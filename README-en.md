# GSBatch — Documentation

**Generic Spring Batch ingestion framework**
Stack: Java 21 · Spring Boot 3.4.x · Spring Batch 5

> Documentation available in: [Français](README-fr.md) · **English**

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [YAML Configuration Reference](#3-yaml-configuration-reference)
4. [Source Types](#4-source-types)
   - [CSV](#41-csv)
   - [SQL](#42-sql)
   - [REST](#43-rest)
   - [SOAP](#44-soap)
5. [Implementing a New Source (backoffice guide)](#5-implementing-a-new-source-backoffice-guide)
6. [Spring Batch 5 Best Practices](#6-spring-batch-5-best-practices)
7. [Tests](#7-tests)
8. [Operations](#8-operations)
9. [Common Error Troubleshooting](#9-common-error-troubleshooting)
10. [Roadmap](#10-roadmap)

---

## 1. Overview

gsbatch is a **library** (JAR, not executable) that provides a generic, configuration-driven data ingestion framework. Consumer applications (e.g. backoffice) depend on this JAR and supply:

- their own YAML configuration files (`classpath:ingestion/{sourceName}.yml`)
- their own business beans (`{sourceName}Writer`, `{sourceName}Processor`)

### Core Principle

> A single generic job, parameterized by `sourceName`. Zero Java code needed to add a new source.

The framework provides:

- Single generic job with 3 steps (preprocessing, ingestion, postprocessing)
- YAML loader with caching
- Factories: Reader, Processor, Writer
- Builders: CSV, SQL, REST, SOAP

The framework does not contain:

- Business logic
- Concrete Writers or Processors
- Application-specific YAML files

---

## 2. Architecture

### Execution Flow

```
JobParameters (sourceName, ...)
  → YamlSourceConfigLoader.load(sourceName)
      classpath:ingestion/{sourceName}.yml
  → GenericIngestionJob (3 steps):
      1. genericPreprocessingStep   @JobScope  → GenericTasklet (SQL or Java bean)
      2. genericIngestionStep       @JobScope  → Reader → Processor → Writer (@StepScope)
      3. genericPostprocessingStep  @JobScope  → GenericTasklet (SQL or Java bean)
```

### Main Components

#### Generic Job

```java
@Bean
public Job genericIngestionJob(JobRepository jobRepository, Step genericIngestionStep) {
    return new JobBuilder("genericIngestionJob", jobRepository)
            .start(genericIngestionStep)
            .build();
}
```

#### Generic Step

```java
@Bean
public Step genericIngestionStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        ItemReader<GenericRecord> genericIngestionReader,
        ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor,
        ItemWriter<GenericRecord> genericIngestionWriter,
        @Value("#{jobParameters['sourceName']}") String sourceName) {

    SourceConfig config = configLoader.load(sourceName);

    return new StepBuilder("genericIngestionStep-" + sourceName, jobRepository)
            .<GenericRecord, GenericRecord>chunk(config.getChunkSize(), transactionManager)
            .reader(genericIngestionReader)
            .processor(genericIngestionProcessor)
            .writer(genericIngestionWriter)
            .build();
}
```

#### StepScope Beans

```java
@Bean @StepScope
public ItemReader<GenericRecord> genericIngestionReader(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return readerFactory.buildReader(configLoader.load(sourceName));
}

@Bean @StepScope
public ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return processorFactory.buildProcessor(configLoader.load(sourceName));
}

@Bean @StepScope
public ItemWriter<GenericRecord> genericIngestionWriter(
        @Value("#{jobParameters['sourceName']}") String sourceName) {
    return writerFactory.buildWriter(configLoader.load(sourceName));
}
```

#### YamlSourceConfigLoader

```java
@Component
public class YamlSourceConfigLoader {

    @Cacheable(value = "sourceConfigs", key = "#sourceName")
    public SourceConfig load(String sourceName) {
        // Loads classpath:ingestion/{sourceName}.yml
        // Validates and returns the config
    }
}
```

Features: `@Cacheable` cache, automatic validation, fine-grained exception handling, secure SnakeYAML (typed to `SourceConfig`).

### Key Data Models

#### GenericRecord

Flexible key-value container carrying a single row across all steps.

```java
public class GenericRecord {
    void    put(String name, Object value)
    Object  get(String name)
    String  getString(String name)
    Integer getInteger(String name)
    Double  getDouble(String name)
    Long    getLong(String name)
    Map<String, Object> getValues()   // unmodifiable
}
```

#### SourceConfig

YAML root object. Fields common to all types:

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Source identifier (= `sourceName`) |
| `type` | String | `CSV`, `SQL`, `REST`, `SOAP` |
| `chunkSize` | int | Spring Batch chunk size |
| `columns` | List\<ColumnConfig\> | Column definitions |
| `writer` | WriterConfig | Writer configuration (optional) |
| `preprocessing` | TaskletConfig | Pre-processing (optional) |
| `postprocessing` | TaskletConfig | Post-processing (optional) |

#### ColumnConfig

| Field | Description |
|-------|-------------|
| `name` | Column name |
| `type` | `STRING`, `INTEGER`, `DECIMAL`, `DATE`, `BOOLEAN` |
| `format` | Format for dates / numbers |
| `required` | Error if value is missing |
| `defaultValue` | Default value if absent |
| `jsonPath` | JsonPath extraction (REST) |
| `xpath` | XPath extraction (SOAP) |

### Factories

#### GenericItemReaderFactory

| `type` | Builder | Reader returned |
|--------|---------|-----------------|
| `CSV` | `CsvGenericItemReaderBuilder` | `FlatFileItemReader<GenericRecord>` |
| `SQL` | `SqlGenericItemReaderBuilder` | `JdbcCursorItemReader<GenericRecord>` |
| `REST` | `RestGenericItemReaderBuilder` | `RestGenericItemReader` |
| `SOAP` | `SoapGenericItemReaderBuilder` | `SoapGenericItemReader` |

#### GenericItemProcessorFactory

- Looks for bean `{sourceName}Processor` → uses it if found
- Otherwise → pass-through (`item -> item`)

The processor is always **optional**.

#### GenericItemWriterFactory

Resolution in order:

1. `writer.type=SQL` → `SqlGenericItemWriter` (loads SQL file, binds jobParameters + record fields)
2. `writer.type=JAVA` → looks up `writer.beanName`
3. No writer config → looks up `{sourceName}Writer` (convention, **required**)

#### GenericTasklet (pre/post processing)

- `type=SQL` → loads a SQL file, binds `jobParameters`, executes
- `type=JAVA` → looks up `{sourceName}PreprocessingTasklet` / `{sourceName}PostprocessingTasklet`
- `enabled=false` or absent → no-op

### Variable Resolution

Both mechanisms apply to URLs, SQL queries, and request templates:

- **Bind variables** `:paramName` → resolved from `jobParameters`
- **Environment variables** `${VAR_NAME}` → resolved from `System.getenv()`

### SQL File Loading

`SqlFileLoader` resolves paths in order:
1. Absolute path
2. `classpath:` prefix
3. File system relative path

---

## 3. YAML Configuration Reference

### Minimal Structure

```yaml
name: trades
type: CSV
chunkSize: 500

columns:
  - name: trade_id
    type: STRING
    required: true
  - name: amount
    type: DECIMAL
    format: "#0.00"
  - name: trade_date
    type: DATE
    format: "yyyy-MM-dd"
```

### With Pre/Post Processing

```yaml
name: trades
type: CSV
chunkSize: 500

preprocessing:
  enabled: true
  type: SQL
  sqlFile: classpath:sql/trades_pre.sql

postprocessing:
  enabled: true
  type: JAVA   # looks up bean 'tradesPostprocessingTasklet'

columns:
  - name: trade_id
    type: STRING
```

### With SQL Writer

```yaml
writer:
  type: SQL
  sqlFile: classpath:sql/insert_trades.sql
  onError: SKIP
  skipLimit: 10
```

---

## 4. Source Types

### 4.1 CSV

```yaml
name: trades
type: CSV
chunkSize: 500
path: "data/trades.csv"
delimiter: ";"
skipHeader: true

columns:
  - name: trade_id
    type: STRING
    required: true
  - name: instrument
    type: STRING
  - name: quantity
    type: INTEGER
  - name: price
    type: DECIMAL
    format: "#0.00"
  - name: trade_date
    type: DATE
    format: "yyyy-MM-dd"
```

**Implementation notes:**

- `FlatFileItemReader` implements `ItemStream` — do not call `afterPropertiesSet()` manually
- Spring Batch manages the lifecycle (`open` / `read` / `update` / `close`)

---

### 4.2 SQL

```yaml
name: orders
type: SQL
chunkSize: 1000
query: "SELECT order_id, customer_id, amount FROM orders WHERE status = 'NEW'"

columns:
  - name: order_id
    type: STRING
  - name: customer_id
    type: STRING
  - name: amount
    type: DECIMAL
```

---

### 4.3 REST

#### Full Configuration

```yaml
name: orders-api
type: REST
chunkSize: 100

rest:
  url: https://api.bank.com/v2/orders
  method: GET

  queryParams:
    status:      :status          # bind variable → jobParameter 'status'
    trade_date:  :process_date    # bind variable → jobParameter 'process_date'
    desk:        :desk

  headers:
    Accept:       application/json
    X-Client-Id:  backoffice-batch

  auth:
    type: API_KEY
    apiKey: ${API_KEY_ORDERS}     # environment variable
    headerName: X-Api-Key

  dataPath: $.data.orders         # JsonPath to the result array

  pagination:
    strategy:  PAGE_SIZE
    pageParam: page
    sizeParam: size
    pageSize:  100
    totalPath: $.meta.total       # optional, for logging

  retry:
    maxRetries: 3
    retryDelay: 2000              # ms
    retryOnHttpCodes: [429, 503, 504]

columns:
  - name: order_id
    type: STRING
    jsonPath: $.orderId
    required: true
  - name: customer_id
    type: STRING
    jsonPath: $.customer.id       # nested field
  - name: amount
    type: DECIMAL
    jsonPath: $.totalAmount
  - name: currency
    type: STRING                  # no jsonPath → JSON key = column name
  - name: order_date
    type: DATE
    format: "yyyy-MM-dd'T'HH:mm:ss'Z'"
    jsonPath: $.createdAt
```

#### Authentication

| Type | Use case | Configuration |
|------|----------|---------------|
| `NONE` | Public API | `auth.type: NONE` |
| `API_KEY` | Static key in header | `apiKey: ${VAR}` · `headerName: X-Api-Key` |
| `BEARER` | Static Bearer token | `bearerToken: ${VAR}` |
| `OAUTH2_CLIENT_CREDENTIALS` | OAuth2 flow | Not yet implemented |

BEARER example:

```yaml
auth:
  type: BEARER
  bearerToken: ${BEARER_TOKEN}
```

Generated HTTP request:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

#### Pagination Strategies

**NONE** — Single request, no pagination.

```yaml
pagination:
  strategy: NONE
```

**PAGE_SIZE** — Incremental page number.

```yaml
pagination:
  strategy: PAGE_SIZE
  pageParam: page
  sizeParam: size
  pageSize:  100
```

Generated calls: `GET /orders?page=0&size=100`, `page=1`, `page=2`...
Stops when: response is empty.

**OFFSET_LIMIT** — Incremental offset.

```yaml
pagination:
  strategy:    OFFSET_LIMIT
  offsetParam: offset
  limitParam:  limit
  pageSize:    100
```

Generated calls: `GET /orders?offset=0&limit=100`, `offset=100`...
Stops when: response is empty.

**CURSOR** — Cursor extracted from each response.

```yaml
pagination:
  strategy:   CURSOR
  cursorParam: cursor
  cursorPath:  $.meta.nextCursor
  pageSize:    100
```

Stops when: `nextCursor` is null or absent.

**LINK_HEADER** — Next page URL in HTTP `Link` header. Not yet implemented.

#### JSON Extraction (JsonPath)

`dataPath` locates the result array within the response:

| dataPath | Expected JSON structure |
|----------|------------------------|
| `$.data.orders` | `{"data": {"orders": [...]}}` |
| `$.orders` | `{"orders": [...]}` |
| `$` | `[{...}, {...}]` (root array) |

`jsonPath` on a column maps a JSON key to the column name:

```yaml
columns:
  - name: order_id
    jsonPath: $.orderId           # JSON key = 'orderId'
  - name: customer_id
    jsonPath: $.customer.id       # nested extraction
  - name: currency
    # no jsonPath → JSON key = 'currency'
```

#### Retry

| HTTP Code | Retry? | Reason |
|-----------|--------|--------|
| 200-299 | No | Success |
| 400 | No | Client error |
| 401 | No | Invalid credentials |
| 403 | No | Access denied |
| 429 | **Yes** | Rate limit |
| 503 | **Yes** | Temporary unavailability |
| 504 | **Yes** | Gateway timeout |

Retry flow:
```
Attempt 1: HTTP 429 → wait 2000ms → retry
Attempt 2: HTTP 429 → wait 2000ms → retry
Attempt 3: HTTP 429 → wait 2000ms → retry
Attempt 4: HTTP 429 → FAIL (maxRetries=3 exhausted)
```

To disable: `retry.maxRetries: 0`

---

### 4.4 SOAP

```yaml
name: trades-soap
type: SOAP
chunkSize: 200

soap:
  endpoint: https://ws.bank.com/trades
  soapAction: "urn:getTrades"
  soapVersion: "1.1"             # 1.1 or 1.2

  requestTemplate: |
    <soapenv:Envelope xmlns:soapenv="...">
      <soapenv:Body>
        <getTrades>
          <date>:process_date</date>
        </getTrades>
      </soapenv:Body>
    </soapenv:Envelope>

  auth:
    type: BASIC                  # NONE | BASIC | WS_SECURITY | CUSTOM_HEADER
    username: ${SOAP_USER}
    password: ${SOAP_PASS}

  dataXPath: "//trade"

columns:
  - name: trade_id
    type: STRING
    xpath: "tradeId"
  - name: amount
    type: DECIMAL
    xpath: "amount"
```

---

## 5. Implementing a New Source (backoffice guide)

No modifications to gsbatch are required to add a source.

### Checklist

1. Create `src/main/resources/ingestion/{source}.yml`
2. Create the data file if applicable (CSV, etc.)
3. Create the writer `@Component("{source}Writer")` — **required**
4. Create the processor `@Component("{source}Processor")` — optional
5. Test with `sourceName={source}`

### Business Processor (optional)

```java
@Component("tradesProcessor")
public class TradesProcessor implements ItemProcessor<GenericRecord, GenericRecord> {

    @Override
    public GenericRecord process(GenericRecord item) throws Exception {
        String tradeId = item.getString("tradeId");
        if (tradeId == null || tradeId.isBlank()) {
            return null;   // null = skip the record
        }
        // Enrichment
        if ("EUR".equals(item.getString("currency"))) {
            item.put("priceUSD", item.getDouble("price") * 1.1);
        }
        return item;
    }
}
```

Convention: `{sourceName}Processor` (e.g. `tradesProcessor` for source `trades`).

### Business Writer (required)

```java
@Component("tradesWriter")
public class TradesWriter implements ItemWriter<GenericRecord> {

    @Autowired
    private TradeRepository repository;

    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        List<Trade> trades = chunk.getItems().stream()
            .map(this::toEntity)
            .collect(Collectors.toList());
        repository.saveAll(trades);
    }

    private Trade toEntity(GenericRecord record) {
        Trade trade = new Trade();
        trade.setTradeId(record.getString("tradeId"));
        trade.setQuantity(record.getInteger("quantity"));
        trade.setPrice(record.getDouble("price"));
        return trade;
    }
}
```

Convention: `{sourceName}Writer` (e.g. `tradesWriter`).

### Adding a New Reader Type (framework extension)

To add a new type (e.g. `JSON`, `XML`):

1. Create the model class in `core/model/` (like `RestConfig` / `SoapConfig`)
2. Add it to `SourceConfig`
3. Create the builder in `core/reader/` implementing the reader
4. Add a `case` in `GenericItemReaderFactory`

---

## 6. Spring Batch 5 Best Practices

### Chunk API

```java
// Correct (Spring Batch 5)
.chunk(chunkSize, transactionManager)

// Deprecated (Spring Batch 4)
.chunk(chunkSize)
```

### StepScope and CGLIB Proxies

Spring creates CGLIB proxies for `@StepScope` beans. The Step receives the proxy, not the actual instance.

```java
// Correct: inject the proxy, Spring Batch will use it at the right time
@Bean
public Step step(ItemReader<GenericRecord> reader) {
    return stepBuilder.chunk(100, tm)
        .reader(reader)   // proxy passed, late binding
        .build();
}

// Incorrect: calling the bean with null
ItemReader reader = readerBean(null);   // error
```

### ItemStream Lifecycle

Spring Batch automatically calls these methods on readers/writers that implement `ItemStream`:

```
open(ExecutionContext)    → initialization (open file, connect...)
read()                    → repeated reading
update(ExecutionContext)  → checkpoint after each chunk
close()                   → cleanup
```

Never call `afterPropertiesSet()` or these methods manually.

### @JobScope vs @StepScope

| Scope | Lifetime | Possible injection |
|-------|----------|--------------------|
| `@JobScope` | Duration of the job | `jobParameters` via `@Value` |
| `@StepScope` | Duration of one step | `jobParameters` + `stepExecutionContext` |

Readers, processors, and writers must be `@StepScope` to enable late parameter binding.

---

## 7. Tests

Stack: JUnit 5 · Mockito 5 · AssertJ — pure unit tests, no Spring context.

```bash
# Run tests
mvn test

# Current result
Tests run: 168, Failures: 0, Errors: 0, Skipped: 0
```

### Test Suite

| Test Class | Tests | Component covered |
|------------|-------|-------------------|
| `GenericRecordTest` | 23 | Data container, type conversions |
| `SourceConfigTest` | 26 | YAML validation (CSV, SQL, REST, SOAP, chunkSize) |
| `WriterConfigTest` | 14 | Writer validation (type, onError, skipLimit) |
| `StepConfigTest` | 11 | Pre/post processing validation |
| `ColumnConfigTest` | 6 | Column validation |
| `SqlFileLoaderTest` | 17 | SQL loading, bind vars, comments, PG cast `::` |
| `YamlSourceConfigLoaderTest` | 8 | YAML loading, path traversal protection |
| `GenericItemReaderFactoryTest` | 12 | CSV/SQL/REST/SOAP dispatch, unknown types |
| `GenericItemProcessorFactoryTest` | 7 | Pass-through, custom bean, camelCase naming |
| `GenericItemWriterFactoryTest` | 10 | Declarative SQL/JAVA, bean convention, camelCase |
| `SqlGenericItemWriterTest` | 4 | Batch write, empty chunk |
| `GenericTaskletTest` | 8 | Disabled, SQL, JAVA, unknown type |

### Code Coverage

Generated with: `mvn clean verify -P coverage` → `target/site/jacoco/index.html`

| Package | Lines | Coverage |
|---------|-------|----------|
| `processor` | 35/35 | **100%** |
| `tasklet` | 62/64 | **97%** |
| `writer` | 90/94 | **96%** |
| `config` | 35/52 | 67% |
| `model` | 199/329 | 60% |
| `exception` | 2/4 | 50% |
| `reader` | 120/804 | 15% |
| `job` | 0/64 | 0% |
| **Total** | **543/1 446** | **38%** |

**`reader` (15%):** CSV/SQL/REST/SOAP builders and HTTP readers would require a real filesystem, database, or mock HTTP server — integration tests planned.
**`job` (0%):** `GenericIngestionJobConfig` requires a full Spring Batch context (`@SpringBatchTest` + H2) — integration tests planned.

---

## 8. Operations

### Launching a Job

```bash
# Environment variable for secrets
export API_KEY_ORDERS=your_api_key_here

# Launch
java -jar backoffice.jar \
  sourceName=trades \
  process_date=2024-01-15
```

### Maven Commands

```bash
# Build and install to local Maven repository
mvn clean install

# Skip tests
mvn clean install -DskipTests

# Production build (enforces no SNAPSHOT dependencies)
mvn install -P prod
```

### Maven Profiles

| Profile | Role |
|---------|------|
| `dev` (default) | Development |
| `prod` | Prohibits SNAPSHOT dependencies |
| `coverage` | JaCoCo report |

### Production Deployment Checklist

- [ ] All tests pass
- [ ] Cache configured (Caffeine or Redis)
- [ ] Log level set to INFO
- [ ] Secret environment variables defined
- [ ] YAML configuration files validated
- [ ] Writers implemented for all sources
- [ ] Rollback plan defined

---

## 9. Common Error Troubleshooting

### `ReaderNotOpenException`

**Cause:** The reader does not implement `ItemStream` or `open()` was not called.
**Solution:** Use `FlatFileItemReader` (implements `ItemStream`).

### `NullPointerException` in Step

**Cause:** `@StepScope` bean called with `null` instead of injecting the proxy.
**Solution:** Inject the Spring proxy; do not call it directly.

### `No writer bean found` / `IllegalStateException: Writer bean required`

**Cause:** Business writer missing from the consumer application.
**Solution:** Create `@Component("{sourceName}Writer")`.

### `Configuration file not found`

**Cause:** YAML file missing or incorrectly named.
**Solution:** Check `classpath:ingestion/{sourceName}.yml`.

### `Environment variable not found: ${API_KEY_ORDERS}`

**Cause:** Environment variable not set before launch.
**Solution:** `export API_KEY_ORDERS=your_key_here` before starting the job.

### `Bind variable not found: ':status'`

**Cause:** Job parameter not provided on the command line.
**Solution:** Add `status=NEW` to the job parameters.

### `JsonPath '$.data.orders' returned null`

**Cause:** The JsonPath does not match the actual JSON structure.
**Solution:**
1. Enable DEBUG logs to display the raw response
2. Test the JsonPath at https://jsonpath.com
3. Adjust `dataPath` to match the actual structure

### HTTP 401 Unauthorized

**Cause:** Invalid or expired API key or token.
**Solution:** Check the environment variable value; regenerate if necessary.

### HTTP 429 Too Many Requests

**Cause:** Rate limit exceeded.
**Solution:**

```yaml
pagination:
  pageSize: 50         # reduced from 100
retry:
  retryDelay: 5000     # increased from 2000ms
```

### Chunk Size Ignored

**Cause:** Value hard-coded instead of being read from YAML.
**Solution:** Use `config.getChunkSize()` in the step builder.

---

## 10. Roadmap

### Short Term

- Configurable Skip / Retry policies in YAML
- Job parameter validation at startup
- Listeners for monitoring (read count, write count, duration)

### Medium Term

- Micrometer metrics / Grafana dashboards
- Native JSON and XML reading support
- Partitioning for parallelization
- OAuth2 client credentials support (REST)
- LINK_HEADER pagination support (REST)

### Long Term

- Cloud connectors: S3, Azure Blob, GCS
- Incremental ingestion (CDC)
- Automatic schema detection

---

## License

This project is distributed under the **Apache License, Version 2.0**.
See the [LICENSE](LICENSE) file for the full text.

Copyright 2026 Makalisio Contributors

---

*Consolidated documentation — last updated: March 1, 2026*
