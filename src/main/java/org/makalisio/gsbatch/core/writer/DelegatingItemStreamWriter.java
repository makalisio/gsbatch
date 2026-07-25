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
package org.makalisio.gsbatch.core.writer;

import org.makalisio.gsbatch.core.model.GenericRecord;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;

/**
 * Wraps the writer resolved by {@link GenericItemWriterFactory} so that the
 * {@code genericIngestionWriter} step-scoped bean is exposed as an
 * {@link ItemStreamWriter}, propagating the {@link ItemStream} lifecycle
 * ({@code open}/{@code update}/{@code close}) to the underlying writer when it
 * implements it.
 *
 * <p><b>Why this exists:</b> the {@code @StepScope} proxy Spring creates for a
 * bean only exposes the bean method's declared return type. When that type was
 * {@code ItemWriter}, the proxy did not implement {@code ItemStream}, so
 * {@code SimpleStepBuilder#registerAsStreamsAndListeners} never registered the
 * writer as a stream — and a consumer writer implementing {@code ItemStream}
 * silently never received {@code open()}/{@code close()}. Declaring the bean as
 * {@code ItemStreamWriter} (via this wrapper) makes the proxy carry
 * {@code ItemStream}, so Spring Batch registers it and the callbacks reach the
 * delegate.</p>
 *
 * <p>If the delegate does not implement {@code ItemStream} (e.g.
 * {@link SqlGenericItemWriter}), the stream callbacks are harmless no-ops.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public class DelegatingItemStreamWriter implements ItemStreamWriter<GenericRecord> {

    private final ItemWriter<GenericRecord> delegate;

    /**
     * @param delegate the actual writer resolved for the source; must not be null
     */
    public DelegatingItemStreamWriter(ItemWriter<GenericRecord> delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate writer cannot be null");
        }
        this.delegate = delegate;
    }

    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        delegate.write(chunk);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.open(executionContext);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.update(executionContext);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.close();
        }
    }

    /**
     * @return the wrapped writer (for diagnostics/tests)
     */
    public ItemWriter<GenericRecord> getDelegate() {
        return delegate;
    }
}
