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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.SoapConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SoapGenericItemReaderBuilderTest {

    private SoapGenericItemReaderBuilder builder;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = new SoapGenericItemReaderBuilder();
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private SoapConfig config() {
        SoapConfig config = new SoapConfig();
        config.setEndpoint("https://api.example.com/TradeService");
        config.setSoapAction("GetTrades");
        return config;
    }

    // ── NONE ──────────────────────────────────────────────────────────────────

    @Test
    void call_noneAuth_sendsNoAuthHeader() throws Exception {
        SoapConfig config = config();
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>");

        server.verify();
    }

    // ── BASIC ─────────────────────────────────────────────────────────────────

    @Test
    void call_basicAuth_addsBase64EncodedAuthorizationHeader() throws Exception {
        SoapConfig config = config();
        config.getAuth().setType("BASIC");
        config.getAuth().setUsername("john");
        config.getAuth().setPassword("secret");
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        String expected = "Basic " + Base64.getEncoder().encodeToString("john:secret".getBytes());

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>");

        server.verify();
    }

    // ── CUSTOM_HEADER ─────────────────────────────────────────────────────────

    @Test
    void call_customHeaderAuth_addsConfiguredHeader() throws Exception {
        SoapConfig config = config();
        config.getAuth().setType("CUSTOM_HEADER");
        config.getAuth().setHeaderName("X-Api-Token");
        config.getAuth().setHeaderValue("token-xyz");
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(header("X-Api-Token", "token-xyz"))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>");

        server.verify();
    }

    // ── WS_SECURITY ───────────────────────────────────────────────────────────

    @Test
    void call_wsSecurityAuth_injectsUsernameTokenIntoEnvelopeNotHeaders() throws Exception {
        SoapConfig config = config();
        config.getAuth().setType("WS_SECURITY");
        config.getAuth().setUsername("john");
        config.getAuth().setPassword("secret");
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Header/><soapenv:Body/></soapenv:Envelope>";

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("wsse:UsernameToken")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("john")))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call(envelope);

        server.verify();
    }

    // ── SOAP version → Content-Type ──────────────────────────────────────────

    @Test
    void call_soap11_setsTextXmlContentTypeAndSoapAction() throws Exception {
        SoapConfig config = config();
        config.setSoapVersion("1.1");
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(content().contentType(org.springframework.http.MediaType.TEXT_XML))
                .andExpect(header("SOAPAction", "GetTrades"))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>");

        server.verify();
    }

    @Test
    void call_soap12_setsSoapXmlContentType() throws Exception {
        SoapConfig config = config();
        config.setSoapVersion("1.2");
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andExpect(content().contentType("application/soap+xml; charset=utf-8"))
                .andRespond(withSuccess("<soapenv:Envelope/>", org.springframework.http.MediaType.TEXT_XML));

        client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>");

        server.verify();
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    void call_emptyResponseBody_throwsIllegalState() {
        SoapConfig config = config();
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andRespond(withSuccess("", org.springframework.http.MediaType.TEXT_XML));

        assertThatThrownBy(() -> client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty SOAP response");
    }

    @Test
    void call_httpError_wrapsInIllegalState() {
        SoapConfig config = config();
        SoapGenericItemReader.SoapClient client = builder.buildSoapClient(restTemplate, config, "trades");

        server.expect(requestTo("https://api.example.com/TradeService"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.call("<soapenv:Envelope><soapenv:Body/></soapenv:Envelope>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAP call failed with HTTP");
    }
}
