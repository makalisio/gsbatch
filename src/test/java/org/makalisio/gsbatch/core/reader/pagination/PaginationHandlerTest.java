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
import org.makalisio.gsbatch.core.model.RestConfig;

import static org.assertj.core.api.Assertions.*;

class PaginationHandlerTest {

    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    private RestConfig restConfig(String strategy) {
        RestConfig restConfig = new RestConfig();
        restConfig.setUrl("https://api.example.com/orders");
        restConfig.getPagination().setStrategy(strategy);
        restConfig.getPagination().setCursorPath("$.next");
        return restConfig;
    }

    @Test
    void forStrategy_none_returnsNoneHandler() {
        assertThat(PaginationHandler.forStrategy(restConfig("NONE"), JSON_PATH_CONFIG))
                .isInstanceOf(NonePaginationHandler.class);
    }

    @Test
    void forStrategy_pageSize_returnsPageSizeHandler() {
        assertThat(PaginationHandler.forStrategy(restConfig("PAGE_SIZE"), JSON_PATH_CONFIG))
                .isInstanceOf(PageSizePaginationHandler.class);
    }

    @Test
    void forStrategy_offsetLimit_returnsOffsetLimitHandler() {
        assertThat(PaginationHandler.forStrategy(restConfig("OFFSET_LIMIT"), JSON_PATH_CONFIG))
                .isInstanceOf(OffsetLimitPaginationHandler.class);
    }

    @Test
    void forStrategy_cursor_returnsCursorHandler() {
        assertThat(PaginationHandler.forStrategy(restConfig("CURSOR"), JSON_PATH_CONFIG))
                .isInstanceOf(CursorPaginationHandler.class);
    }

    @Test
    void forStrategy_linkHeader_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> PaginationHandler.forStrategy(restConfig("LINK_HEADER"), JSON_PATH_CONFIG))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("LINK_HEADER");
    }

    @Test
    void forStrategy_isCaseInsensitive() {
        assertThat(PaginationHandler.forStrategy(restConfig("cursor"), JSON_PATH_CONFIG))
                .isInstanceOf(CursorPaginationHandler.class);
    }
}
