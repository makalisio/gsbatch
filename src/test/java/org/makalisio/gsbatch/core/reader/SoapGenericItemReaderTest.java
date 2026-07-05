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
import org.makalisio.gsbatch.core.model.ColumnConfig;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SoapConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ExecutionContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SoapGenericItemReaderTest {

    private static final String ENVELOPE_OPEN =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">";

    // ── Lecture nominale ─────────────────────────────────────────────────────

    @Test
    void read_normalResponse_extractsRecords() throws Exception {
        String response = ENVELOPE_OPEN
                + "<soapenv:Body><GetTradesResponse>"
                + "<trade><tradeId>T1</tradeId></trade>"
                + "<trade><tradeId>T2</tradeId></trade>"
                + "</GetTradesResponse></soapenv:Body></soapenv:Envelope>";

        SoapGenericItemReader reader = reader(request -> response);
        reader.open(new ExecutionContext());

        GenericRecord first = reader.read();
        GenericRecord second = reader.read();

        assertThat(first).isNotNull();
        assertThat(first.getString("tradeId")).isEqualTo("T1");
        assertThat(second).isNotNull();
        assertThat(second.getString("tradeId")).isEqualTo("T2");
        assertThat(reader.read()).isNull();

        reader.close();
    }

    // ── SOAP Fault ───────────────────────────────────────────────────────────

    @Test
    void read_soapFault_throwsWithFaultString() {
        String response = ENVELOPE_OPEN
                + "<soapenv:Body><soapenv:Fault>"
                + "<faultstring>Invalid trade date</faultstring>"
                + "</soapenv:Fault></soapenv:Body></soapenv:Envelope>";

        SoapGenericItemReader reader = reader(request -> response);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAP Fault")
                .hasMessageContaining("Invalid trade date");
    }

    // ── Durcissement XXE ─────────────────────────────────────────────────────

    @Test
    void read_responseWithDoctype_isRejected() {
        // A DOCTYPE in a SOAP message is forbidden by the SOAP spec and is the
        // vector for XXE (local file disclosure / SSRF via external entities)
        String malicious = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + ENVELOPE_OPEN
                + "<soapenv:Body><GetTradesResponse>"
                + "<trade><tradeId>&xxe;</tradeId></trade>"
                + "</GetTradesResponse></soapenv:Body></soapenv:Envelope>";

        SoapGenericItemReader reader = reader(request -> malicious);
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read)
                .hasMessageContaining("DOCTYPE");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SoapGenericItemReader reader(SoapGenericItemReader.SoapClient soapClient) {
        SourceConfig sourceConfig = new SourceConfig();
        sourceConfig.setName("trades");
        sourceConfig.setType("SOAP");

        ColumnConfig tradeId = new ColumnConfig();
        tradeId.setName("tradeId");
        tradeId.setType("STRING");
        sourceConfig.setColumns(List.of(tradeId));

        SoapConfig soapConfig = new SoapConfig();
        soapConfig.setEndpoint("https://example.org/TradeService");
        soapConfig.setSoapAction("http://example.org/GetTrades");
        soapConfig.setRequestTemplate(ENVELOPE_OPEN
                + "<soapenv:Body><GetTrades/></soapenv:Body></soapenv:Envelope>");
        soapConfig.setDataPath("//trade");
        sourceConfig.setSoap(soapConfig);

        return new SoapGenericItemReader(sourceConfig, soapConfig, Map.of(), soapClient);
    }
}
