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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects a WS-Security {@code UsernameToken} header into a SOAP request envelope.
 *
 * <p>Supports both password types of the WS-Security UsernameToken Profile 1.0:</p>
 * <ul>
 *   <li>{@code PasswordText}   - password sent as-is (use over HTTPS only)</li>
 *   <li>{@code PasswordDigest} - {@code Base64(SHA-1(nonce + created + password))}
 *                                with the nonce and creation timestamp included in the token</li>
 * </ul>
 *
 * <p>The {@code <wsse:Security>} block is inserted into the envelope's existing
 * {@code Header} element, or a new {@code Header} is created right after the
 * {@code Envelope} opening tag if the template has none.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
final class WsSecurityHeaderInjector {

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    private static final String WSU_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String PASSWORD_TEXT_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";
    private static final String PASSWORD_DIGEST_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";
    private static final String BASE64_ENCODING_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

    /**
     * Matches the SOAP Envelope opening tag and captures its namespace prefix
     * (e.g. "soapenv" in {@code <soapenv:Envelope ...>}).
     */
    private static final Pattern ENVELOPE_PATTERN =
            Pattern.compile("<(?:([A-Za-z0-9_.-]+):)?Envelope[\\s>]");

    private static final SecureRandom RANDOM = new SecureRandom();

    private WsSecurityHeaderInjector() {
    }

    /**
     * Injects a WS-Security UsernameToken into the given SOAP request.
     *
     * @param soapRequest  full SOAP envelope XML
     * @param username     resolved username (plain text)
     * @param password     resolved password (plain text)
     * @param passwordType {@code PasswordText} or {@code PasswordDigest}
     * @return the SOAP request with the {@code <wsse:Security>} header injected
     * @throws IllegalStateException if the request contains no SOAP Envelope element
     */
    static String inject(String soapRequest, String username, String password, String passwordType) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return inject(soapRequest, username, password, passwordType, nonce, created);
    }

    /**
     * Variant with explicit nonce and creation timestamp (deterministic, for tests).
     */
    static String inject(String soapRequest, String username, String password,
                         String passwordType, byte[] nonce, String created) {
        Matcher envelope = ENVELOPE_PATTERN.matcher(soapRequest);
        if (!envelope.find()) {
            throw new IllegalStateException(
                    "Cannot inject WS-Security header: no SOAP Envelope element found in request");
        }
        String prefix = envelope.group(1);
        int envelopeTagEnd = soapRequest.indexOf('>', envelope.start());
        if (envelopeTagEnd < 0) {
            throw new IllegalStateException(
                    "Cannot inject WS-Security header: unterminated SOAP Envelope opening tag");
        }

        String security = buildSecurityBlock(prefix, username, password, passwordType, nonce, created);
        String headerName = prefix != null ? prefix + ":Header" : "Header";

        Pattern headerPattern = Pattern.compile("<" + Pattern.quote(headerName) + "(\\s[^>]*?)?(/)?>");
        Matcher header = headerPattern.matcher(soapRequest);
        if (header.find(envelopeTagEnd)) {
            if (header.group(2) != null) {
                // Self-closing <soapenv:Header/> - expand it around the security block
                String attributes = header.group(1) != null ? header.group(1) : "";
                return soapRequest.substring(0, header.start())
                        + "<" + headerName + attributes + ">" + security + "</" + headerName + ">"
                        + soapRequest.substring(header.end());
            }
            // Existing header - insert the security block right after its opening tag
            return soapRequest.substring(0, header.end()) + security + soapRequest.substring(header.end());
        }

        // No header - create one right after the Envelope opening tag
        return soapRequest.substring(0, envelopeTagEnd + 1)
                + "<" + headerName + ">" + security + "</" + headerName + ">"
                + soapRequest.substring(envelopeTagEnd + 1);
    }

    private static String buildSecurityBlock(String envelopePrefix, String username, String password,
                                             String passwordType, byte[] nonce, String created) {
        // mustUnderstand must be qualified with the envelope namespace, so it is
        // only emitted when the template declares a prefix for it
        String mustUnderstand = envelopePrefix != null
                ? " " + envelopePrefix + ":mustUnderstand=\"1\""
                : "";

        StringBuilder sb = new StringBuilder();
        sb.append("<wsse:Security xmlns:wsse=\"").append(WSSE_NS)
                .append("\" xmlns:wsu=\"").append(WSU_NS).append("\"")
                .append(mustUnderstand).append(">")
                .append("<wsse:UsernameToken>")
                .append("<wsse:Username>").append(escapeXml(username)).append("</wsse:Username>");

        if ("PasswordDigest".equals(passwordType)) {
            sb.append("<wsse:Password Type=\"").append(PASSWORD_DIGEST_TYPE).append("\">")
                    .append(computeDigest(nonce, created, password)).append("</wsse:Password>")
                    .append("<wsse:Nonce EncodingType=\"").append(BASE64_ENCODING_TYPE).append("\">")
                    .append(Base64.getEncoder().encodeToString(nonce)).append("</wsse:Nonce>")
                    .append("<wsu:Created>").append(created).append("</wsu:Created>");
        } else {
            sb.append("<wsse:Password Type=\"").append(PASSWORD_TEXT_TYPE).append("\">")
                    .append(escapeXml(password)).append("</wsse:Password>");
        }

        sb.append("</wsse:UsernameToken></wsse:Security>");
        return sb.toString();
    }

    /**
     * Computes the UsernameToken Profile 1.0 password digest:
     * {@code Base64(SHA-1(nonce + created + password))}.
     */
    static String computeDigest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available for WS-Security digest", e);
        }
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
