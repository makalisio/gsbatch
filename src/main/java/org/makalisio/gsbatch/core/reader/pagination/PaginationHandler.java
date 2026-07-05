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
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.PaginationStrategy;
import org.makalisio.gsbatch.core.model.RestConfig;

import java.util.List;
import java.util.Map;

/**
 * Strategy for a single {@link PaginationStrategy}: decides the extra query
 * parameters to request the next page, and whether pagination is complete.
 *
 * <p>Each implementation owns its own pagination state (current page/offset,
 * cursor, ...) instead of {@code RestGenericItemReader} juggling fields for
 * every strategy at once. This is also what makes each strategy directly
 * unit-testable, without mocking HTTP.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public interface PaginationHandler {

    /**
     * Extra query parameters to merge into the request for the next page
     * (e.g. {@code page}/{@code size}, {@code offset}/{@code limit}, or the
     * current cursor). Returns an empty map when the strategy needs nothing
     * beyond the base URL/query params.
     */
    Map<String, String> nextPageParams();

    /**
     * Called once a page has been fetched, so the handler can update its
     * internal state and decide whether more pages remain.
     *
     * @param items        the records extracted from this page (never null, may be empty)
     * @param jsonResponse the raw JSON response body, for strategies that need
     *                     to inspect it directly (e.g. CURSOR extracting the next cursor)
     */
    void onPageFetched(List<GenericRecord> items, String jsonResponse);

    /**
     * @return {@code true} if there are no more pages to fetch
     */
    boolean isDone();

    /**
     * Creates the handler matching {@code restConfig.getPagination().getStrategy()}.
     *
     * @param restConfig     REST source configuration
     * @param jsonPathConfig shared JsonPath configuration (suppressed exceptions, null leaves)
     * @return a fresh, per-execution handler
     * @throws UnsupportedOperationException if the strategy has no implementation yet (LINK_HEADER)
     */
    static PaginationHandler forStrategy(RestConfig restConfig, Configuration jsonPathConfig) {
        RestConfig.PaginationConfig config = restConfig.getPagination();
        PaginationStrategy strategy = config.getPaginationStrategy();

        return switch (strategy) {
            case NONE -> new NonePaginationHandler();
            case PAGE_SIZE -> new PageSizePaginationHandler(config);
            case OFFSET_LIMIT -> new OffsetLimitPaginationHandler(config);
            case CURSOR -> new CursorPaginationHandler(config, jsonPathConfig);
            case LINK_HEADER -> throw new UnsupportedOperationException(
                    "Pagination strategy not yet implemented: " + strategy);
        };
    }
}
