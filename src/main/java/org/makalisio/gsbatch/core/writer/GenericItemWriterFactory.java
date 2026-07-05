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
package org.makalisio.gsbatch.core.writer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.ExecutionType;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.makalisio.gsbatch.core.util.BeanNameResolver;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Factory for creating {@code ItemWriter} instances based on the YAML configuration.
 *
 * <h2>Writer resolution order</h2>
 * <ol>
 *   <li>If {@code writer.type=SQL} in the YAML → {@link SqlGenericItemWriter}</li>
 *   <li>If {@code writer.type=JAVA} in the YAML → bean named {@code writer.beanName}</li>
 *   <li>If {@code writer} is absent from the YAML → bean named {@code {sourceName}Writer}
 *       (legacy behaviour)</li>
 * </ol>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class GenericItemWriterFactory {

    private final ApplicationContext applicationContext;
    private final SqlFileLoader sqlFileLoader;
    private final DataSource defaultDataSource;
    private final BeanFactory beanFactory;
    private final MeterRegistry meterRegistry;

    /**
     * @param applicationContext Spring context for resolving JAVA beans
     * @param sqlFileLoader      SQL file loader
     * @param defaultDataSource  primary DataSource
     * @param beanFactory        for resolving named DataSources
     */
    public GenericItemWriterFactory(ApplicationContext applicationContext,
                                    SqlFileLoader sqlFileLoader,
                                    DataSource defaultDataSource,
                                    BeanFactory beanFactory) {
        this(applicationContext, sqlFileLoader, defaultDataSource, beanFactory, new SimpleMeterRegistry());
    }

    /**
     * @param applicationContext    Spring context for resolving JAVA beans
     * @param sqlFileLoader         SQL file loader
     * @param defaultDataSource     primary DataSource
     * @param beanFactory           for resolving named DataSources
     * @param meterRegistryProvider resolves the consumer app's {@link MeterRegistry} bean
     *                              if one exists, otherwise falls back to a private
     *                              in-memory registry - metrics never prevent startup
     */
    @Autowired
    public GenericItemWriterFactory(ApplicationContext applicationContext,
                                    SqlFileLoader sqlFileLoader,
                                    DataSource defaultDataSource,
                                    BeanFactory beanFactory,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(applicationContext, sqlFileLoader, defaultDataSource, beanFactory,
                meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    private GenericItemWriterFactory(ApplicationContext applicationContext,
                                    SqlFileLoader sqlFileLoader,
                                    DataSource defaultDataSource,
                                    BeanFactory beanFactory,
                                    MeterRegistry meterRegistry) {
        this.applicationContext = applicationContext;
        this.sqlFileLoader = sqlFileLoader;
        this.defaultDataSource = defaultDataSource;
        this.beanFactory = beanFactory;
        this.meterRegistry = meterRegistry;
        log.info("GenericItemWriterFactory initialized");
    }

    /**
     * Builds an {@code ItemWriter} according to the YAML configuration.
     *
     * @param config the source configuration
     * @return configured writer
     */
    public ItemWriter<GenericRecord> buildWriter(SourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }

        // ── Case 1: WriterConfig declared in the YAML ─────────────────────────
        if (config.hasWriterConfig()) {
            return buildFromWriterConfig(config);
        }

        // ── Case 2: legacy behaviour - bean "{sourceName}Writer" ──────────────
        return buildFromBeanConvention(config);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Case 1: declarative WriterConfig
    // ─────────────────────────────────────────────────────────────────────────

    private ItemWriter<GenericRecord> buildFromWriterConfig(SourceConfig config) {
        WriterConfig writerConfig = config.getWriter();
        String type = writerConfig.getType();
        log.debug("Source '{}' - declarative writer, type={}", config.getName(), type);

        ExecutionType executionType;
        try {
            executionType = ExecutionType.from(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid writer.type for source '" + config.getName() +
                            "': '" + type + "'. Accepted values: SQL, JAVA"
            );
        }

        return switch (executionType) {
            case SQL -> buildSqlWriter(config, writerConfig);
            case JAVA -> buildJavaWriter(config, writerConfig.getBeanName());
        };
    }

    /**
     * Builds a {@link SqlGenericItemWriter} from the SQL file.
     */
    private ItemWriter<GenericRecord> buildSqlWriter(SourceConfig config, WriterConfig writerConfig) {
        DataSource dataSource = resolveDataSource(writerConfig.getDataSourceBean(), config.getName());

        log.info("Source '{}' - SQL writer: {}/{}",
                config.getName(), writerConfig.getSqlDirectory(), writerConfig.getSqlFile());

        return new SqlGenericItemWriter(writerConfig, sqlFileLoader, dataSource, config.getName(), meterRegistry);
    }

    /**
     * Resolves a Java {@code ItemWriter} bean from the Spring context.
     */
    private ItemWriter<GenericRecord> buildJavaWriter(SourceConfig config, String beanName) {
        log.info("Source '{}' - JAVA writer bean: '{}'", config.getName(), beanName);
        return resolveWriterBean(beanName, config.getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Case 2: convention {sourceName}Writer (legacy behaviour)
    // ─────────────────────────────────────────────────────────────────────────

    private ItemWriter<GenericRecord> buildFromBeanConvention(SourceConfig config) {
        String sourceName = config.getName();
        String beanName = BeanNameResolver.resolve(applicationContext, sourceName, "Writer");

        log.debug("Source '{}' - convention-based writer, looking for bean '{}'", sourceName, beanName);
        return resolveWriterBean(beanName, sourceName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves an {@code ItemWriter} bean from the Spring context.
     */
    @SuppressWarnings("unchecked")
    private ItemWriter<GenericRecord> resolveWriterBean(String beanName, String sourceName) {
        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(String.format(
                    "No writer found for source '%s'.%n" +
                            "Option 1 (declarative): add a 'writer:' section in %s.yml%n" +
                            "Option 2 (convention): create a @Component(\"%s\") implementing ItemWriter<GenericRecord>",
                    sourceName, sourceName, beanName
            ));
        }

        Object bean = applicationContext.getBean(beanName);
        if (!(bean instanceof ItemWriter)) {
            throw new IllegalStateException(String.format(
                    "Bean '%s' does not implement ItemWriter. Actual type: %s",
                    beanName, bean.getClass().getName()
            ));
        }

        log.info("Source '{}' - writer bean '{}' resolved", sourceName, beanName);
        return (ItemWriter<GenericRecord>) bean;
    }

    /**
     * Resolves the DataSource (named or primary).
     */
    private DataSource resolveDataSource(String dataSourceBeanName, String sourceName) {
        if (dataSourceBeanName != null && !dataSourceBeanName.isBlank()) {
            log.debug("Source '{}' - named DataSource: '{}'", sourceName, dataSourceBeanName);
            return beanFactory.getBean(dataSourceBeanName, DataSource.class);
        }
        return defaultDataSource;
    }
}
