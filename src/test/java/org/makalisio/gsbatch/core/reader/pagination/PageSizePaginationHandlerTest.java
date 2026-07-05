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
import org.makalisio.gsbatch.core.model.RestConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PageSizePaginationHandlerTest {

    private RestConfig.PaginationConfig config() {
        RestConfig.PaginationConfig config = new RestConfig.PaginationConfig();
        config.setPageParam("page");
        config.setSizeParam("size");
        config.setPageSize(100);
        return config;
    }

    @Test
    void nextPageParams_startsAtPageZero() {
        PageSizePaginationHandler handler = new PageSizePaginationHandler(config());

        assertThat(handler.nextPageParams()).containsEntry("page", "0").containsEntry("size", "100");
    }

    @Test
    void onPageFetched_withItems_advancesPageAndStaysNotDone() {
        PageSizePaginationHandler handler = new PageSizePaginationHandler(config());

        handler.onPageFetched(List.of(new GenericRecord()), "{}");

        assertThat(handler.isDone()).isFalse();
        assertThat(handler.nextPageParams()).containsEntry("page", "1");
    }

    @Test
    void onPageFetched_emptyItems_marksDoneWithoutAdvancingPage() {
        PageSizePaginationHandler handler = new PageSizePaginationHandler(config());
        handler.onPageFetched(List.of(new GenericRecord()), "{}"); // page 0 -> 1

        handler.onPageFetched(List.of(), "{}"); // page 1 empty

        assertThat(handler.isDone()).isTrue();
        assertThat(handler.nextPageParams()).containsEntry("page", "1");
    }
}
