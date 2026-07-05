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
package org.makalisio.gsbatch.core.reader.pagination;

import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.GenericRecord;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class NonePaginationHandlerTest {

    @Test
    void nextPageParams_isAlwaysEmpty() {
        assertThat(new NonePaginationHandler().nextPageParams()).isEmpty();
    }

    @Test
    void isDone_beforeFirstFetch_isFalse() {
        assertThat(new NonePaginationHandler().isDone()).isFalse();
    }

    @Test
    void onPageFetched_anyResult_marksDone() {
        NonePaginationHandler handler = new NonePaginationHandler();

        handler.onPageFetched(List.of(new GenericRecord()), "{}");

        assertThat(handler.isDone()).isTrue();
    }

    @Test
    void onPageFetched_emptyResult_stillMarksDone() {
        NonePaginationHandler handler = new NonePaginationHandler();

        handler.onPageFetched(List.of(), "{}");

        assertThat(handler.isDone()).isTrue();
    }
}
