package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SoapConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builder for {@link SoapGenericItemReader}.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Configure HTTP client for SOAP calls with timeouts</li>
 *   <li>Implement authentication (NONE, BASIC, WS_SECURITY Level 1, CUSTOM_HEADER)</li>
 *   <li>Resolve environment variables in credentials (${VAR} syntax)</li>
 *   <li>Instantiate the reader with all dependencies</li>
 * </ul>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class SoapGenericItemReaderBuilder {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    public SoapGenericItemReaderBuilder() {
        log.info("SoapGenericItemReaderBuilder initialized");
    }

    /**
     * Builds a SOAP ItemReader for the given source configuration.
     *
     * @param sourceConfig  source configuration from YAML
     * @param jobParameters job parameters for bind variable resolution
     * @return configured SOAP reader
     */
    public ItemStreamReader<GenericRecord> build(SourceConfig sourceConfig, 
                                                   Map<String, Object> jobParameters) {
        if (!sourceConfig.hasSoapConfig()) {
            throw new IllegalStateException(
                "SOAP configuration missing for source: " + sourceConfig.getName());
        }

        SoapConfig soapConfig = sourceConfig.getSoap();
        log.info("Building SOAP reader for source '{}' - endpoint: {}, SOAPAction: {}", 
                 sourceConfig.getName(), soapConfig.getEndpoint(), soapConfig.getSoapAction());

        // Build SOAP client with authentication
        SoapGenericItemReader.SoapClient soapClient = buildSoapClient(soapConfig, sourceConfig.getName());

        return new SoapGenericItemReader(
            sourceConfig,
            soapConfig,
            jobParameters,
            soapClient
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SOAP client implementation
    // ─────────────────────────────────────────────────────────────────────────

    private SoapGenericItemReader.SoapClient buildSoapClient(SoapConfig config, String sourceName) {
        // Configure HTTP client with timeouts
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.getConnectionTimeout());
        requestFactory.setReadTimeout(config.getReadTimeout());

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        log.debug("Source '{}' - SOAP client configured (connectTimeout={}ms, readTimeout={}ms)", 
                  sourceName, config.getConnectionTimeout(), config.getReadTimeout());

        // Return SOAP client implementation
        return new SoapClientImpl(restTemplate, config, sourceName);
    }

    /**
     * SOAP client implementation using RestTemplate.
     */
    private class SoapClientImpl implements SoapGenericItemReader.SoapClient {

        private final RestTemplate restTemplate;
        private final SoapConfig config;
        private final String sourceName;

        public SoapClientImpl(RestTemplate restTemplate, SoapConfig config, String sourceName) {
            this.restTemplate = restTemplate;
            this.config = config;
            this.sourceName = sourceName;
        }

        @Override
        public String call(String soapRequest) throws Exception {
            // Build HTTP headers
            HttpHeaders headers = buildHeaders(soapRequest);

            // Add authentication if configured
            applyAuthentication(headers, soapRequest);

            // Create HTTP entity
            HttpEntity<String> entity = new HttpEntity<>(soapRequest, headers);

            log.debug("Source '{}' - executing SOAP call to: {}", sourceName, config.getEndpoint());

            try {
                // Execute HTTP POST
                ResponseEntity<String> response = restTemplate.exchange(
                    config.getEndpoint(),
                    HttpMethod.POST,
                    entity,
                    String.class
                );

                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException(
                        "SOAP call failed with HTTP status: " + response.getStatusCode());
                }

                String responseBody = response.getBody();
                if (responseBody == null || responseBody.isBlank()) {
                    throw new IllegalStateException("Empty SOAP response received");
                }

                log.debug("Source '{}' - SOAP call successful, response size: {} bytes", 
                          sourceName, responseBody.length());

                return responseBody;

            } catch (HttpStatusCodeException e) {
                log.error("Source '{}' - SOAP call failed with HTTP {}: {}", 
                          sourceName, e.getStatusCode(), e.getResponseBodyAsString());
                throw new IllegalStateException(
                    "SOAP call failed with HTTP " + e.getStatusCode() + ": " + 
                    e.getResponseBodyAsString(), e);
            }
        }

        private HttpHeaders buildHeaders(String soapRequest) {
            HttpHeaders headers = new HttpHeaders();

            // Content-Type depends on SOAP version
            if ("1.1".equals(config.getSoapVersion())) {
                headers.setContentType(MediaType.TEXT_XML);
            } else {
                // SOAP 1.2
                headers.setContentType(MediaType.valueOf("application/soap+xml; charset=utf-8"));
            }

            // SOAPAction header (required for SOAP 1.1, optional for 1.2)
            if (config.getSoapAction() != null && !config.getSoapAction().isBlank()) {
                headers.set("SOAPAction", config.getSoapAction());
            }

            return headers;
        }

        private void applyAuthentication(HttpHeaders headers, String soapRequest) {
            String authType = config.getAuth().getType().toUpperCase();

            log.debug("Source '{}' - applying authentication: {}", sourceName, authType);

            switch (authType) {
                case "NONE":
                    // No authentication
                    break;

                case "BASIC":
                    applyBasicAuth(headers);
                    break;

                case "WS_SECURITY":
                    // WS-Security is embedded in SOAP envelope - already done in request template
                    // But if username/password are in config, inject them
                    // This is handled by modifying the SOAP request before sending
                    // For now, we assume the template already has WS-Security header
                    log.debug("Source '{}' - WS-Security: UsernameToken should be in request template", 
                              sourceName);
                    break;

                case "CUSTOM_HEADER":
                    applyCustomHeader(headers);
                    break;

                default:
                    throw new IllegalStateException("Unknown auth type: " + authType);
            }
        }

        private void applyBasicAuth(HttpHeaders headers) {
            String username = resolveEnvVars(config.getAuth().getUsername(), "soap.auth.username");
            String password = resolveEnvVars(config.getAuth().getPassword(), "soap.auth.password");

            String auth = username + ":" + password;
            byte[] encodedAuth = java.util.Base64.getEncoder().encode(auth.getBytes());
            String authHeader = "Basic " + new String(encodedAuth);

            headers.set("Authorization", authHeader);
            log.debug("Source '{}' - Basic auth header added", sourceName);
        }

        private void applyCustomHeader(HttpHeaders headers) {
            String headerName = config.getAuth().getHeaderName();
            String headerValue = resolveEnvVars(config.getAuth().getHeaderValue(), 
                                                "soap.auth.headerValue");

            headers.set(headerName, headerValue);
            log.debug("Source '{}' - custom header '{}' added", sourceName, headerName);
        }

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
}
