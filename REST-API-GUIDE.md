# GSBATCH - REST API Extension

Complete guide for the REST API reader extension in gsbatch framework.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Configuration](#configuration)
4. [Authentication](#authentication)
5. [Pagination Strategies](#pagination-strategies)
6. [JSON Extraction](#json-extraction)
7. [Retry Logic](#retry-logic)
8. [Testing](#testing)
9. [Troubleshooting](#troubleshooting)

---

## Overview

The REST API extension enables gsbatch to ingest data from HTTP/REST APIs with:

- **Pagination**: Automatic iteration through paginated endpoints (PAGE_SIZE, OFFSET_LIMIT, CURSOR)
- **Authentication**: API_KEY, BEARER token, OAuth2 client credentials
- **Bind variables**: Resolve `:paramName` from jobParameters (same as SQL)
- **Environment variables**: Resolve `${VAR}` for secrets
- **JsonPath extraction**: Navigate nested JSON responses
- **Retry logic**: Automatic retry on transient errors (429, 503, 504)
- **Type conversion**: Map JSON fields to typed GenericRecord columns

---

## Architecture

### Components

```
GenericIngestionJobConfig
    ↓
GenericItemReaderFactory
    ↓ (if type=REST)
RestGenericItemReaderBuilder
    ├─ builds RestTemplate (auth interceptor)
    ├─ builds RetryTemplate (backoff policy)
    └─ creates RestGenericItemReader
         ├─ resolves bind variables (:paramName from jobParameters)
         ├─ resolves env vars (${VAR} from System.getenv())
         ├─ fetches paginated JSON responses
         ├─ extracts items via JsonPath ($.data.orders)
         └─ converts JSON → GenericRecord
```

### New Files

**Framework (gsbatch):**
- `RestConfig.java` - YAML model for REST configuration
- `RestGenericItemReader.java` - Paginated reader with retry
- `RestGenericItemReaderBuilder.java` - Factory with auth + retry setup
- `SourceConfig.java` - (+`RestConfig rest` field)
- `GenericItemReaderFactory.java` - (+case REST)

**Application (backoffice):**
- `orders-api.yml` - Example REST source configuration
- `posts-jsonplaceholder.yml` - Test source with public API
- `PostsJsonplaceholderWriter.java` - Test writer (console logging)

**Dependencies (gsbatch/pom.xml):**
- `json-path` 2.9.0 - JsonPath extraction
- `spring-web` - RestTemplate
- `spring-retry` - Retry logic

---

## Configuration

### Full YAML Example

```yaml
name: orders-api
type: REST
chunkSize: 100

rest:
  url: https://api.bank.com/v2/orders
  method: GET
  
  # Bind variables resolved from jobParameters
  queryParams:
    status:       :status          # from jobParameter 'status=NEW'
    trade_date:   :process_date    # from jobParameter 'process_date=2024-01-15'
    desk:         :desk            # from jobParameter 'desk=EQUITY'
  
  headers:
    Accept:       application/json
    X-Client-Id:  backoffice-batch
  
  # Authentication
  auth:
    type: API_KEY
    apiKey: ${API_KEY_ORDERS}      # resolved from environment variable
    headerName: X-Api-Key
  
  # JSON extraction
  dataPath: $.data.orders
  
  # Pagination
  pagination:
    strategy:   PAGE_SIZE
    pageParam:  page
    sizeParam:  size
    pageSize:   100
    totalPath:  $.meta.total       # optional, for logging progress
  
  # Retry on transient errors
  retry:
    maxRetries: 3
    retryDelay: 2000               # milliseconds
    retryOnHttpCodes: [429, 503, 504]

# Column mapping from JSON to GenericRecord
columns:
  - name: order_id
    type: STRING
    jsonPath: $.orderId            # JSON key is 'orderId', column name is 'order_id'
    required: true
  
  - name: customer_id
    type: STRING
    jsonPath: $.customer.id        # nested field extraction
  
  - name: amount
    type: DECIMAL
    jsonPath: $.totalAmount
  
  - name: currency
    type: STRING
    # No jsonPath - direct mapping (JSON key = column name)
  
  - name: order_date
    type: DATE
    format: "yyyy-MM-dd'T'HH:mm:ss'Z'"
    jsonPath: $.createdAt

# Writer configuration (same as SQL sources)
writer:
  type: SQL
  sqlDirectory: /opt/batch/sql
  sqlFile: insert_orders.sql
  onError: SKIP
  skipLimit: 10
```

### Running the Job

```bash
# Set environment variables for secrets
export API_KEY_ORDERS=your_api_key_here

# Launch job with bind variable values
java -jar backoffice.jar \
  sourceName=orders-api \
  status=NEW \
  process_date=2024-01-15 \
  desk=EQUITY
```

---

## Authentication

### Supported Types

| Type | Use Case | Configuration |
|------|----------|---------------|
| **NONE** | Public APIs | `auth.type: NONE` |
| **API_KEY** | Static API key in header | `auth.type: API_KEY`<br>`auth.apiKey: ${VAR}`<br>`auth.headerName: X-Api-Key` |
| **BEARER** | Static bearer token | `auth.type: BEARER`<br>`auth.bearerToken: ${VAR}` |
| **OAUTH2_CLIENT_CREDENTIALS** | OAuth2 flow | Not yet implemented |

### API_KEY Example

```yaml
auth:
  type: API_KEY
  apiKey: ${API_KEY_ORDERS}      # resolved from env var
  headerName: X-Api-Key          # default if not specified
```

**HTTP Request:**
```
GET /v2/orders?status=NEW HTTP/1.1
Host: api.bank.com
X-Api-Key: abc123xyz456
Accept: application/json
```

### BEARER Example

```yaml
auth:
  type: BEARER
  bearerToken: ${BEARER_TOKEN}
```

**HTTP Request:**
```
GET /v2/orders HTTP/1.1
Host: api.bank.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Environment Variable Resolution

**Syntax:** `${VAR_NAME}`

**Resolution:**
- Resolved once during reader initialization
- Throws `IllegalStateException` if variable not found
- Supports nested resolution (bind vars → env vars)

**Example:**
```yaml
auth:
  apiKey: ${API_KEY_ORDERS}
```

```bash
# Set before running the job
export API_KEY_ORDERS=sk_live_abc123
```

---

## Pagination Strategies

### 1. NONE - Single Request

Entire dataset returned in one call. No pagination.

```yaml
pagination:
  strategy: NONE
```

**Use case:** Small datasets (<1000 items), non-paginated APIs.

---

### 2. PAGE_SIZE - Page Number + Size

Incremental page number with fixed page size.

```yaml
pagination:
  strategy: PAGE_SIZE
  pageParam:  page
  sizeParam:  size
  pageSize:   100
```

**HTTP calls:**
```
GET /orders?page=0&size=100
GET /orders?page=1&size=100
GET /orders?page=2&size=100
...
```

**Stop condition:** Empty response (0 items returned).

---

### 3. OFFSET_LIMIT - Offset + Limit

Incremental offset with fixed limit.

```yaml
pagination:
  strategy: OFFSET_LIMIT
  offsetParam: offset
  limitParam:  limit
  pageSize:    100
```

**HTTP calls:**
```
GET /orders?offset=0&limit=100
GET /orders?offset=100&limit=100
GET /orders?offset=200&limit=100
...
```

**Stop condition:** Empty response.

---

### 4. CURSOR - Cursor-Based

Next page cursor extracted from current response.

```yaml
pagination:
  strategy:    CURSOR
  cursorParam: cursor
  cursorPath:  $.meta.nextCursor    # JsonPath to extract cursor from response
  pageSize:    100
```

**HTTP calls:**
```
GET /orders?size=100                  # first call (no cursor)
GET /orders?cursor=abc123&size=100   # next call (cursor from previous response)
GET /orders?cursor=def456&size=100
...
```

**Response example:**
```json
{
  "data": {
    "orders": [...]
  },
  "meta": {
    "nextCursor": "abc123"
  }
}
```

**Stop condition:** `nextCursor` is null or absent in response.

---

### 5. LINK_HEADER - Link Header

Next page URL extracted from HTTP `Link` header.

```yaml
pagination:
  strategy: LINK_HEADER
```

**HTTP response headers:**
```
Link: <https://api.bank.com/orders?page=2>; rel="next"
```

**Status:** Not yet implemented.

---

## JSON Extraction

### JsonPath Syntax

Used to navigate nested JSON responses.

**Examples:**

| JsonPath | Description | Example JSON |
|----------|-------------|--------------|
| `$` | Root array | `[{...}, {...}]` |
| `$.data` | Object at root | `{"data": {...}}` |
| `$.data.orders` | Nested array | `{"data": {"orders": [...]}}` |
| `$.items[*]` | Array elements | `{"items": [{...}, {...}]}` |
| `$.customer.id` | Nested field | `{"customer": {"id": "C001"}}` |

### dataPath - Locating Items

Specifies where the item array is located in the response.

**Example 1 - Nested array:**
```yaml
dataPath: $.data.orders
```

```json
{
  "success": true,
  "data": {
    "orders": [
      {"orderId": "O001", "amount": 1500},
      {"orderId": "O002", "amount": 2300}
    ]
  },
  "meta": {"total": 2}
}
```

**Example 2 - Root array:**
```yaml
dataPath: $
```

```json
[
  {"id": 1, "title": "Post 1"},
  {"id": 2, "title": "Post 2"}
]
```

### Column jsonPath - Field Mapping

Maps JSON keys to GenericRecord columns.

**Use when:**
- JSON key ≠ column name
- Nested field extraction
- Complex transformations

**Example:**
```yaml
columns:
  - name: order_id
    jsonPath: $.orderId              # JSON key is 'orderId'
  
  - name: customer_id
    jsonPath: $.customer.id          # nested extraction
  
  - name: amount
    jsonPath: $.pricing.totalAmount  # deeply nested
  
  - name: currency
    # No jsonPath - direct mapping (JSON key = 'currency')
```

**JSON:**
```json
{
  "orderId": "O001",
  "customer": {"id": "C001", "name": "Acme Corp"},
  "pricing": {"totalAmount": 1500.00, "tax": 300.00},
  "currency": "EUR"
}
```

**GenericRecord:**
```
{
  order_id: "O001",
  customer_id: "C001",
  amount: 1500.00,
  currency: "EUR"
}
```

---

## Retry Logic

### Configuration

```yaml
retry:
  maxRetries: 3                      # number of retry attempts
  retryDelay: 2000                   # delay between retries (ms)
  retryOnHttpCodes: [429, 503, 504]  # HTTP codes that trigger retry
```

### HTTP Status Codes

| Code | Meaning | Retry? | Reason |
|------|---------|--------|--------|
| 200-299 | Success | No | Request succeeded |
| 400 | Bad Request | No | Client error (fix the request) |
| 401 | Unauthorized | No | Invalid credentials |
| 403 | Forbidden | No | Access denied |
| 404 | Not Found | No | Resource doesn't exist |
| **429** | Too Many Requests | **Yes** | Rate limit - retry after delay |
| 500 | Internal Server Error | No | Server error (may not be transient) |
| **503** | Service Unavailable | **Yes** | Temporary outage |
| **504** | Gateway Timeout | **Yes** | Timeout - may succeed on retry |

### Retry Flow

```
Attempt 1: HTTP 429 → wait 2000ms → retry
Attempt 2: HTTP 429 → wait 2000ms → retry
Attempt 3: HTTP 429 → wait 2000ms → retry
Attempt 4: HTTP 429 → FAIL (maxRetries=3 exhausted)
```

### Disabling Retry

```yaml
retry:
  maxRetries: 0
```

---

## Testing

### Test with JSONPlaceholder

Public fake API for testing: https://jsonplaceholder.typicode.com

**1. Create test source YAML:**
```yaml
# src/main/resources/ingestion/posts-jsonplaceholder.yml
name: posts-jsonplaceholder
type: REST
chunkSize: 10

rest:
  url: https://jsonplaceholder.typicode.com/posts
  method: GET
  queryParams:
    userId: :userId
  auth:
    type: NONE
  dataPath: $
  pagination:
    strategy: NONE

columns:
  - name: post_id
    type: INTEGER
    jsonPath: $.id
  - name: user_id
    type: INTEGER
    jsonPath: $.userId
  - name: title
    type: STRING
  - name: body
    type: STRING
```

**2. Create test writer:**
```java
// PostsJsonplaceholderWriter.java
@Slf4j
@Component("postsJsonplaceholderWriter")
public class PostsJsonplaceholderWriter implements ItemWriter<GenericRecord> {
    @Override
    public void write(Chunk<? extends GenericRecord> chunk) {
        log.info("Writing {} posts", chunk.size());
        for (GenericRecord record : chunk) {
            log.info("  Post #{}: '{}'", 
                    record.getInt("post_id"), 
                    record.getString("title"));
        }
    }
}
```

**3. Run test:**
```bash
java -jar backoffice.jar sourceName=posts-jsonplaceholder userId=1
```

**Expected output:**
```
RestGenericItemReader initialized for source 'posts-jsonplaceholder'
Fetching page: https://jsonplaceholder.typicode.com/posts?userId=1
Extracted 10 items from JSON
Writing 10 posts
  Post #1: 'sunt aut facere repellat provident...'
  Post #2: 'qui est esse'
  ...
Job completed: COMPLETED
```

---

## Troubleshooting

### "Environment variable not found: ${API_KEY_ORDERS}"

**Cause:** Environment variable not set before running the job.

**Fix:**
```bash
export API_KEY_ORDERS=your_key_here
java -jar backoffice.jar sourceName=orders-api ...
```

---

### "Bind variable not found: ':status'"

**Cause:** jobParameter not provided in command line.

**Fix:**
```bash
# Missing: status=NEW
java -jar backoffice.jar sourceName=orders-api status=NEW process_date=2024-01-15
```

---

### "JsonPath '$.data.orders' returned null"

**Cause:** JsonPath doesn't match the actual JSON structure.

**Debug:**
1. Check the raw JSON response in logs (enable DEBUG logging)
2. Test JsonPath online: https://jsonpath.com
3. Adjust `dataPath` to match actual structure

**Example fix:**
```yaml
# If response is: {"orders": [...]}  (not nested in "data")
dataPath: $.orders   # instead of $.data.orders
```

---

### "No value supplied for SQL parameter 'product_code'"

**Cause:** Column declared in `columns:` but missing from JSON response.

**Fix 1 - Make column optional:**
```yaml
columns:
  - name: product_code
    type: STRING
    required: false    # allow null
```

**Fix 2 - Use correct JsonPath:**
```yaml
columns:
  - name: product_code
    jsonPath: $.productCode   # correct JSON key
```

---

### HTTP 401 Unauthorized

**Cause:** Invalid or expired API key/token.

**Fix:**
1. Verify environment variable value
2. Check API key validity in API provider dashboard
3. Regenerate key if expired

---

### HTTP 429 Too Many Requests

**Cause:** Rate limit exceeded.

**Fix:**
1. Increase `retryDelay` to give more time between retries
2. Reduce `pageSize` to fetch fewer items per call
3. Reduce `chunkSize` to make smaller batches

```yaml
pagination:
  pageSize: 50        # reduced from 100

retry:
  retryDelay: 5000    # increased from 2000ms
```

---

## Summary

The REST API extension makes gsbatch fully compatible with modern HTTP APIs while maintaining the same configuration-driven approach as SQL and CSV sources. Key features:

- **Zero Java code** for new REST sources (YAML only)
- **Pagination** handled automatically
- **Authentication** via interceptors
- **Retry logic** for resilience
- **JsonPath** for complex JSON structures
- **Type-safe** conversion to GenericRecord

Next steps: implement OAUTH2_CLIENT_CREDENTIALS and LINK_HEADER pagination if needed.
