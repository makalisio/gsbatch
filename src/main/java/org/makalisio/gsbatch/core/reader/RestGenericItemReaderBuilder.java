package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

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

    private RestTemplate buildRestTemplate(RestConfig restConfig, String sourceName) {
        RestTemplateBuilder builder = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(60));

        // Add authentication interceptor if configured
        String authType = restConfig.getAuth().getType().toUpperCase();

        if ("API_KEY".equals(authType)) {
            String apiKey = resolveEnvVars(restConfig.getAuth().getApiKey(), "rest.auth.apiKey");
            String headerName = restConfig.getAuth().getHeaderName();

            log.debug("Source '{}' - API_KEY auth configured (header: {})", sourceName, headerName);

            builder = builder.interceptors((ClientHttpRequestInterceptor) (request, body, execution) -> {
                request.getHeaders().set(headerName, apiKey);
                return execution.execute(request, body);
            });
        }
        else if ("BEARER".equals(authType)) {
            String token = resolveEnvVars(restConfig.getAuth().getBearerToken(), "rest.auth.bearerToken");

            log.debug("Source '{}' - BEARER auth configured", sourceName);

            builder = builder.interceptors((ClientHttpRequestInterceptor) (request, body, execution) -> {
                request.getHeaders().setBearerAuth(token);
                return execution.execute(request, body);
            });
        }
        else if ("OAUTH2_CLIENT_CREDENTIALS".equals(authType)) {
            throw new UnsupportedOperationException(
                    "OAUTH2_CLIENT_CREDENTIALS not yet implemented. Use API_KEY or BEARER for now.");
        }
        else if (!"NONE".equals(authType)) {
            throw new IllegalStateException(
                    "Unknown auth type for source '" + sourceName + "': " + authType);
        }

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RetryTemplate configuration
    // ─────────────────────────────────────────────────────────────────────────

    private RetryTemplate buildRetryTemplate(RestConfig restConfig, String sourceName) {
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Environment variable resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves environment variables in the format ${VAR_NAME}.
     *
     * @param input   string that may contain ${VAR} placeholders
     * @param context context for error messages (e.g., "rest.auth.apiKey")
     * @return resolved string with env vars substituted
     * @throws IllegalStateException if an environment variable is not found
     */
    private String resolveEnvVars(String input, String context) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String envValue = System.getenv(varName);

            if (envValue == null) {
                throw new IllegalStateException(String.format(
                        "Environment variable not found [%s]: ${%s}%n" +
                                "Set it before running the job:%n" +
                                "  export %s=<value>  # Linux/Mac%n" +
                                "  set %s=<value>     # Windows CMD%n" +
                                "  $env:%s='<value>'  # Windows PowerShell",
                        context, varName, varName, varName, varName
                ));
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }
}