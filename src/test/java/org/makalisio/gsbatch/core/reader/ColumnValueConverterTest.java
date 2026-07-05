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

import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.ColumnConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ColumnValueConverterTest {

    private final ColumnValueConverter converter = new ColumnValueConverter();

    private ColumnConfig column(String type) {
        return column(type, null);
    }

    private ColumnConfig column(String type, String format) {
        ColumnConfig col = new ColumnConfig();
        col.setName("col");
        col.setType(type);
        col.setFormat(format);
        return col;
    }

    // ── null handling ────────────────────────────────────────────────────────

    @Test
    void convert_nullValue_returnsNull() {
        assertThat(converter.convert(null, column("STRING"))).isNull();
    }

    // ── STRING ────────────────────────────────────────────────────────────────

    @Test
    void convert_string_fromPlainString_returnsAsIs() {
        assertThat(converter.convert("hello", column("STRING"))).isEqualTo("hello");
    }

    @Test
    void convert_string_fromNumber_usesToString() {
        // REST/JsonPath can hand back an already-typed Number for a STRING column
        assertThat(converter.convert(42, column("STRING"))).isEqualTo("42");
    }

    @Test
    void convert_string_fromMap_serializesAsJson() {
        Object result = converter.convert(Map.of("a", 1), column("STRING"));
        assertThat(result).isEqualTo("{\"a\":1}");
    }

    @Test
    void convert_string_fromList_serializesAsJson() {
        Object result = converter.convert(List.of("x", "y"), column("STRING"));
        assertThat(result).isEqualTo("[\"x\",\"y\"]");
    }

    // ── INTEGER / LONG ────────────────────────────────────────────────────────

    @Test
    void convert_long_fromNumber_usesLongValueDirectly() {
        assertThat(converter.convert(42.9, column("LONG"))).isEqualTo(42L);
    }

    @Test
    void convert_long_fromString_parses() {
        assertThat(converter.convert("42", column("INTEGER"))).isEqualTo(42L);
    }

    @Test
    void convert_long_fromStringWithWhitespace_trimsBeforeParsing() {
        assertThat(converter.convert("  42  ", column("LONG"))).isEqualTo(42L);
    }

    // ── DECIMAL / DOUBLE ──────────────────────────────────────────────────────

    @Test
    void convert_double_fromNumber_usesDoubleValueDirectly() {
        assertThat(converter.convert(3, column("DOUBLE"))).isEqualTo(3.0);
    }

    @Test
    void convert_double_fromString_parses() {
        assertThat(converter.convert("3.14", column("DECIMAL"))).isEqualTo(3.14);
    }

    // ── BOOLEAN ───────────────────────────────────────────────────────────────

    @Test
    void convert_boolean_fromBoolean_returnsDirectly() {
        assertThat(converter.convert(true, column("BOOLEAN"))).isEqualTo(true);
    }

    @Test
    void convert_boolean_fromString_parses() {
        assertThat(converter.convert("true", column("BOOLEAN"))).isEqualTo(true);
    }

    // ── DATE / DATETIME ───────────────────────────────────────────────────────

    @Test
    void convert_date_withoutFormat_parsesIso() {
        assertThat(converter.convert("2024-01-15", column("DATE")))
                .isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    void convert_date_withFormat_parsesPattern() {
        assertThat(converter.convert("15/01/2024", column("DATE", "dd/MM/yyyy")))
                .isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    void convert_datetime_withoutFormat_parsesIso() {
        assertThat(converter.convert("2024-01-15T10:30:00", column("DATETIME")))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
    }

    @Test
    void convert_datetime_withFormat_parsesPattern() {
        assertThat(converter.convert("15/01/2024 10:30", column("DATETIME", "dd/MM/yyyy HH:mm")))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30));
    }

    @Test
    void convert_date_reusesCachedFormatterAcrossCalls() {
        ColumnConfig col = column("DATE", "dd/MM/yyyy");
        assertThat(converter.convert("01/02/2024", col)).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(converter.convert("28/12/2024", col)).isEqualTo(LocalDate.of(2024, 12, 28));
    }

    // ── failure fallback ──────────────────────────────────────────────────────

    @Test
    void convert_unparsableNumber_returnsOriginalValue() {
        assertThat(converter.convert("not-a-number", column("LONG"))).isEqualTo("not-a-number");
    }

    @Test
    void convert_unparsableDate_returnsOriginalValue() {
        assertThat(converter.convert("not-a-date", column("DATE"))).isEqualTo("not-a-date");
    }

    // ── unknown type ──────────────────────────────────────────────────────────

    @Test
    void convert_unknownType_returnsValueUnchanged() {
        assertThat(converter.convert("raw", column("SOMETHING_ELSE"))).isEqualTo("raw");
    }

    @Test
    void convert_typeIsCaseInsensitive() {
        assertThat(converter.convert("42", column("long"))).isEqualTo(42L);
    }
}
