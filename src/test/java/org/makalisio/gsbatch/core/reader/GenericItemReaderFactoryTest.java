package org.makalisio.gsbatch.core.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ItemStreamReader;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenericItemReaderFactoryTest {

    @Mock CsvGenericItemReaderBuilder csvBuilder;
    @Mock SqlGenericItemReaderBuilder sqlBuilder;
    @Mock RestGenericItemReaderBuilder restBuilder;
    @Mock SoapGenericItemReaderBuilder soapBuilder;

    private GenericItemReaderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GenericItemReaderFactory(csvBuilder, sqlBuilder, restBuilder, soapBuilder);
    }

    // ── Dispatch vers les builders ───────────────────────────────────────────

    @Test
    void buildReader_csvType_delegatesToCsvBuilder() {
        SourceConfig config = config("orders", "CSV");
        // doReturn bypasses compile-time type checking (builder returns FlatFileItemReader)
        doReturn(null).when(csvBuilder).build(config);

        factory.buildReader(config, Map.of());

        verify(csvBuilder).build(config);
        verifyNoInteractions(sqlBuilder, restBuilder, soapBuilder);
    }

    @Test
    void buildReader_sqlType_delegatesToSqlBuilder() {
        SourceConfig config = config("orders", "SQL");
        Map<String, Object> params = Map.of("status", "NEW");
        doReturn(null).when(sqlBuilder).build(config, params);

        factory.buildReader(config, params);

        verify(sqlBuilder).build(config, params);
        verifyNoInteractions(csvBuilder, restBuilder, soapBuilder);
    }

    @Test
    void buildReader_restType_delegatesToRestBuilder() {
        SourceConfig config = config("api", "REST");
        doReturn(null).when(restBuilder).build(config, Map.of());

        factory.buildReader(config, Map.of());

        verify(restBuilder).build(config, Map.of());
        verifyNoInteractions(csvBuilder, sqlBuilder, soapBuilder);
    }

    @Test
    void buildReader_soapType_delegatesToSoapBuilder() {
        SourceConfig config = config("ws", "SOAP");
        doReturn(null).when(soapBuilder).build(config, Map.of());

        factory.buildReader(config, Map.of());

        verify(soapBuilder).build(config, Map.of());
        verifyNoInteractions(csvBuilder, sqlBuilder, restBuilder);
    }

    // ── Insensibilité à la casse ─────────────────────────────────────────────

    @Test
    void buildReader_lowercaseCsvType_delegatesToCsvBuilder() {
        SourceConfig config = config("orders", "csv");
        doReturn(null).when(csvBuilder).build(config);

        factory.buildReader(config, Map.of());

        verify(csvBuilder).build(config);
    }

    @Test
    void buildReader_mixedCaseSqlType_delegatesToSqlBuilder() {
        SourceConfig config = config("orders", "Sql");
        doReturn(null).when(sqlBuilder).build(config, Map.of());

        factory.buildReader(config, Map.of());

        verify(sqlBuilder).build(config, Map.of());
    }

    // ── Types non supportés ──────────────────────────────────────────────────

    @Test
    void buildReader_jsonType_throwsUnsupportedOperation() {
        SourceConfig config = config("data", "JSON");

        assertThatThrownBy(() -> factory.buildReader(config, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void buildReader_xmlType_throwsUnsupportedOperation() {
        SourceConfig config = config("data", "XML");

        assertThatThrownBy(() -> factory.buildReader(config, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void buildReader_unknownType_throwsIllegalArgument() {
        SourceConfig config = config("data", "EXCEL");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildReader(config, Map.of()))
                .withMessageContaining("Unsupported source type");
    }

    // ── Cas d'erreur sur config ──────────────────────────────────────────────

    @Test
    void buildReader_nullConfig_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildReader(null, Map.of()));
    }

    @Test
    void buildReader_nullType_throwsIllegalArgument() {
        SourceConfig config = config("orders", null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildReader(config, Map.of()))
                .withMessageContaining("Source type must not be null");
    }

    @Test
    void buildReader_blankType_throwsIllegalArgument() {
        SourceConfig config = config("orders", "  ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildReader(config, Map.of()));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private SourceConfig config(String name, String type) {
        SourceConfig sc = new SourceConfig();
        sc.setName(name);
        sc.setType(type);
        return sc;
    }
}
