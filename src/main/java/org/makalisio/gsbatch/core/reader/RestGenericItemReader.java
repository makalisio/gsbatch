/*
 * Copyright 2026 Makalisio Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.makalisio.gsbatch.core.reader.pagination.PaginationHandler;
import org.makalisio.gsbatch.core.util.VariableResolver;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.http.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SourceConfig sourceConfig;
    private final RestConfig restConfig;
    private final Map<String, Object> jobParameters;
    private final RestTemplate restTemplate;
    private final RetryTemplate retryTemplate;
    private final Configuration jsonPathConfig;

    // Pagination state - delegated to a strategy-specific handler, built in open()
    private PaginationHandler paginationHandler;
    private Integer totalItems = null;
    private int itemsRead = 0;

    // Buffer for items from current page
    private Queue<GenericRecord> buffer = new LinkedList<>();

    // Resolved values (computed once in open())
    private String resolvedUrl;
    private Map<String, String> resolvedQueryParams;
    private HttpHeaders resolvedHeaders;
    private String resolvedBody;

    // Cache of compiled DateTimeFormatters, keyed by column format pattern
    private final Map<String, DateTimeFormatter> dateFormatters = new HashMap<>();

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

        // Resolve URL, query params, headers, and body once
        resolvedUrl = resolveVariables(restConfig.getUrl(), "rest.url");
        resolvedQueryParams = resolveQueryParams();
        resolvedHeaders = buildHeaders();
        resolvedBody = resolveVariables(restConfig.getBody(), "rest.body");

        // Fresh pagination state for this execution
        paginationHandler = PaginationHandler.forStrategy(restConfig, jsonPathConfig);
        itemsRead = 0;
        buffer.clear();

        log.info("REST reader opened - URL: {}, pagination: {}",
                resolvedUrl, restConfig.getPagination().getStrategy());
    }

    @Override
    public GenericRecord read() throws Exception {
        // Keep fetching pages while the buffer is empty and more pages may exist.
        // A page can legitimately come back empty (e.g. a filtered CURSOR page)
        // while still carrying a valid next cursor, so a single fetch attempt
        // is not enough to decide there is no more data.
        while (buffer.isEmpty() && !paginationHandler.isDone()) {
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
        Map<String, String> pageParams = new HashMap<>(resolvedQueryParams);
        pageParams.putAll(paginationHandler.nextPageParams());

        fetchPage(buildUrl(resolvedUrl, pageParams));
    }

    private void fetchPage(String url) {
        log.debug("Fetching page: {}", url);

        // Execute HTTP request with retry
        String jsonResponse = retryTemplate.execute(context -> {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(restConfig.getMethod()),
                    buildRequestEntity(), String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "HTTP request failed with status: " + response.getStatusCode());
            }

            return response.getBody();
        });

        if (jsonResponse == null || jsonResponse.isBlank()) {
            log.warn("Empty response from API");
            paginationHandler.onPageFetched(Collections.emptyList(), jsonResponse);
            return;
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
        paginationHandler.onPageFetched(records, jsonResponse);
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
            // A parsing failure here means the response body itself is broken
            // (invalid JSON, unexpected schema) - not that pagination is exhausted.
            // Returning an empty list would be indistinguishable from a legitimate
            // last page and would silently truncate the ingestion.
            throw new IllegalStateException(String.format(
                    "Failed to extract items from JSON response using path '%s' for source '%s': %s",
                    restConfig.getDataPath(), sourceConfig.getName(), e.getMessage()), e);
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
                        return LocalDate.parse(dateStr, formatterFor(format));
                    }
                    return LocalDate.parse(dateStr);

                case "DATETIME":
                    String datetimeStr = value.toString();
                    String datetimeFormat = column.getFormat();
                    if (datetimeFormat != null && !datetimeFormat.isBlank()) {
                        return LocalDateTime.parse(datetimeStr, formatterFor(datetimeFormat));
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

    private DateTimeFormatter formatterFor(String pattern) {
        return dateFormatters.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
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
        return VariableResolver.resolve(input, jobParameters, context);
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

    private HttpEntity<String> buildRequestEntity() {
        if (resolvedBody == null) {
            return new HttpEntity<>(resolvedHeaders);
        }

        if (!resolvedHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
            resolvedHeaders.setContentType(MediaType.APPLICATION_JSON);
        }

        return new HttpEntity<>(resolvedBody, resolvedHeaders);
    }
}