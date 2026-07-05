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

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PAGE_SIZE} strategy: increments a page number until a page comes
 * back empty.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class PageSizePaginationHandler implements PaginationHandler {

    private final String pageParam;
    private final String sizeParam;
    private final int pageSize;

    private int currentPage = 0;
    private boolean done = false;

    public PageSizePaginationHandler(RestConfig.PaginationConfig config) {
        this.pageParam = config.getPageParam();
        this.sizeParam = config.getSizeParam();
        this.pageSize = config.getPageSize();
    }

    @Override
    public Map<String, String> nextPageParams() {
        Map<String, String> params = new HashMap<>();
        params.put(pageParam, String.valueOf(currentPage));
        params.put(sizeParam, String.valueOf(pageSize));
        return params;
    }

    @Override
    public void onPageFetched(List<GenericRecord> items, String jsonResponse) {
        if (items.isEmpty()) {
            log.debug("Page {} returned 0 items - end of pagination", currentPage);
            done = true;
        } else {
            log.debug("Page {} fetched: {} items", currentPage, items.size());
            currentPage++;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }
}
