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

class OffsetLimitPaginationHandlerTest {

    private RestConfig.PaginationConfig config() {
        RestConfig.PaginationConfig config = new RestConfig.PaginationConfig();
        config.setOffsetParam("offset");
        config.setLimitParam("limit");
        config.setPageSize(50);
        return config;
    }

    @Test
    void nextPageParams_startsAtOffsetZero() {
        OffsetLimitPaginationHandler handler = new OffsetLimitPaginationHandler(config());

        assertThat(handler.nextPageParams()).containsEntry("offset", "0").containsEntry("limit", "50");
    }

    @Test
    void onPageFetched_withItems_advancesOffsetByItemCount() {
        OffsetLimitPaginationHandler handler = new OffsetLimitPaginationHandler(config());

        handler.onPageFetched(List.of(new GenericRecord(), new GenericRecord(), new GenericRecord()), "{}");

        assertThat(handler.isDone()).isFalse();
        assertThat(handler.nextPageParams()).containsEntry("offset", "3");
    }

    @Test
    void onPageFetched_emptyItems_marksDoneWithoutAdvancingOffset() {
        OffsetLimitPaginationHandler handler = new OffsetLimitPaginationHandler(config());
        handler.onPageFetched(List.of(new GenericRecord(), new GenericRecord()), "{}"); // offset 0 -> 2

        handler.onPageFetched(List.of(), "{}");

        assertThat(handler.isDone()).isTrue();
        assertThat(handler.nextPageParams()).containsEntry("offset", "2");
    }
}
