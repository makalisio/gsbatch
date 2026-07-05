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
package org.makalisio.gsbatch.core.model;

import java.util.Locale;

/**
 * Recognized values for {@link RestConfig.PaginationConfig#getStrategy()}.
 *
 * <p>{@code LINK_HEADER} parses and validates successfully but has no
 * implementation yet - {@code RestGenericItemReader} throws
 * {@code UnsupportedOperationException} when it is used at read time.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public enum PaginationStrategy {
    NONE,
    PAGE_SIZE,
    OFFSET_LIMIT,
    CURSOR,
    LINK_HEADER;

    /**
     * Parses a YAML {@code rest.pagination.strategy} value, case-insensitively.
     *
     * @param value the raw string value
     * @return the matching {@code PaginationStrategy}
     * @throws IllegalArgumentException if {@code value} is null, blank, or unrecognized
     */
    public static PaginationStrategy from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rest.pagination.strategy must not be null or blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unrecognized rest.pagination.strategy: '" + value + "'");
        }
    }
}
