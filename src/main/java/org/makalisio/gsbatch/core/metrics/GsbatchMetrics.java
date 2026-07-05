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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Callable;

/**
 * Thin, per-instance wrapper around a {@link MeterRegistry} that tags every
 * meter with the owning source name and component, so gsbatch's readers and
 * writers don't each repeat the same tag-building boilerplate.
 *
 * <p>Meters published (all under the {@code gsbatch.} prefix, tagged with
 * {@code source} and {@code component}):</p>
 * <ul>
 *   <li>{@code gsbatch.reader.items} - counter, items successfully read</li>
 *   <li>{@code gsbatch.reader.pages} - counter, pages fetched (REST pagination depth)</li>
 *   <li>{@code gsbatch.errors} - counter, tagged additionally with {@code error}
 *       (published by both readers and writers)</li>
 *   <li>{@code gsbatch.reader.calls} - timer, wall-clock time of one HTTP/SOAP call
 *       (including any retries spent inside it)</li>
 *   <li>{@code gsbatch.retry.attempts} - counter, retry attempts (not counting the initial try)</li>
 *   <li>{@code gsbatch.writer.items} - counter, rows written</li>
 * </ul>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public class GsbatchMetrics {

    private final MeterRegistry registry;
    private final Tags tags;

    /**
     * @param registry  the registry to publish to (a consumer-provided bean if
     *                  one exists, otherwise a private in-memory registry - see
     *                  the builders that construct this class)
     * @param sourceName the source name (e.g. "orders")
     * @param component  the component publishing metrics (e.g. "rest-reader", "soap-reader", "sql-writer")
     */
    public GsbatchMetrics(MeterRegistry registry, String sourceName, String component) {
        this.registry = registry;
        this.tags = Tags.of("source", sourceName, "component", component);
    }

    /**
     * Increments {@code gsbatch.reader.items} by {@code count}.
     */
    public void itemsRead(int count) {
        Counter.builder("gsbatch.reader.items").tags(tags).register(registry).increment(count);
    }

    /**
     * Increments {@code gsbatch.writer.items} by {@code count}.
     */
    public void itemsWritten(int count) {
        Counter.builder("gsbatch.writer.items").tags(tags).register(registry).increment(count);
    }

    /**
     * Increments {@code gsbatch.reader.pages} by one.
     */
    public void pageFetched() {
        Counter.builder("gsbatch.reader.pages").tags(tags).register(registry).increment();
    }

    /**
     * Increments {@code gsbatch.errors}, tagged with {@code error=errorType}.
     */
    public void error(String errorType) {
        Counter.builder("gsbatch.errors").tags(tags).tag("error", errorType).register(registry).increment();
    }

    /**
     * Increments {@code gsbatch.retry.attempts} by one.
     */
    public void retryAttempted() {
        Counter.builder("gsbatch.retry.attempts").tags(tags).register(registry).increment();
    }

    /**
     * Records the duration of {@code call} under {@code gsbatch.reader.calls},
     * propagating whatever exception {@code call} throws.
     */
    public <T> T recordCall(Callable<T> call) throws Exception {
        return Timer.builder("gsbatch.reader.calls").tags(tags).register(registry).recordCallable(call);
    }
}
