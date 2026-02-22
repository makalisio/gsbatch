package org.makalisio.gsbatch.core.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.ColumnConfig;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.http.*;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST API ItemReader with pagination, retry, and JSON extraction.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Paginated HTTP calls (PAGE_SIZE, OFFSET_LIMIT, CURSOR strategies)</li>
 *   <li>Bind variable resolution (:paramName from jobParameters)</li>
 *   <li>Environment variable resolution (${VAR})</li>
 *   <li>JsonPath extraction from nested JSON responses</li>
 *   <li>Automatic retry on transient HTTP errors (429, 503, 504)</li>
 *   <li>Authentication (API_KEY, BEARER, OAUTH2)</li>
 * </ul>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class RestGenericItemReader implements ItemStreamReader<GenericRecord> {

    private static final Pattern BIND_PARAM_PATTERN = Pattern.compile("(?<![:])(:[a-zA-Z][a-zA-Z0-9_]*)");
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SourceConfig sourceConfig;
    private final RestConfig restConfig;
    private final Map<String, Object> jobParameters;
    private final RestTemplate restTemplate;
    private final RetryTemplate retryTemplate;
    private final Configuration jsonPathConfig;

    // Pagination state
    private int currentPage = 0;
    private int currentOffset = 0;
    private String currentCursor = null;
    private Integer totalItems = null;
    private int itemsRead = 0;

    // Buffer for items from current page
    private Queue<GenericRecord> buffer = new LinkedList<>();

    // Resolved values (computed once in open())
    private String resolvedUrl;
    private Map<String, String> resolvedQueryParams;
    private HttpHeaders resolvedHeaders;

    /**
     * @param sourceConfig    source configuration from YAML
     * @param restConfig      REST-specific configuration
     * @param jobParameters   job parameters for bind variable resolution
     * @param restTemplate    configured RestTemplate with auth interceptor
     * @param retryTemplate   configured RetryTemplate for transient errors
     */
    public RestGenericItemReader(SourceConfig sourceConfig,
                                 RestConfig restConfig,
                                 Map<String, Object> jobParameters,
                                 RestTemplate restTemplate,
                                 RetryTemplate retryTemplate) {
        this.sourceConfig = sourceConfig;
        this.restConfig = restConfig;
        this.jobParameters = Collections.unmodifiableMap(jobParameters);
        this.restTemplate = restTemplate;
        this.retryTemplate = retryTemplate;

        // JsonPath configuration: suppress exceptions, return null for missing paths
        this.jsonPathConfig = Configuration.builder()
                .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
                .build();

        log.info("RestGenericItemReader initialized for source '{}'", sourceConfig.getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ItemStreamReader implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void open(org.springframework.batch.item.ExecutionContext executionContext) {
        log.info("Opening REST reader for source '{}'", sourceConfig.getName());

        // Resolve URL, query params, and headers once
        resolvedUrl = resolveVariables(restConfig.getUrl(), "rest.url");
        resolvedQueryParams = resolveQueryParams();
        resolvedHeaders = buildHeaders();

        // Reset pagination state
        currentPage = 0;
        currentOffset = 0;
        currentCursor = null;
        itemsRead = 0;
        buffer.clear();

        log.info("REST reader opened - URL: {}, pagination: {}",
                resolvedUrl, restConfig.getPagination().getStrategy());
    }

    @Override
    public GenericRecord read() throws Exception {
        // If buffer is empty, fetch next page
        if (buffer.isEmpty()) {
            fetchNextPage();
        }

        // Poll next item from buffer (null if no more items)
        GenericRecord record = buffer.poll();
        if (record != null) {
            itemsRead++;
        }

        return record;
    }

    @Override
    public void close() {
        log.info("Closing REST reader for source '{}' - total items read: {}",
                sourceConfig.getName(), itemsRead);
        buffer.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Pagination
    // ─────────────────────────────────────────────────────────────────────────

    private void fetchNextPage() {
        String strategy = restConfig.getPagination().getStrategy().toUpperCase();

        if ("NONE".equals(strategy)) {
            // Single call, no pagination
            if (currentPage > 0) {
                return;  // Already fetched, no more pages
            }
            fetchPage(buildUrl(resolvedUrl, resolvedQueryParams));
            currentPage++;
        }
        else if ("PAGE_SIZE".equals(strategy)) {
            Map<String, String> pageParams = new HashMap<>(resolvedQueryParams);
            pageParams.put(restConfig.getPagination().getPageParam(), String.valueOf(currentPage));
            pageParams.put(restConfig.getPagination().getSizeParam(),
                    String.valueOf(restConfig.getPagination().getPageSize()));

            String pageUrl = buildUrl(resolvedUrl, pageParams);
            List<GenericRecord> items = fetchPage(pageUrl);

            if (items.isEmpty()) {
                log.debug("Page {} returned 0 items - end of pagination", currentPage);
            } else {
                log.debug("Page {} fetched: {} items", currentPage, items.size());
                currentPage++;
            }
        }
        else if ("OFFSET_LIMIT".equals(strategy)) {
            Map<String, String> pageParams = new HashMap<>(resolvedQueryParams);
            pageParams.put(restConfig.getPagination().getOffsetParam(), String.valueOf(currentOffset));
            pageParams.put(restConfig.getPagination().getLimitParam(),
                    String.valueOf(restConfig.getPagination().getPageSize()));

            String pageUrl = buildUrl(resolvedUrl, pageParams);
            List<GenericRecord> items = fetchPage(pageUrl);

            if (items.isEmpty()) {
                log.debug("Offset {} returned 0 items - end of pagination", currentOffset);
            } else {
                log.debug("Offset {} fetched: {} items", currentOffset, items.size());
                currentOffset += items.size();
            }
        }
        else if ("CURSOR".equals(strategy)) {
            Map<String, String> pageParams = new HashMap<>(resolvedQueryParams);
            if (currentCursor != null) {
                pageParams.put(restConfig.getPagination().getCursorParam(), currentCursor);
            }

            String pageUrl = buildUrl(resolvedUrl, pageParams);
            List<GenericRecord> items = fetchPage(pageUrl);

            if (items.isEmpty()) {
                log.debug("Cursor '{}' returned 0 items - end of pagination", currentCursor);
            } else {
                log.debug("Cursor '{}' fetched: {} items", currentCursor, items.size());
            }
        }
        else {
            throw new UnsupportedOperationException(
                    "Pagination strategy not yet implemented: " + strategy);
        }
    }

    private List<GenericRecord> fetchPage(String url) {
        log.debug("Fetching page: {}", url);

        // Execute HTTP request with retry
        String jsonResponse = retryTemplate.execute(context -> {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(restConfig.getMethod()),
                    new HttpEntity<>(resolvedHeaders), String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "HTTP request failed with status: " + response.getStatusCode());
            }

            return response.getBody();
        });

        if (jsonResponse == null || jsonResponse.isBlank()) {
            log.warn("Empty response from API");
            return Collections.emptyList();
        }

        // Extract total count if configured (for logging progress)
        if (totalItems == null && restConfig.getPagination().getTotalPath() != null) {
            try {
                Object totalObj = JsonPath.using(jsonPathConfig)
                        .parse(jsonResponse)
                        .read(restConfig.getPagination().getTotalPath());
                if (totalObj instanceof Number) {
                    totalItems = ((Number) totalObj).intValue();
                    log.info("Total items to fetch: {}", totalItems);
                }
            } catch (Exception e) {
                log.warn("Could not extract total count from response", e);
            }
        }

        // Extract cursor for next page (if CURSOR strategy)
        if ("CURSOR".equalsIgnoreCase(restConfig.getPagination().getStrategy()) &&
                restConfig.getPagination().getCursorPath() != null) {
            try {
                Object cursorObj = JsonPath.using(jsonPathConfig)
                        .parse(jsonResponse)
                        .read(restConfig.getPagination().getCursorPath());
                currentCursor = cursorObj != null ? cursorObj.toString() : null;
                log.debug("Next cursor: {}", currentCursor);
            } catch (Exception e) {
                log.warn("Could not extract cursor from response", e);
                currentCursor = null;
            }
        }

        // Extract items array from JSON
        List<Map<String, Object>> jsonItems = extractItems(jsonResponse);
        log.debug("Extracted {} items from JSON", jsonItems.size());

        // Convert JSON items to GenericRecords
        List<GenericRecord> records = new ArrayList<>();
        for (Map<String, Object> jsonItem : jsonItems) {
            GenericRecord record = convertJsonToRecord(jsonItem);
            records.add(record);
        }

        buffer.addAll(records);
        return records;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(String jsonResponse) {
        try {
            Object extracted = JsonPath.using(jsonPathConfig)
                    .parse(jsonResponse)
                    .read(restConfig.getDataPath());

            if (extracted == null) {
                log.warn("JsonPath '{}' returned null", restConfig.getDataPath());
                return Collections.emptyList();
            }

            if (extracted instanceof List) {
                return (List<Map<String, Object>>) extracted;
            } else if (extracted instanceof Map) {
                // Single item wrapped in object - wrap it in a list
                return List.of((Map<String, Object>) extracted);
            } else {
                log.warn("Unexpected type from JsonPath: {}", extracted.getClass());
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Failed to extract items from JSON using path: {}",
                    restConfig.getDataPath(), e);
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JSON to GenericRecord conversion
    // ─────────────────────────────────────────────────────────────────────────

    private GenericRecord convertJsonToRecord(Map<String, Object> jsonItem) {
        Map<String, Object> recordData = new HashMap<>();

        for (ColumnConfig column : sourceConfig.getColumns()) {
            String columnName = column.getName();
            String jsonPathExpr = column.getJsonPath();

            Object value;
            if (jsonPathExpr != null && !jsonPathExpr.isBlank()) {
                // Use custom JsonPath expression
                value = JsonPath.using(jsonPathConfig)
                        .parse(jsonItem)
                        .read(jsonPathExpr);
            } else {
                // Direct mapping: column name = JSON key
                value = jsonItem.get(columnName);
            }

            // Type conversion
            Object convertedValue = convertValue(value, column);
            recordData.put(columnName, convertedValue);
        }

        return new GenericRecord(recordData);
    }

    private Object convertValue(Object value, ColumnConfig column) {
        if (value == null) {
            return null;
        }

        String type = column.getType().toUpperCase();

        try {
            switch (type) {
                case "STRING":
                    // Map/List from JSON must be serialized as valid JSON, not via toString()
                    if (value instanceof Map || value instanceof List) {
                        try {
                            return OBJECT_MAPPER.writeValueAsString(value);
                        } catch (Exception e) {
                            log.warn("Failed to serialize JSON value for column {}: {}",
                                    column.getName(), e.getMessage());
                            return value.toString();
                        }
                    }
                    return value.toString();

                case "INTEGER":
                case "LONG":
                    if (value instanceof Number) {
                        return ((Number) value).longValue();
                    }
                    return Long.parseLong(value.toString());

                case "DECIMAL":
                case "DOUBLE":
                    if (value instanceof Number) {
                        return ((Number) value).doubleValue();
                    }
                    return Double.parseDouble(value.toString());

                case "BOOLEAN":
                    if (value instanceof Boolean) {
                        return value;
                    }
                    return Boolean.parseBoolean(value.toString());

                case "DATE":
                    String dateStr = value.toString();
                    String format = column.getFormat();
                    if (format != null && !format.isBlank()) {
                        return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(format));
                    }
                    return LocalDate.parse(dateStr);

                case "DATETIME":
                    String datetimeStr = value.toString();
                    String datetimeFormat = column.getFormat();
                    if (datetimeFormat != null && !datetimeFormat.isBlank()) {
                        return LocalDateTime.parse(datetimeStr,
                                DateTimeFormatter.ofPattern(datetimeFormat));
                    }
                    return LocalDateTime.parse(datetimeStr);

                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("Failed to convert value '{}' to type {} for column {}: {}",
                    value, type, column.getName(), e.getMessage());
            return value;  // Return as-is if conversion fails
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  URL building
    // ─────────────────────────────────────────────────────────────────────────

    private String buildUrl(String baseUrl, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl);

        for (Map.Entry<String, String> param : queryParams.entrySet()) {
            builder.queryParam(param.getKey(), param.getValue());
        }

        return builder.toUriString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Variable resolution (bind variables + env vars)
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveVariables(String input, String context) {
        if (input == null) {
            return null;
        }

        // Step 1: Resolve bind variables (:paramName)
        String resolved = resolveBindVariables(input, context);

        // Step 2: Resolve environment variables (${VAR})
        resolved = resolveEnvVariables(resolved, context);

        return resolved;
    }

    private String resolveBindVariables(String input, String context) {
        Matcher matcher = BIND_PARAM_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1).substring(1);  // Remove ":"

            if (!jobParameters.containsKey(paramName)) {
                throw new IllegalStateException(String.format(
                        "Bind variable not found in jobParameters [%s]: ':%s'%n" +
                                "Available parameters: %s",
                        context, paramName, jobParameters.keySet()
                ));
            }

            Object value = jobParameters.get(paramName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value.toString()));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveEnvVariables(String input, String context) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String envValue = System.getenv(varName);

            if (envValue == null) {
                throw new IllegalStateException(String.format(
                        "Environment variable not found [%s]: ${%s}%n" +
                                "Set it before running the job: export %s=<value>",
                        context, varName, varName
                ));
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, String> resolveQueryParams() {
        Map<String, String> resolved = new HashMap<>();

        for (Map.Entry<String, String> entry : restConfig.getQueryParams().entrySet()) {
            String key = entry.getKey();
            String value = resolveVariables(entry.getValue(), "rest.queryParams." + key);
            resolved.put(key, value);
        }

        return resolved;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();

        for (Map.Entry<String, String> entry : restConfig.getHeaders().entrySet()) {
            String key = entry.getKey();
            String value = resolveVariables(entry.getValue(), "rest.headers." + key);
            headers.set(key, value);
        }

        return headers;
    }
}