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

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class WsSecurityHeaderInjectorTest {

    private static final String ENVELOPE_NO_HEADER =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soapenv:Body><GetTrades/></soapenv:Body></soapenv:Envelope>";

    // ── Insertion du header ──────────────────────────────────────────────────

    @Test
    void inject_envelopeWithoutHeader_createsHeaderBeforeBody() throws Exception {
        String result = WsSecurityHeaderInjector.inject(
                ENVELOPE_NO_HEADER, "john", "secret", "PasswordText");

        assertThat(result)
                .contains("<soapenv:Header><wsse:Security")
                .contains("soapenv:mustUnderstand=\"1\"")
                .contains("<wsse:Username>john</wsse:Username>")
                .contains("#PasswordText\">secret</wsse:Password>");
        assertThat(result.indexOf("<soapenv:Header>"))
                .isLessThan(result.indexOf("<soapenv:Body>"));
        assertThat(parseXml(result)).isNotNull(); // still well-formed XML
    }

    @Test
    void inject_envelopeWithExistingHeader_insertsIntoIt() throws Exception {
        String envelope =
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soapenv:Header><Custom>x</Custom></soapenv:Header>"
                        + "<soapenv:Body><GetTrades/></soapenv:Body></soapenv:Envelope>";

        String result = WsSecurityHeaderInjector.inject(envelope, "john", "secret", "PasswordText");

        assertThat(result)
                .contains("<soapenv:Header><wsse:Security")
                .contains("<Custom>x</Custom>");
        // No second header created
        assertThat(result.split("<soapenv:Header").length - 1).isEqualTo(1);
        assertThat(parseXml(result)).isNotNull();
    }

    @Test
    void inject_selfClosingHeader_expandsIt() throws Exception {
        String envelope =
                "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                        + "<soapenv:Header/>"
                        + "<soapenv:Body><GetTrades/></soapenv:Body></soapenv:Envelope>";

        String result = WsSecurityHeaderInjector.inject(envelope, "john", "secret", "PasswordText");

        assertThat(result)
                .doesNotContain("<soapenv:Header/>")
                .contains("<soapenv:Header><wsse:Security");
        assertThat(parseXml(result)).isNotNull();
    }

    @Test
    void inject_noEnvelope_throws() {
        assertThatThrownBy(() ->
                WsSecurityHeaderInjector.inject("<foo/>", "john", "secret", "PasswordText"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no SOAP Envelope");
    }

    // ── Échappement des identifiants ─────────────────────────────────────────

    @Test
    void inject_credentialsWithXmlSpecials_areEscaped() throws Exception {
        String result = WsSecurityHeaderInjector.inject(
                ENVELOPE_NO_HEADER, "a<b&c", "p\"q'r>", "PasswordText");

        assertThat(result)
                .contains("<wsse:Username>a&lt;b&amp;c</wsse:Username>")
                .contains("p&quot;q&apos;r&gt;</wsse:Password>");
        assertThat(parseXml(result)).isNotNull();
    }

    // ── PasswordDigest ───────────────────────────────────────────────────────

    @Test
    void inject_passwordDigest_computesUsernameTokenProfileDigest() throws Exception {
        byte[] nonce = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String created = "2026-07-05T12:00:00Z";

        String result = WsSecurityHeaderInjector.inject(
                ENVELOPE_NO_HEADER, "john", "secret", "PasswordDigest", nonce, created);

        // Digest = Base64(SHA-1(nonce + created + password)) per UsernameToken Profile 1.0
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonce);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update("secret".getBytes(StandardCharsets.UTF_8));
        String expectedDigest = Base64.getEncoder().encodeToString(sha1.digest());

        assertThat(result)
                .contains("#PasswordDigest\">" + expectedDigest + "</wsse:Password>")
                .contains("<wsse:Nonce")
                .contains(">" + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce>")
                .contains("<wsu:Created>" + created + "</wsu:Created>")
                .doesNotContain(">secret<"); // plain password never sent
        assertThat(parseXml(result)).isNotNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
