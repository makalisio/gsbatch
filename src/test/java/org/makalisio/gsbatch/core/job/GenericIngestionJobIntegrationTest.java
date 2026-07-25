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
package org.makalisio.gsbatch.core.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests of {@code genericIngestionJob} on H2:
 * real Spring context (component scan of the whole framework), real
 * {@code @JobScope}/{@code @StepScope} proxies, real job launch.
 *
 * <p>Covers the two writer resolution paths:</p>
 * <ul>
 *   <li>convention bean {@code {sourceName}Writer} — and asserts that the
 *       {@code ItemStream} lifecycle ({@code open}/{@code update}/{@code close})
 *       reaches the consumer writer through the step-scoped proxy (regression
 *       test for the {@code DelegatingItemStreamWriter} fix: with a plain
 *       {@code ItemWriter} bean type these callbacks silently never fired)</li>
 *   <li>declarative {@code writer.type=SQL} — rows really land in the table</li>
 * </ul>
 */
@SpringBatchTest
@SpringJUnitConfig(GenericIngestionJobIntegrationTest.TestConfig.class)
class GenericIngestionJobIntegrationTest {

    private static final Path CSV_PATH = Path.of("target", "it-data", "it-orders.csv");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private RecordingStreamWriter recordingWriter;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void writeCsvAndReset() throws IOException {
        Files.createDirectories(CSV_PATH.getParent());
        Files.writeString(CSV_PATH, """
            order_id;amount
            O-1;100.50
            O-2;200.00
            O-3;300.25
            """);
        recordingWriter.reset();
        new JdbcTemplate(dataSource).update("DELETE FROM IT_ORDERS");
    }

    private static JobParameters params(String sourceName) {
        return new JobParametersBuilder()
            .addString("sourceName", sourceName)
            .addString("run.id", UUID.randomUUID().toString())
            .toJobParameters();
    }

    @Test
    void conventionWriter_ReceivesItemsAndStreamLifecycle() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(params("it-csv-orders"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Items went through reader → (pass-through processor) → convention writer
        assertThat(recordingWriter.items).hasSize(3);
        assertThat(recordingWriter.items.get(0).getString("order_id")).isEqualTo("O-1");
        assertThat(recordingWriter.items.get(0).getString("amount")).isEqualTo("100.50");
        assertThat(recordingWriter.items.get(2).getString("order_id")).isEqualTo("O-3");

        // ItemStream lifecycle reached the consumer writer through the proxy
        assertThat(recordingWriter.opened).as("open() must reach the consumer writer").isTrue();
        assertThat(recordingWriter.updateCount).as("update() fires at each chunk commit").isGreaterThan(0);
        assertThat(recordingWriter.closed).as("close() must reach the consumer writer").isTrue();
    }

    @Test
    void declarativeSqlWriter_InsertsRowsIntoTable() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(params("it-csv-sqlwriter"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM IT_ORDERS", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM IT_ORDERS WHERE order_id = 'O-2'", String.class))
            .isEqualTo("200.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Test context
    // ─────────────────────────────────────────────────────────────────────────

    @Configuration
    @EnableBatchProcessing
    @ComponentScan(basePackages = "org.makalisio.gsbatch")
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
                .addScript("classpath:sql-it/it-schema.sql")
                .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        /**
         * In a Boot app the CacheManager comes from auto-configuration;
         * this plain Spring context must provide it for @EnableCaching
         * (YamlSourceConfigLoader's @Cacheable("sourceConfigs")).
         */
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("sourceConfigs");
        }

        /** Convention writer for sourceName=it-csv-orders. */
        @Bean("it-csv-ordersWriter")
        RecordingStreamWriter itCsvOrdersWriter() {
            return new RecordingStreamWriter();
        }
    }

    /**
     * Consumer-style writer implementing ItemStream, recording items and
     * lifecycle callbacks so the test can assert they actually fire.
     */
    static class RecordingStreamWriter implements ItemStreamWriter<GenericRecord> {

        final List<GenericRecord> items = new ArrayList<>();
        volatile boolean opened;
        volatile boolean closed;
        volatile int updateCount;

        void reset() {
            items.clear();
            opened = false;
            closed = false;
            updateCount = 0;
        }

        @Override
        public void open(ExecutionContext executionContext) throws ItemStreamException {
            opened = true;
        }

        @Override
        public void update(ExecutionContext executionContext) throws ItemStreamException {
            updateCount++;
        }

        @Override
        public void close() throws ItemStreamException {
            closed = true;
        }

        @Override
        public void write(Chunk<? extends GenericRecord> chunk) {
            chunk.forEach(items::add);
        }
    }
}
