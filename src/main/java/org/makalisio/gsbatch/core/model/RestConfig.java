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
package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API configuration for the YAML source.
 *
 * <p>Supports paginated HTTP calls with authentication, retry logic,
 * and JSON extraction via JsonPath.</p>
 *
 * <h2>Example YAML</h2>
 * <pre>
 * rest:
 *   url: https://api.bank.com/v2/orders
 *   method: GET
 *   queryParams:
 *     status:     :status          # bind from jobParameters
 *     trade_date: :process_date
 *   headers:
 *     Accept: application/json
 *   auth:
 *     type: API_KEY
 *     apiKey: ${API_KEY_ORDERS}    # resolved from env var
 *     headerName: X-Api-Key
 *   dataPath: $.data.orders
 *   pagination:
 *     strategy: PAGE_SIZE
 *     pageParam: page
 *     sizeParam: size
 *     pageSize: 100
 *     totalPath: $.meta.total
 *   retry:
 *     maxRetries: 3
 *     retryDelay: 2000
 *     retryOnHttpCodes: [429, 503, 504]
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class RestConfig {

    // ── HTTP REQUEST ─────────────────────────────────────────────────────────

    /**
     * Base URL of the REST API endpoint.
     * Can contain bind variables like :paramName resolved from jobParameters.
     * Example: "https://api.bank.com/v2/orders"
     */
    private String url;

    /**
     * HTTP method. Default: GET.
     * Supported: GET, POST (for POST pagination or search APIs).
     */
    private String method = "GET";

    /**
     * Query parameters to append to the URL.
     * Values can be static strings or bind variables (:paramName).
     * Example: {status: ":status", trade_date: ":process_date"}
     */
    private Map<String, String> queryParams = new HashMap<>();

    /**
     * HTTP headers to include in every request.
     * Example: {Accept: "application/json", X-Client-Id: "backoffice"}
     */
    private Map<String, String> headers = new HashMap<>();

    /**
     * Optional request body for POST requests.
     * Can contain bind variables (:paramName).
     * JSON format expected.
     */
    private String body;

    // ── AUTHENTICATION ───────────────────────────────────────────────────────

    /**
     * Authentication configuration.
     */
    private AuthConfig auth = new AuthConfig();

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class AuthConfig {
        /**
         * Authentication type: NONE | API_KEY | BEARER | OAUTH2_CLIENT_CREDENTIALS.
         * Default: NONE.
         */
        private String type = "NONE";

        /**
         * API key value (used when type=API_KEY).
         * Supports ${VAR} syntax for environment variable resolution.
         * Example: "${API_KEY_ORDERS}"
         */
        private String apiKey;

        /**
         * HTTP header name for the API key (used when type=API_KEY).
         * Default: "X-Api-Key"
         */
        private String headerName = "X-Api-Key";

        /**
         * Bearer token value (used when type=BEARER).
         * Supports ${VAR} syntax.
         */
        private String bearerToken;

        /**
         * OAuth2 token endpoint URL (used when type=OAUTH2_CLIENT_CREDENTIALS).
         */
        private String tokenUrl;

        /**
         * OAuth2 client ID. Supports ${VAR} syntax.
         */
        private String clientId;

        /**
         * OAuth2 client secret. Supports ${VAR} syntax.
         */
        private String clientSecret;

        /**
         * OAuth2 scope (optional).
         */
        private String scope;
    }

    // ── JSON EXTRACTION ──────────────────────────────────────────────────────

    /**
     * JsonPath expression to extract the array of items from the JSON response.
     * Example: "$.data.orders" for {"data": {"orders": [...]}}
     * If the array is at the root, use "$" or "$.".
     */
    private String dataPath = "$";

    // ── PAGINATION ───────────────────────────────────────────────────────────

    /**
     * Pagination configuration.
     */
    private PaginationConfig pagination = new PaginationConfig();

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class PaginationConfig {
        /**
         * Pagination strategy.
         * Supported: NONE | PAGE_SIZE | OFFSET_LIMIT | CURSOR | LINK_HEADER.
         * Default: NONE (entire dataset returned in one call).
         */
        private String strategy = "NONE";

        /**
         * Query parameter name for the page number (PAGE_SIZE strategy).
         * Example: "page" for ?page=0&size=100
         */
        private String pageParam = "page";

        /**
         * Query parameter name for the page size (PAGE_SIZE or OFFSET_LIMIT).
         * Example: "size" for ?page=0&size=100
         */
        private String sizeParam = "size";

        /**
         * Number of items per page.
         * Default: 100.
         */
        private int pageSize = 100;

        /**
         * Query parameter name for the offset (OFFSET_LIMIT strategy).
         * Example: "offset" for ?offset=0&limit=100
         */
        private String offsetParam = "offset";

        /**
         * Query parameter name for the limit (OFFSET_LIMIT strategy).
         * Example: "limit" for ?offset=0&limit=100
         */
        private String limitParam = "limit";

        /**
         * JsonPath to extract the cursor value from the response (CURSOR strategy).
         * Example: "$.meta.nextCursor"
         */
        private String cursorPath;

        /**
         * Query parameter name for the cursor (CURSOR strategy).
         * Example: "cursor" for ?cursor=abc123
         */
        private String cursorParam = "cursor";

        /**
         * JsonPath to extract the total item count from the response (optional).
         * Used for logging progress.
         * Example: "$.meta.total"
         */
        private String totalPath;
    }

    // ── RETRY ────────────────────────────────────────────────────────────────

    /**
     * Retry configuration for transient HTTP errors.
     */
    private RetryConfig retry = new RetryConfig();

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class RetryConfig {
        /**
         * Maximum number of retry attempts.
         * Default: 3.
         */
        private int maxRetries = 3;

        /**
         * Delay between retries in milliseconds.
         * Default: 2000 (2 seconds).
         */
        private long retryDelay = 2000L;

        /**
         * HTTP status codes that trigger a retry.
         * Default: [429, 503, 504] (Too Many Requests, Service Unavailable, Gateway Timeout).
         */
        private List<Integer> retryOnHttpCodes = List.of(429, 503, 504);
    }

    // ── VALIDATION ───────────────────────────────────────────────────────────

    /**
     * Validates the REST configuration.
     *
     * @throws IllegalStateException if the configuration is invalid
     */
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("rest.url is required");
        }

        if (method == null || method.isBlank()) {
            throw new IllegalStateException("rest.method is required");
        }

        if (!List.of("GET", "POST").contains(method.toUpperCase())) {
            throw new IllegalStateException(
                "rest.method must be GET or POST, got: " + method);
        }

        if (dataPath == null || dataPath.isBlank()) {
            throw new IllegalStateException("rest.dataPath is required");
        }

        // Validate auth
        String authType = auth.getType().toUpperCase();
        if (!List.of("NONE", "API_KEY", "BEARER", "OAUTH2_CLIENT_CREDENTIALS").contains(authType)) {
            throw new IllegalStateException(
                "rest.auth.type must be NONE, API_KEY, BEARER, or OAUTH2_CLIENT_CREDENTIALS, got: " + authType);
        }

        if ("API_KEY".equals(authType) && (auth.getApiKey() == null || auth.getApiKey().isBlank())) {
            throw new IllegalStateException("rest.auth.apiKey is required when type=API_KEY");
        }

        if ("BEARER".equals(authType) && (auth.getBearerToken() == null || auth.getBearerToken().isBlank())) {
            throw new IllegalStateException("rest.auth.bearerToken is required when type=BEARER");
        }

        if ("OAUTH2_CLIENT_CREDENTIALS".equals(authType)) {
            if (auth.getTokenUrl() == null || auth.getTokenUrl().isBlank()) {
                throw new IllegalStateException("rest.auth.tokenUrl is required when type=OAUTH2_CLIENT_CREDENTIALS");
            }
            if (auth.getClientId() == null || auth.getClientId().isBlank()) {
                throw new IllegalStateException("rest.auth.clientId is required when type=OAUTH2_CLIENT_CREDENTIALS");
            }
            if (auth.getClientSecret() == null || auth.getClientSecret().isBlank()) {
                throw new IllegalStateException("rest.auth.clientSecret is required when type=OAUTH2_CLIENT_CREDENTIALS");
            }
        }

        // Validate pagination
        String paginationStrategy = pagination.getStrategy().toUpperCase();
        if (!List.of("NONE", "PAGE_SIZE", "OFFSET_LIMIT", "CURSOR", "LINK_HEADER").contains(paginationStrategy)) {
            throw new IllegalStateException(
                "rest.pagination.strategy must be NONE, PAGE_SIZE, OFFSET_LIMIT, CURSOR, or LINK_HEADER, got: " +
                paginationStrategy);
        }

        if ("PAGE_SIZE".equals(paginationStrategy)) {
            if (pagination.getPageParam() == null || pagination.getPageParam().isBlank()) {
                throw new IllegalStateException("rest.pagination.pageParam is required when strategy=PAGE_SIZE");
            }
            if (pagination.getSizeParam() == null || pagination.getSizeParam().isBlank()) {
                throw new IllegalStateException("rest.pagination.sizeParam is required when strategy=PAGE_SIZE");
            }
        }

        if ("OFFSET_LIMIT".equals(paginationStrategy)) {
            if (pagination.getOffsetParam() == null || pagination.getOffsetParam().isBlank()) {
                throw new IllegalStateException("rest.pagination.offsetParam is required when strategy=OFFSET_LIMIT");
            }
            if (pagination.getLimitParam() == null || pagination.getLimitParam().isBlank()) {
                throw new IllegalStateException("rest.pagination.limitParam is required when strategy=OFFSET_LIMIT");
            }
        }

        if ("CURSOR".equals(paginationStrategy)) {
            if (pagination.getCursorPath() == null || pagination.getCursorPath().isBlank()) {
                throw new IllegalStateException("rest.pagination.cursorPath is required when strategy=CURSOR");
            }
            if (pagination.getCursorParam() == null || pagination.getCursorParam().isBlank()) {
                throw new IllegalStateException("rest.pagination.cursorParam is required when strategy=CURSOR");
            }
        }

        if (pagination.getPageSize() <= 0) {
            throw new IllegalStateException("rest.pagination.pageSize must be positive");
        }

        // Validate retry
        if (retry.getMaxRetries() < 0) {
            throw new IllegalStateException("rest.retry.maxRetries must be >= 0");
        }

        if (retry.getRetryDelay() < 0) {
            throw new IllegalStateException("rest.retry.retryDelay must be >= 0");
        }
    }
}
