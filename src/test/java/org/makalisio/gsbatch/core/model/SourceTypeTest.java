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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SourceTypeTest {

    @Test
    void from_exactUpperCase_matches() {
        assertThat(SourceType.from("CSV")).isEqualTo(SourceType.CSV);
        assertThat(SourceType.from("SQL")).isEqualTo(SourceType.SQL);
        assertThat(SourceType.from("REST")).isEqualTo(SourceType.REST);
        assertThat(SourceType.from("SOAP")).isEqualTo(SourceType.SOAP);
    }

    @Test
    void from_lowercaseOrMixedCase_isCaseInsensitive() {
        assertThat(SourceType.from("csv")).isEqualTo(SourceType.CSV);
        assertThat(SourceType.from("Sql")).isEqualTo(SourceType.SQL);
        assertThat(SourceType.from("  rest  ")).isEqualTo(SourceType.REST);
    }

    @Test
    void from_null_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceType.from(null));
    }

    @Test
    void from_blank_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceType.from("  "));
    }

    @Test
    void from_unrecognized_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SourceType.from("EXCEL"))
                .withMessageContaining("EXCEL");
    }

    @Test
    void isImplemented_forCoreTypes_isTrue() {
        assertThat(SourceType.CSV.isImplemented()).isTrue();
        assertThat(SourceType.SQL.isImplemented()).isTrue();
        assertThat(SourceType.REST.isImplemented()).isTrue();
        assertThat(SourceType.SOAP.isImplemented()).isTrue();
    }

    @Test
    void isImplemented_forReservedTypes_isFalse() {
        assertThat(SourceType.JSON.isImplemented()).isFalse();
        assertThat(SourceType.XML.isImplemented()).isFalse();
    }
}
