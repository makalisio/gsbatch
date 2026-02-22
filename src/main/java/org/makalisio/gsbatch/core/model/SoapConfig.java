package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

/**
 * SOAP WebService configuration for the YAML source.
 *
 * <p>Supports SOAP 1.1/1.2 calls with WS-Security UsernameToken authentication 
 * and XPath extraction. No pagination support (all data returned in single call).</p>
 *
 * <h2>Example YAML</h2>
 * <pre>
 * soap:
 *   endpoint: https://api.bank.com/TradeService
 *   soapAction: "http://bank.com/GetTrades"
 *   soapVersion: "1.1"
 *   requestTemplate: |
 *     &lt;soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
 *                       xmlns:tns="http://bank.com/TradeService"&gt;
 *       &lt;soapenv:Body&gt;
 *         &lt;tns:GetTrades&gt;
 *           &lt;tns:tradeDate&gt;:tradeDate&lt;/tns:tradeDate&gt;
 *           &lt;tns:status&gt;:status&lt;/tns:status&gt;
 *         &lt;/tns:GetTrades&gt;
 *       &lt;/soapenv:Body&gt;
 *     &lt;/soapenv:Envelope&gt;
 *   requestParams:
 *     tradeDate: :process_date
 *     status: :status
 *   auth:
 *     type: WS_SECURITY
 *     username: ${SOAP_USERNAME}
 *     password: ${SOAP_PASSWORD}
 *     passwordType: PasswordText
 *   dataPath: //GetTradesResponse/trades/trade
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class SoapConfig {

    // ── SOAP ENDPOINT ────────────────────────────────────────────────────────

    /**
     * SOAP endpoint URL.
     * Example: "https://api.bank.com/TradeService"
     */
    private String endpoint;

    /**
     * SOAPAction HTTP header value (required for SOAP 1.1, optional for 1.2).
     * Example: "http://bank.com/GetTrades"
     */
    private String soapAction;

    /**
     * SOAP version: "1.1" or "1.2".
     * Default: "1.1"
     */
    private String soapVersion = "1.1";

    /**
     * WSDL URL (optional, for documentation purposes only).
     * Not used during execution.
     */
    private String wsdl;

    // ── REQUEST TEMPLATE ─────────────────────────────────────────────────────

    /**
     * SOAP request template as inline XML string (Q1: B).
     * 
     * <p>Supports bind variables in the format :paramName which are resolved from jobParameters.</p>
     * 
     * <p>Namespaces must be hardcoded in the template (Q2: A).</p>
     * 
     * <p>Example:</p>
     * <pre>
     * &lt;soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
     *                   xmlns:tns="http://bank.com/TradeService"&gt;
     *   &lt;soapenv:Body&gt;
     *     &lt;tns:GetTrades&gt;
     *       &lt;tns:tradeDate&gt;:tradeDate&lt;/tns:tradeDate&gt;
     *       &lt;tns:status&gt;:status&lt;/tns:status&gt;
     *     &lt;/tns:GetTrades&gt;
     *   &lt;/soapenv:Body&gt;
     * &lt;/soapenv:Envelope&gt;
     * </pre>
     */
    private String requestTemplate;

    /**
     * Request parameters to bind in the template.
     * 
     * <p>Keys are parameter names as they appear in the template (:paramName).</p>
     * <p>Values can be static strings or bind variables from jobParameters.</p>
     * 
     * <p>Example: {tradeDate: ":process_date", status: ":status"}</p>
     */
    private Map<String, String> requestParams = new HashMap<>();

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
         * Authentication type: NONE | BASIC | WS_SECURITY | CUSTOM_HEADER.
         * Default: NONE.
         */
        private String type = "NONE";

        /**
         * Username for authentication.
         * Supports ${VAR} syntax for environment variable resolution.
         */
        private String username;

        /**
         * Password for authentication.
         * Supports ${VAR} syntax for environment variable resolution.
         */
        private String password;

        /**
         * WS-Security password type: PasswordText | PasswordDigest.
         * Default: PasswordText (Q5: Level 1 - simple UsernameToken).
         * Only used when type=WS_SECURITY.
         */
        private String passwordType = "PasswordText";

        /**
         * Custom header name (used when type=CUSTOM_HEADER).
         */
        private String headerName;

        /**
         * Custom header value (used when type=CUSTOM_HEADER).
         * Supports ${VAR} syntax.
         */
        private String headerValue;
    }

    // ── XML EXTRACTION ───────────────────────────────────────────────────────

    /**
     * XPath expression to extract the array of items from the SOAP response.
     *
     * <p>Example: "//GetTradesResponse/trades/trade" extracts all &lt;trade&gt; nodes.</p>
     *
     * <p>The XPath is evaluated against the entire SOAP response (including envelope).
     * Each matching node becomes one item.</p>
     */
    private String dataPath;

    /**
     * Whether the XML parser should be namespace-aware.
     *
     * <p>Set to {@code false} when the SOAP service returns elements with a default namespace
     * (e.g., {@code <AddResponse xmlns="http://tempuri.org/">}) and the XPath expressions
     * should match element local-names directly without namespace qualification.</p>
     *
     * <p>Example: with {@code namespaceAware: false}, {@code dataPath: //AddResponse}
     * matches {@code <AddResponse xmlns="http://tempuri.org/">} without any XPath workaround.</p>
     *
     * <p>Default: {@code true} (standard namespace-aware behaviour).</p>
     */
    private boolean namespaceAware = true;

    // ── HTTP SETTINGS ────────────────────────────────────────────────────────

    /**
     * HTTP connection timeout in milliseconds.
     * Default: 30000 (30 seconds).
     */
    private int connectionTimeout = 30000;

    /**
     * HTTP read timeout in milliseconds.
     * Default: 60000 (60 seconds).
     */
    private int readTimeout = 60000;

    // ── VALIDATION ───────────────────────────────────────────────────────────

    /**
     * Validates the SOAP configuration.
     *
     * @throws IllegalStateException if the configuration is invalid
     */
    public void validate() {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("soap.endpoint is required");
        }

        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalStateException(
                "soap.endpoint must start with http:// or https://, got: " + endpoint);
        }

        if (soapVersion == null || soapVersion.isBlank()) {
            throw new IllegalStateException("soap.soapVersion is required");
        }

        if (!"1.1".equals(soapVersion) && !"1.2".equals(soapVersion)) {
            throw new IllegalStateException(
                "soap.soapVersion must be '1.1' or '1.2', got: " + soapVersion);
        }

        if ("1.1".equals(soapVersion) && (soapAction == null || soapAction.isBlank())) {
            throw new IllegalStateException(
                "soap.soapAction is required for SOAP 1.1");
        }

        if (requestTemplate == null || requestTemplate.isBlank()) {
            throw new IllegalStateException("soap.requestTemplate is required");
        }

        if (!requestTemplate.contains("<soapenv:Envelope") && 
            !requestTemplate.contains("<soap:Envelope") &&
            !requestTemplate.contains("<SOAP-ENV:Envelope")) {
            throw new IllegalStateException(
                "soap.requestTemplate must be a valid SOAP envelope (missing <soapenv:Envelope>, <soap:Envelope>, or <SOAP-ENV:Envelope>)");
        }

        if (dataPath == null || dataPath.isBlank()) {
            throw new IllegalStateException("soap.dataPath is required");
        }

        // Validate auth
        String authType = auth.getType().toUpperCase();
        if (!java.util.List.of("NONE", "BASIC", "WS_SECURITY", "CUSTOM_HEADER").contains(authType)) {
            throw new IllegalStateException(
                "soap.auth.type must be NONE, BASIC, WS_SECURITY, or CUSTOM_HEADER, got: " + authType);
        }

        if ("BASIC".equals(authType) || "WS_SECURITY".equals(authType)) {
            if (auth.getUsername() == null || auth.getUsername().isBlank()) {
                throw new IllegalStateException(
                    "soap.auth.username is required when type=" + authType);
            }
            if (auth.getPassword() == null || auth.getPassword().isBlank()) {
                throw new IllegalStateException(
                    "soap.auth.password is required when type=" + authType);
            }
        }

        if ("WS_SECURITY".equals(authType)) {
            String passwordType = auth.getPasswordType();
            if (!"PasswordText".equals(passwordType) && !"PasswordDigest".equals(passwordType)) {
                throw new IllegalStateException(
                    "soap.auth.passwordType must be 'PasswordText' or 'PasswordDigest', got: " + passwordType);
            }
        }

        if ("CUSTOM_HEADER".equals(authType)) {
            if (auth.getHeaderName() == null || auth.getHeaderName().isBlank()) {
                throw new IllegalStateException(
                    "soap.auth.headerName is required when type=CUSTOM_HEADER");
            }
            if (auth.getHeaderValue() == null || auth.getHeaderValue().isBlank()) {
                throw new IllegalStateException(
                    "soap.auth.headerValue is required when type=CUSTOM_HEADER");
            }
        }

        if (connectionTimeout <= 0) {
            throw new IllegalStateException("soap.connectionTimeout must be positive");
        }

        if (readTimeout <= 0) {
            throw new IllegalStateException("soap.readTimeout must be positive");
        }
    }
}
