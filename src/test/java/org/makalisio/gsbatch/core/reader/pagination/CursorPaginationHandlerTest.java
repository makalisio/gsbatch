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

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CursorPaginationHandlerTest {

    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    private CursorPaginationHandler handler() {
        RestConfig.PaginationConfig config = new RestConfig.PaginationConfig();
        config.setCursorParam("cursor");
        config.setCursorPath("$.meta.nextCursor");
        return new CursorPaginationHandler(config, JSON_PATH_CONFIG);
    }

    @Test
    void nextPageParams_beforeFirstFetch_isEmpty() {
        assertThat(handler().nextPageParams()).isEmpty();
    }

    @Test
    void onPageFetched_extractsCursorForNextPage() {
        CursorPaginationHandler handler = handler();

        handler.onPageFetched(List.of(new GenericRecord()), "{\"meta\":{\"nextCursor\":\"abc123\"}}");

        assertThat(handler.isDone()).isFalse();
        assertThat(handler.nextPageParams()).containsEntry("cursor", "abc123");
    }

    @Test
    void onPageFetched_nonEmptyPageWithNullCursor_marksDone_regressionForInfiniteLoopBug() {
        // Regression test: the API returns real data on the last page but no
        // next cursor. The old implementation used items.isEmpty() to decide
        // termination, so it kept re-fetching page 1 forever in this exact case.
        CursorPaginationHandler handler = handler();

        handler.onPageFetched(List.of(new GenericRecord(), new GenericRecord()), "{\"meta\":{}}");

        assertThat(handler.isDone()).isTrue();
        assertThat(handler.nextPageParams()).isEmpty();
    }

    @Test
    void onPageFetched_emptyPageWithValidCursor_staysNotDone_regressionForPrematureTruncation() {
        // Regression test: a filtered/rate-limited page returns zero items but
        // still carries a valid next cursor. The old implementation stopped
        // here, silently truncating the rest of the dataset.
        CursorPaginationHandler handler = handler();

        handler.onPageFetched(List.of(), "{\"meta\":{\"nextCursor\":\"page-2-cursor\"}}");

        assertThat(handler.isDone()).isFalse();
        assertThat(handler.nextPageParams()).containsEntry("cursor", "page-2-cursor");
    }

    @Test
    void onPageFetched_malformedJson_treatsCursorAsAbsentAndMarksDone() {
        CursorPaginationHandler handler = handler();

        handler.onPageFetched(List.of(new GenericRecord()), "not valid json");

        assertThat(handler.isDone()).isTrue();
    }

    @Test
    void onPageFetched_successiveCursors_chainsCorrectly() {
        CursorPaginationHandler handler = handler();

        handler.onPageFetched(List.of(new GenericRecord()), "{\"meta\":{\"nextCursor\":\"page2\"}}");
        assertThat(handler.nextPageParams()).containsEntry("cursor", "page2");

        handler.onPageFetched(List.of(new GenericRecord()), "{\"meta\":{\"nextCursor\":\"page3\"}}");
        assertThat(handler.nextPageParams()).containsEntry("cursor", "page3");
        assertThat(handler.isDone()).isFalse();

        handler.onPageFetched(List.of(new GenericRecord()), "{\"meta\":{}}");
        assertThat(handler.isDone()).isTrue();
    }
}
