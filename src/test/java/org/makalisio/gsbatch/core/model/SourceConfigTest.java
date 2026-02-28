package org.makalisio.gsbatch.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SourceConfigTest {

    // ── validate() — champs requis ───────────────────────────────────────────

    @Test
    void validate_nullName_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setType("CSV");
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Source name is required");
    }

    @Test
    void validate_blankName_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setName("  ");
        sc.setType("CSV");
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Source name is required");
    }

    @Test
    void validate_nullType_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Source type is required");
    }

    // ── validate() — type CSV ────────────────────────────────────────────────

    @Test
    void validate_csv_missingPath_throws() {
        SourceConfig sc = csvConfig();
        sc.setPath(null);
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Path is required");
    }

    @Test
    void validate_csv_missingColumns_throws() {
        SourceConfig sc = csvConfig();
        sc.setColumns(List.of());
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Columns configuration is required");
    }

    @Test
    void validate_csv_columnWithBlankName_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        sc.setType("CSV");
        sc.setPath("/data/orders.csv");
        ColumnConfig col = new ColumnConfig();
        col.setName("  ");
        sc.setColumns(List.of(col));
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Column name is required at index 0");
    }

    @Test
    void validate_csv_valid_passes() {
        assertThatNoException().isThrownBy(csvConfig()::validate);
    }

    @Test
    void validate_csv_typeIsCaseInsensitive() {
        SourceConfig sc = csvConfig();
        sc.setType("csv");
        assertThatNoException().isThrownBy(sc::validate);
    }

    // ── validate() — type SQL ────────────────────────────────────────────────

    @Test
    void validate_sql_missingSqlDirectory_throws() {
        SourceConfig sc = sqlConfig();
        sc.setSqlDirectory(null);
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("sqlDirectory");
    }

    @Test
    void validate_sql_missingSqlFile_throws() {
        SourceConfig sc = sqlConfig();
        sc.setSqlFile(null);
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("sqlFile");
    }

    @Test
    void validate_sql_valid_passes() {
        assertThatNoException().isThrownBy(sqlConfig()::validate);
    }

    // ── validate() — type REST / SOAP ────────────────────────────────────────

    @Test
    void validate_rest_missingRestConfig_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setName("api");
        sc.setType("REST");
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("rest configuration is required");
    }

    @Test
    void validate_soap_missingSoapConfig_throws() {
        SourceConfig sc = new SourceConfig();
        sc.setName("ws");
        sc.setType("SOAP");
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("soap configuration is required");
    }

    // ── validate() — chunkSize ───────────────────────────────────────────────

    @Test
    void validate_negativeChunkSize_throws() {
        SourceConfig sc = sqlConfig();
        sc.setChunkSize(-1);
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Chunk size must be positive");
    }

    @Test
    void validate_zeroChunkSize_throws() {
        SourceConfig sc = sqlConfig();
        sc.setChunkSize(0);
        // chunkSize=0 is treated as default (1000) by getChunkSize(), but validate() rejects <= 0 explicitly set
        // The validation only triggers when chunkSize != null && chunkSize <= 0
        assertThatIllegalStateException()
                .isThrownBy(sc::validate)
                .withMessageContaining("Chunk size must be positive");
    }

    @Test
    void validate_positiveChunkSize_passes() {
        SourceConfig sc = sqlConfig();
        sc.setChunkSize(500);
        assertThatNoException().isThrownBy(sc::validate);
    }

    // ── getChunkSize() ───────────────────────────────────────────────────────

    @Test
    void getChunkSize_notSet_returns1000() {
        assertThat(new SourceConfig().getChunkSize()).isEqualTo(1000);
    }

    @Test
    void getChunkSize_set_returnsConfiguredValue() {
        SourceConfig sc = new SourceConfig();
        sc.setChunkSize(500);
        assertThat(sc.getChunkSize()).isEqualTo(500);
    }

    @Test
    void getChunkSize_zero_returns1000() {
        SourceConfig sc = new SourceConfig();
        sc.setChunkSize(0);
        assertThat(sc.getChunkSize()).isEqualTo(1000);
    }

    // ── getEffectiveFetchSize() ──────────────────────────────────────────────

    @Test
    void getEffectiveFetchSize_notSet_returns1000() {
        assertThat(new SourceConfig().getEffectiveFetchSize()).isEqualTo(1000);
    }

    @Test
    void getEffectiveFetchSize_set_returnsConfiguredValue() {
        SourceConfig sc = new SourceConfig();
        sc.setFetchSize(200);
        assertThat(sc.getEffectiveFetchSize()).isEqualTo(200);
    }

    @Test
    void getEffectiveFetchSize_zero_returns1000() {
        SourceConfig sc = new SourceConfig();
        sc.setFetchSize(0);
        assertThat(sc.getEffectiveFetchSize()).isEqualTo(1000);
    }

    // ── getColumnNames() ─────────────────────────────────────────────────────

    @Test
    void getColumnNames_emptyColumns_returnsEmptyArray() {
        assertThat(new SourceConfig().getColumnNames()).isEmpty();
    }

    @Test
    void getColumnNames_returnsNamesInOrder() {
        SourceConfig sc = new SourceConfig();
        ColumnConfig c1 = new ColumnConfig(); c1.setName("id");
        ColumnConfig c2 = new ColumnConfig(); c2.setName("amount");
        sc.setColumns(List.of(c1, c2));
        assertThat(sc.getColumnNames()).containsExactly("id", "amount");
    }

    // ── hasXxx() ─────────────────────────────────────────────────────────────

    @Test
    void hasRestConfig_whenSet_returnsTrue() {
        SourceConfig sc = new SourceConfig();
        sc.setRest(new RestConfig());
        assertThat(sc.hasRestConfig()).isTrue();
    }

    @Test
    void hasRestConfig_whenNull_returnsFalse() {
        assertThat(new SourceConfig().hasRestConfig()).isFalse();
    }

    @Test
    void hasSoapConfig_whenSet_returnsTrue() {
        SourceConfig sc = new SourceConfig();
        sc.setSoap(new SoapConfig());
        assertThat(sc.hasSoapConfig()).isTrue();
    }

    @Test
    void hasSoapConfig_whenNull_returnsFalse() {
        assertThat(new SourceConfig().hasSoapConfig()).isFalse();
    }

    @Test
    void hasWriterConfig_whenSet_returnsTrue() {
        SourceConfig sc = new SourceConfig();
        sc.setWriter(new WriterConfig());
        assertThat(sc.hasWriterConfig()).isTrue();
    }

    @Test
    void hasWriterConfig_whenNull_returnsFalse() {
        assertThat(new SourceConfig().hasWriterConfig()).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SourceConfig csvConfig() {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        sc.setType("CSV");
        sc.setPath("/data/orders.csv");
        ColumnConfig col = new ColumnConfig();
        col.setName("id");
        sc.setColumns(List.of(col));
        return sc;
    }

    private SourceConfig sqlConfig() {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        sc.setType("SQL");
        sc.setSqlDirectory("/sql");
        sc.setSqlFile("orders.sql");
        return sc;
    }
}
