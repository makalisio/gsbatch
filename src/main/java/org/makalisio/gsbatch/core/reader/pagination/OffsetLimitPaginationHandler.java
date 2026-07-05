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
 * {@code OFFSET_LIMIT} strategy: advances an offset by the number of items
 * received until a page comes back empty.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class OffsetLimitPaginationHandler implements PaginationHandler {

    private final String offsetParam;
    private final String limitParam;
    private final int pageSize;

    private int currentOffset = 0;
    private boolean done = false;

    public OffsetLimitPaginationHandler(RestConfig.PaginationConfig config) {
        this.offsetParam = config.getOffsetParam();
        this.limitParam = config.getLimitParam();
        this.pageSize = config.getPageSize();
    }

    @Override
    public Map<String, String> nextPageParams() {
        Map<String, String> params = new HashMap<>();
        params.put(offsetParam, String.valueOf(currentOffset));
        params.put(limitParam, String.valueOf(pageSize));
        return params;
    }

    @Override
    public void onPageFetched(List<GenericRecord> items, String jsonResponse) {
        if (items.isEmpty()) {
            log.debug("Offset {} returned 0 items - end of pagination", currentOffset);
            done = true;
        } else {
            log.debug("Offset {} fetched: {} items", currentOffset, items.size());
            currentOffset += items.size();
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }
}
