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
package org.makalisio.gsbatch.core.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.makalisio.gsbatch.core.config.YamlSourceConfigLoader;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceResolutionDiagnosticsTest {

    @Mock YamlSourceConfigLoader configLoader;
    @Mock ApplicationContext applicationContext;

    private SourceResolutionDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        diagnostics = new SourceResolutionDiagnostics(configLoader, applicationContext);
    }

    private SourceConfig config(String name, String type) {
        SourceConfig config = new SourceConfig();
        config.setName(name);
        config.setType(type);
        return config;
    }

    private void stub(SourceConfig config) {
        when(configLoader.load(config.getName())).thenReturn(config);
    }

    // ── reader ────────────────────────────────────────────────────────────────

    @Test
    void describe_csvType_resolvesToCsvBuilder() {
        SourceConfig config = config("orders", "CSV");
        stub(config);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.reader().resolvable()).isTrue();
        assertThat(report.reader().description()).contains("CsvGenericItemReaderBuilder");
    }

    @Test
    void describe_restType_resolvesToRestBuilder() {
        SourceConfig config = config("api", "REST");
        stub(config);

        SourceResolutionReport report = diagnostics.describe("api");

        assertThat(report.reader().resolvable()).isTrue();
        assertThat(report.reader().description()).contains("RestGenericItemReaderBuilder");
    }

    @Test
    void describe_reservedUnimplementedType_isUnresolvable() {
        SourceConfig config = config("future", "JSON");
        stub(config);

        SourceResolutionReport report = diagnostics.describe("future");

        assertThat(report.reader().resolvable()).isFalse();
        assertThat(report.reader().description()).contains("not yet implemented");
    }

    @Test
    void describe_invalidType_isUnresolvable() {
        SourceConfig config = config("bogus", "EXCEL");
        stub(config);

        SourceResolutionReport report = diagnostics.describe("bogus");

        assertThat(report.reader().resolvable()).isFalse();
    }

    // ── processor ─────────────────────────────────────────────────────────────

    @Test
    void describe_noProcessorBean_resolvesToPassThrough() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.processor().resolvable()).isTrue();
        assertThat(report.processor().description()).contains("pass-through");
    }

    @Test
    void describe_processorBeanWrongType_isUnresolvable() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersProcessor", ItemProcessor.class)).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.processor().resolvable()).isFalse();
        assertThat(report.processor().description()).contains("does not implement ItemProcessor");
    }

    // ── writer ────────────────────────────────────────────────────────────────

    @Test
    void describe_declarativeSqlWriter_resolvesWithPath() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        WriterConfig writer = new WriterConfig();
        writer.setType("SQL");
        writer.setSqlDirectory("/opt/sql");
        writer.setSqlFile("insert_orders.sql");
        config.setWriter(writer);
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.writer().resolvable()).isTrue();
        assertThat(report.writer().description()).contains("/opt/sql/insert_orders.sql");
    }

    @Test
    void describe_declarativeJavaWriter_missingBean_isUnresolvable() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        WriterConfig writer = new WriterConfig();
        writer.setType("JAVA");
        writer.setBeanName("customWriter");
        config.setWriter(writer);
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("customWriter")).thenReturn(false);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.writer().resolvable()).isFalse();
        assertThat(report.writer().description()).contains("customWriter");
    }

    @Test
    void describe_conventionWriter_beanFound_resolves() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.writer().resolvable()).isTrue();
        assertThat(report.writer().description()).contains("ordersWriter");
    }

    @Test
    void describe_conventionWriter_noBean_isUnresolvable() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(false);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.writer().resolvable()).isFalse();
        assertThat(report.isFullyResolvable()).isFalse();
    }

    // ── pre/post-processing ──────────────────────────────────────────────────

    @Test
    void describe_disabledSteps_resolveAsNoOp() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.preprocessing().resolvable()).isTrue();
        assertThat(report.preprocessing().description()).contains("disabled");
        assertThat(report.postprocessing().description()).contains("disabled");
        assertThat(report.isFullyResolvable()).isTrue();
    }

    @Test
    void describe_javaPreprocessing_missingTaskletBean_isUnresolvable() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        config.getPreprocessing().setEnabled(true);
        config.getPreprocessing().setType("JAVA");
        config.getPreprocessing().setBeanName("ordersPreTasklet");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);
        when(applicationContext.containsBean("ordersPreTasklet")).thenReturn(false);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.preprocessing().resolvable()).isFalse();
        assertThat(report.preprocessing().description()).contains("ordersPreTasklet");
        assertThat(report.isFullyResolvable()).isFalse();
    }

    @Test
    void describe_javaPostprocessing_beanFoundCorrectType_resolves() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        config.getPostprocessing().setEnabled(true);
        config.getPostprocessing().setType("JAVA");
        config.getPostprocessing().setBeanName("ordersPostTasklet");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);
        when(applicationContext.containsBean("ordersPostTasklet")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersPostTasklet", Tasklet.class)).thenReturn(true);

        SourceResolutionReport report = diagnostics.describe("orders");

        assertThat(report.postprocessing().resolvable()).isTrue();
        assertThat(report.postprocessing().description()).contains("ordersPostTasklet");
    }

    // ── format() ──────────────────────────────────────────────────────────────

    @Test
    void format_includesSourceNameAndAllSections() {
        SourceConfig config = config("orders", "SQL");
        config.setSqlDirectory("/sql");
        config.setSqlFile("orders.sql");
        stub(config);
        when(applicationContext.containsBean("ordersProcessor")).thenReturn(false);
        when(applicationContext.containsBean("ordersWriter")).thenReturn(true);
        when(applicationContext.isTypeMatch("ordersWriter", ItemWriter.class)).thenReturn(true);

        String formatted = diagnostics.describe("orders").format();

        assertThat(formatted)
                .contains("orders")
                .contains("reader:")
                .contains("processor:")
                .contains("writer:")
                .contains("preprocessing:")
                .contains("postprocessing:");
    }
}
