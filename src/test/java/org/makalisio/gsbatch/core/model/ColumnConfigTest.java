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
