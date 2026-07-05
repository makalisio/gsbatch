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
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.RestConfig;

import java.util.List;
import java.util.Map;

/**
 * {@code CURSOR} strategy: extracts the next-page cursor from each response.
 *
 * <p>Termination is driven purely by cursor presence, never by the page's
 * item count: a filtered/rate-limited page can legitimately return zero
 * items while still carrying a valid next cursor, and a page with items can
 * be the last one if the API returns no next cursor for it.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class CursorPaginationHandler implements PaginationHandler {

    private final String cursorParam;
    private final String cursorPath;
    private final Configuration jsonPathConfig;

    private String currentCursor;
    private boolean done = false;

    public CursorPaginationHandler(RestConfig.PaginationConfig config, Configuration jsonPathConfig) {
        this.cursorParam = config.getCursorParam();
        this.cursorPath = config.getCursorPath();
        this.jsonPathConfig = jsonPathConfig;
    }

    @Override
    public Map<String, String> nextPageParams() {
        return currentCursor == null ? Map.of() : Map.of(cursorParam, currentCursor);
    }

    @Override
    public void onPageFetched(List<GenericRecord> items, String jsonResponse) {
        currentCursor = extractCursor(jsonResponse);

        if (items.isEmpty()) {
            log.debug("Cursor returned 0 items, next cursor: '{}'", currentCursor);
        } else {
            log.debug("Cursor fetched: {} items, next cursor: '{}'", items.size(), currentCursor);
        }

        if (currentCursor == null) {
            done = true;
        }
    }

    private String extractCursor(String jsonResponse) {
        try {
            Object cursorObj = JsonPath.using(jsonPathConfig)
                    .parse(jsonResponse)
                    .read(cursorPath);
            return cursorObj != null ? cursorObj.toString() : null;
        } catch (Exception e) {
            log.warn("Could not extract cursor from response", e);
            return null;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }
}
