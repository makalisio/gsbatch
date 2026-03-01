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

class ColumnConfigTest {

    @Test
    void validate_withName_passes() {
        ColumnConfig col = new ColumnConfig();
        col.setName("amount");
        assertThatNoException().isThrownBy(col::validate);
    }

    @Test
    void validate_nullName_throwsIllegalState() {
        ColumnConfig col = new ColumnConfig();
        col.setName(null);
        assertThatIllegalStateException()
                .isThrownBy(col::validate)
                .withMessageContaining("Column name is required");
    }

    @Test
    void validate_blankName_throwsIllegalState() {
        ColumnConfig col = new ColumnConfig();
        col.setName("  ");
        assertThatIllegalStateException()
                .isThrownBy(col::validate)
                .withMessageContaining("Column name is required");
    }

    @Test
    void validate_emptyName_throwsIllegalState() {
        ColumnConfig col = new ColumnConfig();
        col.setName("");
        assertThatIllegalStateException()
                .isThrownBy(col::validate);
    }

    @Test
    void defaultValues_areCorrect() {
        ColumnConfig col = new ColumnConfig();
        assertThat(col.isRequired()).isFalse();
        assertThat(col.getDefaultValue()).isNull();
        assertThat(col.getFormat()).isNull();
        assertThat(col.getJsonPath()).isNull();
        assertThat(col.getXpath()).isNull();
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        ColumnConfig col = new ColumnConfig("price", "DECIMAL", "#,##0.00", "$.price", null, true, "0.0");
        assertThat(col.getName()).isEqualTo("price");
        assertThat(col.getType()).isEqualTo("DECIMAL");
        assertThat(col.getFormat()).isEqualTo("#,##0.00");
        assertThat(col.getJsonPath()).isEqualTo("$.price");
        assertThat(col.isRequired()).isTrue();
        assertThat(col.getDefaultValue()).isEqualTo("0.0");
    }
}
