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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SoapAuthType;
import org.makalisio.gsbatch.core.model.SoapConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.util.VariableResolver;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

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

    private final MeterRegistry meterRegistry;

    public SoapGenericItemReaderBuilder() {
        this(new SimpleMeterRegistry());
    }

    /**
     * @param meterRegistryProvider resolves the consumer app's {@link MeterRegistry} bean
     *                              if one exists, otherwise falls back to a private
     *                              in-memory registry - metrics never prevent startup
     */
    @Autowired
    public SoapGenericItemReaderBuilder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    private SoapGenericItemReaderBuilder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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
            soapClient,
            meterRegistry
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

        return buildSoapClient(restTemplate, config, sourceName);
    }

    /**
     * Package-private overload accepting the {@link RestTemplate} directly, so
     * tests can bind it to {@code MockRestServiceServer} instead of exercising
     * real HTTP.
     */
    SoapGenericItemReader.SoapClient buildSoapClient(RestTemplate restTemplate, SoapConfig config, String sourceName) {
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
            // WS-Security travels inside the SOAP envelope itself, so the
            // UsernameToken must be injected before the request is sent
            if (config.getAuth().getAuthType() == SoapAuthType.WS_SECURITY) {
                soapRequest = injectWsSecurity(soapRequest);
            }

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
            SoapAuthType authType = config.getAuth().getAuthType();

            log.debug("Source '{}' - applying authentication: {}", sourceName, authType);

            switch (authType) {
                case NONE -> { /* no authentication */ }
                case BASIC -> applyBasicAuth(headers);
                case WS_SECURITY -> { /* already handled in call(): the UsernameToken is
                                         injected into the SOAP envelope itself, not into
                                         HTTP headers */ }
                case CUSTOM_HEADER -> applyCustomHeader(headers);
            }
        }

        private String injectWsSecurity(String soapRequest) {
            String username = VariableResolver.resolveEnvVariables(config.getAuth().getUsername(),
                                                "soap.auth.username");
            String password = VariableResolver.resolveEnvVariables(config.getAuth().getPassword(),
                                                "soap.auth.password");

            log.debug("Source '{}' - injecting WS-Security UsernameToken ({})",
                      sourceName, config.getAuth().getPasswordType());

            return WsSecurityHeaderInjector.inject(soapRequest, username, password,
                                                   config.getAuth().getPasswordType());
        }

        private void applyBasicAuth(HttpHeaders headers) {
            String username = VariableResolver.resolveEnvVariables(config.getAuth().getUsername(), "soap.auth.username");
            String password = VariableResolver.resolveEnvVariables(config.getAuth().getPassword(), "soap.auth.password");

            String auth = username + ":" + password;
            byte[] encodedAuth = java.util.Base64.getEncoder().encode(auth.getBytes());
            String authHeader = "Basic " + new String(encodedAuth);

            headers.set("Authorization", authHeader);
            log.debug("Source '{}' - Basic auth header added", sourceName);
        }

        private void applyCustomHeader(HttpHeaders headers) {
            String headerName = config.getAuth().getHeaderName();
            String headerValue = VariableResolver.resolveEnvVariables(config.getAuth().getHeaderValue(),
                                                "soap.auth.headerValue");

            headers.set(headerName, headerValue);
            log.debug("Source '{}' - custom header '{}' added", sourceName, headerName);
        }
    }
}
