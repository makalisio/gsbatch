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

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestAuthType;
import org.makalisio.gsbatch.core.model.RestConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.util.VariableResolver;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Builder for {@link RestGenericItemReader}.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Configure {@link RestTemplate} with authentication interceptor</li>
 *   <li>Configure {@link RetryTemplate} with backoff policy and retryable HTTP codes</li>
 *   <li>Resolve environment variables in auth credentials (${VAR} syntax)</li>
 *   <li>Instantiate the reader with all dependencies</li>
 * </ul>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class RestGenericItemReaderBuilder {

    public RestGenericItemReaderBuilder() {
        log.info("RestGenericItemReaderBuilder initialized");
    }

    /**
     * Builds a REST ItemReader for the given source configuration.
     *
     * @param sourceConfig  source configuration from YAML
     * @param jobParameters job parameters for bind variable resolution
     * @return configured REST reader
     */
    public ItemStreamReader<GenericRecord> build(SourceConfig sourceConfig,
                                                 Map<String, Object> jobParameters) {
        if (!sourceConfig.hasRestConfig()) {
            throw new IllegalStateException(
                    "REST configuration missing for source: " + sourceConfig.getName());
        }

        RestConfig restConfig = sourceConfig.getRest();
        log.info("Building REST reader for source '{}' - URL: {}, pagination: {}",
                sourceConfig.getName(), restConfig.getUrl(),
                restConfig.getPagination().getStrategy());

        // Build RestTemplate with authentication
        RestTemplate restTemplate = buildRestTemplate(restConfig, sourceConfig.getName());

        // Build RetryTemplate for transient errors
        RetryTemplate retryTemplate = buildRetryTemplate(restConfig, sourceConfig.getName());

        return new RestGenericItemReader(
                sourceConfig,
                restConfig,
                jobParameters,
                restTemplate,
                retryTemplate
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RestTemplate configuration
    // ─────────────────────────────────────────────────────────────────────────

    RestTemplate buildRestTemplate(RestConfig restConfig, String sourceName) {
        RestTemplateBuilder builder = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(60));

        // Add authentication interceptor if configured
        RestAuthType authType = restConfig.getAuth().getAuthType();

        switch (authType) {
            case API_KEY -> {
                String apiKey = VariableResolver.resolveEnvVariables(restConfig.getAuth().getApiKey(), "rest.auth.apiKey");
                String headerName = restConfig.getAuth().getHeaderName();

                log.debug("Source '{}' - API_KEY auth configured (header: {})", sourceName, headerName);

                builder = builder.interceptors((ClientHttpRequestInterceptor) (request, body, execution) -> {
                    request.getHeaders().set(headerName, apiKey);
                    return execution.execute(request, body);
                });
            }
            case BEARER -> {
                String token = VariableResolver.resolveEnvVariables(restConfig.getAuth().getBearerToken(), "rest.auth.bearerToken");

                log.debug("Source '{}' - BEARER auth configured", sourceName);

                builder = builder.interceptors((ClientHttpRequestInterceptor) (request, body, execution) -> {
                    request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                });
            }
            case OAUTH2_CLIENT_CREDENTIALS -> throw new UnsupportedOperationException(
                    "OAUTH2_CLIENT_CREDENTIALS not yet implemented. Use API_KEY or BEARER for now.");
            case NONE -> { /* no authentication */ }
        }

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RetryTemplate configuration
    // ─────────────────────────────────────────────────────────────────────────

    RetryTemplate buildRetryTemplate(RestConfig restConfig, String sourceName) {
        RestConfig.RetryConfig retryConfig = restConfig.getRetry();

        if (retryConfig.getMaxRetries() == 0) {
            log.debug("Source '{}' - retry disabled", sourceName);
            // Return a no-op RetryTemplate
            RetryTemplate noRetry = new RetryTemplate();
            noRetry.setRetryPolicy(new SimpleRetryPolicy(1));  // 1 attempt = no retry
            return noRetry;
        }

        log.info("Source '{}' - retry configured: maxRetries={}, delay={}ms, codes={}",
                sourceName, retryConfig.getMaxRetries(), retryConfig.getRetryDelay(),
                retryConfig.getRetryOnHttpCodes());

        // Retry policy: retry on specific HTTP status codes
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(HttpStatusCodeException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                retryConfig.getMaxRetries() + 1,  // +1 because Spring counts the initial attempt
                retryableExceptions
        ) {
            @Override
            public boolean canRetry(org.springframework.retry.RetryContext context) {
                Throwable lastThrowable = context.getLastThrowable();

                // Spring Retry consults canRetry() BEFORE the first attempt, with no
                // throwable registered yet. Returning false here would prevent the
                // initial HTTP call from ever executing.
                if (lastThrowable == null) {
                    return super.canRetry(context);
                }

                if (lastThrowable instanceof HttpStatusCodeException) {
                    int statusCode = ((HttpStatusCodeException) lastThrowable).getStatusCode().value();
                    boolean shouldRetry = retryConfig.getRetryOnHttpCodes().contains(statusCode);

                    if (shouldRetry) {
                        log.warn("HTTP {} received - retry attempt {}/{}",
                                statusCode, context.getRetryCount(), retryConfig.getMaxRetries());
                    }

                    return shouldRetry && super.canRetry(context);
                }

                return false;  // Don't retry other exceptions
            }
        };

        // Backoff policy: fixed delay between retries
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(retryConfig.getRetryDelay());

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}