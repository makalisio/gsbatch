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
package org.makalisio.gsbatch.core.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.ColumnConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a raw extracted value (from JsonPath for REST, from XPath text for
 * SOAP) into the type declared by {@link ColumnConfig#getType()}.
 *
 * <p>Shared by {@link RestGenericItemReader} and {@link SoapGenericItemReader}
 * so that STRING/INTEGER/LONG/DECIMAL/DOUBLE/BOOLEAN/DATE/DATETIME conversion
 * (and the {@link DateTimeFormatter} cache backing DATE/DATETIME) is
 * implemented and tested in one place instead of two near-identical
 * switches.</p>
 *
 * <p>{@code value} may already be a typed {@link Number}/{@link Boolean}
 * (REST's JsonPath can return one directly) or a plain {@link String} (SOAP's
 * XPath always returns text) - both are handled. A {@code null} value returns
 * {@code null} without inspecting {@code column}; callers that treat a blank
 * string as "no value" (SOAP) must check that before calling {@link #convert}.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class ColumnValueConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, DateTimeFormatter> dateFormatters = new HashMap<>();

    /**
     * Converts {@code value} to the type declared by {@code column}.
     *
     * @param value  the raw extracted value, or {@code null}
     * @param column the column configuration (its {@code type} is assumed non-blank,
     *               enforced by {@code SourceConfig.validate()} for REST/SOAP sources)
     * @return the converted value, or the original {@code value} unchanged if
     *         conversion fails (logged as a warning) or the type is unrecognized
     */
    public Object convert(Object value, ColumnConfig column) {
        if (value == null) {
            return null;
        }

        String type = column.getType().toUpperCase(Locale.ROOT);

        try {
            return switch (type) {
                case "STRING" -> convertToString(value, column);
                case "INTEGER", "LONG" -> convertToLong(value);
                case "DECIMAL", "DOUBLE" -> convertToDouble(value);
                case "BOOLEAN" -> convertToBoolean(value);
                case "DATE" -> convertToDate(value, column.getFormat());
                case "DATETIME" -> convertToDateTime(value, column.getFormat());
                default -> value;
            };
        } catch (Exception e) {
            log.warn("Failed to convert value '{}' to type {} for column {}: {}",
                    value, type, column.getName(), e.getMessage());
            return value;  // Return as-is if conversion fails
        }
    }

    private Object convertToString(Object value, ColumnConfig column) {
        // Map/List from JSON must be serialized as valid JSON, not via toString()
        if (value instanceof Map || value instanceof List) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                log.warn("Failed to serialize JSON value for column {}: {}",
                        column.getName(), e.getMessage());
                return value.toString();
            }
        }
        return value.toString();
    }

    private Long convertToLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(trimmed(value));
    }

    private Double convertToDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(trimmed(value));
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(trimmed(value));
    }

    private LocalDate convertToDate(Object value, String format) {
        String dateStr = trimmed(value);
        return (format != null && !format.isBlank())
                ? LocalDate.parse(dateStr, formatterFor(format))
                : LocalDate.parse(dateStr);
    }

    private LocalDateTime convertToDateTime(Object value, String format) {
        String datetimeStr = trimmed(value);
        return (format != null && !format.isBlank())
                ? LocalDateTime.parse(datetimeStr, formatterFor(format))
                : LocalDateTime.parse(datetimeStr);
    }

    private String trimmed(Object value) {
        return value.toString().trim();
    }

    private DateTimeFormatter formatterFor(String pattern) {
        return dateFormatters.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }
}
