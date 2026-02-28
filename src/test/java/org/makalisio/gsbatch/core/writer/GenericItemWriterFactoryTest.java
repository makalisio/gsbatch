package org.makalisio.gsbatch.core.writer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenericItemWriterFactoryTest {

    @TempDir Path tempDir;

    @Mock ApplicationContext applicationContext;
    @Mock SqlFileLoader sqlFileLoader;
    @Mock DataSource dataSource;
    @Mock BeanFactory beanFactory;
    @Mock ItemWriter<GenericRecord> mockWriter;

    private GenericItemWriterFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GenericItemWriterFactory(applicationContext, sqlFileLoader, dataSource, beanFactory);
    }

    // ── Case 1 : WriterConfig déclaratif — type SQL ──────────────────────────

    @Test
    void buildWriter_sqlType_returnsSqlGenericItemWriter() throws IOException {
        Files.writeString(tempDir.resolve("insert.sql"), "INSERT INTO T VALUES (:v)");
        when(sqlFileLoader.readRawSql(tempDir.toString(), "insert.sql"))
                .thenReturn("INSERT INTO T VALUES (:v)");

        SourceConfig config = configWithSqlWriter(tempDir.toString(), "insert.sql");
        ItemWriter<GenericRecord> writer = factory.buildWriter(config);

        assertThat(writer).isInstanceOf(SqlGenericItemWriter.class);
        verify(sqlFileLoader).readRawSql(tempDir.toString(), "insert.sql");
    }

    @Test
    void buildWriter_sqlType_usesNamedDataSource_whenConfigured() throws IOException {
        Files.writeString(tempDir.resolve("insert.sql"), "INSERT INTO T VALUES (:v)");
        when(sqlFileLoader.readRawSql(any(), any())).thenReturn("INSERT INTO T VALUES (:v)");
        when(beanFactory.getBean("secondaryDS", DataSource.class)).thenReturn(dataSource);

        SourceConfig config = new SourceConfig();
        config.setName("orders");
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory(tempDir.toString());
        wc.setSqlFile("insert.sql");
        wc.setDataSourceBean("secondaryDS");
        config.setWriter(wc);

        factory.buildWriter(config);

        verify(beanFactory).getBean("secondaryDS", DataSource.class);
    }

    // ── Case 1 : WriterConfig déclaratif — type JAVA ─────────────────────────

    @Test
    void buildWriter_javaType_returnsResolvedBean() {
        SourceConfig config = configWithJavaWriter("myWriter");
        when(applicationContext.containsBean("myWriter")).thenReturn(true);
        when(applicationContext.getBean("myWriter")).thenReturn(mockWriter);

        ItemWriter<GenericRecord> writer = factory.buildWriter(config);

        assertThat(writer).isSameAs(mockWriter);
    }

    @Test
    void buildWriter_javaType_beanNotFound_throwsIllegalState() {
        SourceConfig config = configWithJavaWriter("ghostWriter");
        when(applicationContext.containsBean("ghostWriter")).thenReturn(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.buildWriter(config));
    }

    @Test
    void buildWriter_javaType_beanNotItemWriter_throwsIllegalState() {
        SourceConfig config = configWithJavaWriter("badWriter");
        when(applicationContext.containsBean("badWriter")).thenReturn(true);
        when(applicationContext.getBean("badWriter")).thenReturn("not-a-writer");

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.buildWriter(config))
                .withMessageContaining("does not implement ItemWriter");
    }

    // ── Case 2 : convention {sourceName}Writer ────────────────────────────────

    @Test
    void buildWriter_noWriterConfig_conventionBeanFound() {
        SourceConfig config = sourceConfig("orders");
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.getBean("ordersWriter")).thenReturn(mockWriter);

        ItemWriter<GenericRecord> writer = factory.buildWriter(config);

        assertThat(writer).isSameAs(mockWriter);
    }

    @Test
    void buildWriter_noWriterConfig_noBeanFound_throwsIllegalState() {
        SourceConfig config = sourceConfig("orders");
        when(applicationContext.containsBean("ordersWriter")).thenReturn(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> factory.buildWriter(config))
                .withMessageContaining("orders");
    }

    @Test
    void buildWriter_hyphenatedSourceName_triesCamelCaseBean() {
        SourceConfig config = sourceConfig("exchange-rates");
        when(applicationContext.containsBean("exchange-ratesWriter")).thenReturn(false);
        when(applicationContext.containsBean("exchangeRatesWriter")).thenReturn(true);
        when(applicationContext.getBean("exchangeRatesWriter")).thenReturn(mockWriter);

        ItemWriter<GenericRecord> writer = factory.buildWriter(config);

        assertThat(writer).isSameAs(mockWriter);
    }

    @Test
    void buildWriter_multipleHyphens_correctCamelCase() {
        SourceConfig config = sourceConfig("a-b-c");
        when(applicationContext.containsBean("a-b-cWriter")).thenReturn(false);
        when(applicationContext.containsBean("aBCWriter")).thenReturn(true);
        when(applicationContext.getBean("aBCWriter")).thenReturn(mockWriter);

        ItemWriter<GenericRecord> writer = factory.buildWriter(config);

        assertThat(writer).isSameAs(mockWriter);
    }

    // ── Cas d'erreur ─────────────────────────────────────────────────────────

    @Test
    void buildWriter_nullConfig_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.buildWriter(null));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SourceConfig sourceConfig(String name) {
        SourceConfig sc = new SourceConfig();
        sc.setName(name);
        sc.setType("CSV");
        return sc;
    }

    private SourceConfig configWithSqlWriter(String sqlDir, String sqlFile) {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory(sqlDir);
        wc.setSqlFile(sqlFile);
        sc.setWriter(wc);
        return sc;
    }

    private SourceConfig configWithJavaWriter(String beanName) {
        SourceConfig sc = new SourceConfig();
        sc.setName("orders");
        WriterConfig wc = new WriterConfig();
        wc.setType("JAVA");
        wc.setBeanName(beanName);
        sc.setWriter(wc);
        return sc;
    }
}
