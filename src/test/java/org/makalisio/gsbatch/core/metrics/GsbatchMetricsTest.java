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
package org.makalisio.gsbatch.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GsbatchMetricsTest {

    private SimpleMeterRegistry registry;
    private GsbatchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GsbatchMetrics(registry, "orders", "rest-reader");
    }

    @Test
    void itemsRead_incrementsCounterByGivenCount() {
        metrics.itemsRead(3);
        metrics.itemsRead(2);

        assertThat(registry.get("gsbatch.reader.items")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .counter().count()).isEqualTo(5.0);
    }

    @Test
    void itemsWritten_incrementsCounterByGivenCount() {
        metrics.itemsWritten(10);

        assertThat(registry.get("gsbatch.writer.items")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .counter().count()).isEqualTo(10.0);
    }

    @Test
    void pageFetched_incrementsCounterByOne() {
        metrics.pageFetched();
        metrics.pageFetched();
        metrics.pageFetched();

        assertThat(registry.get("gsbatch.reader.pages")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void error_incrementsCounterTaggedWithErrorType() {
        metrics.error("http_call");
        metrics.error("http_call");
        metrics.error("json_extraction");

        assertThat(registry.get("gsbatch.errors")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .tag("error", "http_call")
                .counter().count()).isEqualTo(2.0);

        assertThat(registry.get("gsbatch.errors")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .tag("error", "json_extraction")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void retryAttempted_incrementsCounterByOne() {
        metrics.retryAttempted();

        assertThat(registry.get("gsbatch.retry.attempts")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordCall_returnsCallableResultAndRecordsTiming() throws Exception {
        String result = metrics.recordCall(() -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(registry.get("gsbatch.reader.calls")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void recordCall_propagatesExceptionFromCallable() {
        assertThatThrownBy(() -> metrics.recordCall(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(registry.get("gsbatch.reader.calls")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void differentComponents_areTaggedIndependently() {
        GsbatchMetrics soapMetrics = new GsbatchMetrics(registry, "orders", "soap-reader");
        metrics.itemsRead(1);
        soapMetrics.itemsRead(4);

        assertThat(registry.get("gsbatch.reader.items")
                .tag("source", "orders")
                .tag("component", "rest-reader")
                .counter().count()).isEqualTo(1.0);

        assertThat(registry.get("gsbatch.reader.items")
                .tag("source", "orders")
                .tag("component", "soap-reader")
                .counter().count()).isEqualTo(4.0);
    }
}
