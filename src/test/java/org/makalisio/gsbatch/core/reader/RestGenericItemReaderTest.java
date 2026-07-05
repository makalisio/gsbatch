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
import org.makalisio.gsbatch.core.model.RestConfig;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * End-to-end tests for {@link RestGenericItemReader} driven through a real
 * {@link RestTemplate} bound to {@link MockRestServiceServer}, exercising the
 * full open()/read()/close() lifecycle instead of mocking HTTP.
 */
class RestGenericItemReaderTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    private RestTemplate newRestTemplate() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        return template;
    }

    private SourceConfig sourceConfig(String url) {
        SourceConfig config = new SourceConfig();
        config.setName("orders");
        config.setType("REST");

        RestConfig rest = new RestConfig();
        rest.setUrl(url);
        rest.setDataPath("$.data");
        config.setRest(rest);

        ColumnConfig id = new ColumnConfig();
        id.setName("id");
        id.setType("STRING");
        ColumnConfig amount = new ColumnConfig();
        amount.setName("amount");
        amount.setType("DECIMAL");
        config.setColumns(List.of(id, amount));

        return config;
    }

    private RestGenericItemReader reader(SourceConfig config, Map<String, Object> jobParameters) {
        restTemplate = newRestTemplate();
        RetryTemplate retryTemplate = new RetryTemplate();
        return new RestGenericItemReader(config, config.getRest(), jobParameters, restTemplate, retryTemplate);
    }

    // ── NONE strategy ────────────────────────────────────────────────────────

    @Test
    void read_noneStrategy_returnsAllRecordsThenNull() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        RestGenericItemReader reader = reader(config, Map.of());

        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"1\",\"amount\":10.5},{\"id\":\"2\",\"amount\":20}]}",
                        MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());

        GenericRecord first = reader.read();
        GenericRecord second = reader.read();
        GenericRecord third = reader.read();

        assertThat(first.getString("id")).isEqualTo("1");
        assertThat(first.getDouble("amount")).isEqualTo(10.5);
        assertThat(second.getString("id")).isEqualTo("2");
        assertThat(third).isNull();

        reader.close();
        server.verify();
    }

    // ── PAGE_SIZE strategy ───────────────────────────────────────────────────

    @Test
    void read_pageSizeStrategy_fetchesUntilEmptyPage() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        config.getRest().getPagination().setStrategy("PAGE_SIZE");
        config.getRest().getPagination().setPageSize(1);
        RestGenericItemReader reader = reader(config, Map.of());

        server.expect(requestTo(startsWith("https://api.example.com/orders?")))
                .andExpect(queryParam("page", "0"))
                .andExpect(queryParam("size", "1"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"1\",\"amount\":10}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://api.example.com/orders?")))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParam("size", "1"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());

        assertThat(reader.read().getString("id")).isEqualTo("1");
        assertThat(reader.read()).isNull();

        server.verify();
    }

    // ── bind variables in URL ────────────────────────────────────────────────

    @Test
    void open_resolvesBindVariablesInUrl() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders/:status");
        RestGenericItemReader reader = reader(config, Map.of("status", "NEW"));

        server.expect(requestTo("https://api.example.com/orders/NEW"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();

        server.verify();
    }

    // ── POST body ─────────────────────────────────────────────────────────────

    @Test
    void fetchPage_postWithBody_sendsBodyAndDefaultsContentType() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        config.getRest().setMethod("POST");
        config.getRest().setBody("{\"status\":\":status\"}");
        RestGenericItemReader reader = reader(config, Map.of("status", "NEW"));

        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"status\":\"NEW\"}"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();

        server.verify();
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    void read_truncatedJsonResponse_throwsInsteadOfSilentlyTruncating() throws Exception {
        // A truncated/broken response body must fail loudly - not be treated
        // as "0 items", which would be indistinguishable from a legitimate
        // last page and would silently truncate the ingestion.
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        RestGenericItemReader reader = reader(config, Map.of());

        server.expect(requestTo("https://api.example.com/orders"))
                .andRespond(withSuccess("{\"data\":[", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to extract items");
    }

    @Test
    void read_emptyResponseBody_returnsNullWithoutError() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        RestGenericItemReader reader = reader(config, Map.of());

        server.expect(requestTo("https://api.example.com/orders"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNull();
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Test
    void close_clearsBufferedRecords() throws Exception {
        SourceConfig config = sourceConfig("https://api.example.com/orders");
        RestGenericItemReader reader = reader(config, Map.of());

        // Both expectations registered upfront: MockRestServiceServer's default
        // expectation manager rejects new expectations once requests have started.
        server.expect(requestTo("https://api.example.com/orders"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"1\",\"amount\":1},{\"id\":\"2\",\"amount\":2}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.example.com/orders"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        reader.open(new ExecutionContext());
        reader.read(); // consume one, leave one buffered
        reader.close();

        // Re-opening resets state; buffer from the previous execution must not leak in
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNull();
    }
}
