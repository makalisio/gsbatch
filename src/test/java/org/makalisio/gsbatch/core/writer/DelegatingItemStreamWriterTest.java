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

import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.ItemWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class DelegatingItemStreamWriterTest {

    @Test
    void write_DelegatesChunk() throws Exception {
        @SuppressWarnings("unchecked")
        ItemWriter<GenericRecord> delegate = mock(ItemWriter.class);
        DelegatingItemStreamWriter writer = new DelegatingItemStreamWriter(delegate);

        Chunk<GenericRecord> chunk = new Chunk<>(List.of(new GenericRecord()));
        writer.write(chunk);

        verify(delegate).write(chunk);
    }

    @Test
    void streamCallbacks_ForwardedWhenDelegateIsItemStream() {
        @SuppressWarnings("unchecked")
        ItemStreamWriter<GenericRecord> delegate = mock(ItemStreamWriter.class);
        DelegatingItemStreamWriter writer = new DelegatingItemStreamWriter(delegate);

        ExecutionContext context = new ExecutionContext();
        writer.open(context);
        writer.update(context);
        writer.close();

        verify(delegate).open(context);
        verify(delegate).update(context);
        verify(delegate).close();
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void streamCallbacks_NoOpWhenDelegateIsPlainWriter() {
        @SuppressWarnings("unchecked")
        ItemWriter<GenericRecord> delegate = mock(ItemWriter.class);
        DelegatingItemStreamWriter writer = new DelegatingItemStreamWriter(delegate);

        writer.open(new ExecutionContext());
        writer.update(new ExecutionContext());
        writer.close();

        verifyNoMoreInteractions(delegate);
    }

    @Test
    void constructor_RejectsNullDelegate() {
        assertThatThrownBy(() -> new DelegatingItemStreamWriter(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDelegate_ExposesWrappedWriter() {
        @SuppressWarnings("unchecked")
        ItemWriter<GenericRecord> delegate = mock(ItemWriter.class);
        assertThat(new DelegatingItemStreamWriter(delegate).getDelegate()).isSameAs(delegate);
    }
}
