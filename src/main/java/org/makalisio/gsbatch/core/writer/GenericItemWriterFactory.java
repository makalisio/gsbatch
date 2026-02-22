package org.makalisio.gsbatch.core.writer;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.BeanFactory;
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
        this.applicationContext = applicationContext;
        this.sqlFileLoader = sqlFileLoader;
        this.defaultDataSource = defaultDataSource;
        this.beanFactory = beanFactory;
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

        if ("SQL".equalsIgnoreCase(type)) {
            return buildSqlWriter(config, writerConfig);
        } else if ("JAVA".equalsIgnoreCase(type)) {
            return buildJavaWriter(config, writerConfig.getBeanName());
        } else {
            throw new IllegalStateException(
                    "Invalid writer.type for source '" + config.getName() +
                            "': '" + type + "'. Accepted values: SQL, JAVA"
            );
        }
    }

    /**
     * Builds a {@link SqlGenericItemWriter} from the SQL file.
     */
    private ItemWriter<GenericRecord> buildSqlWriter(SourceConfig config, WriterConfig writerConfig) {
        DataSource dataSource = resolveDataSource(writerConfig.getDataSourceBean(), config.getName());

        log.info("Source '{}' - SQL writer: {}/{}",
                config.getName(), writerConfig.getSqlDirectory(), writerConfig.getSqlFile());

        return new SqlGenericItemWriter(writerConfig, sqlFileLoader, dataSource, config.getName());
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
        String beanName = sourceName + "Writer";

        // Try direct name first (e.g., "calculator-soapWriter")
        if (applicationContext.containsBean(beanName)) {
            log.debug("Source '{}' - convention-based writer, using bean '{}'", sourceName, beanName);
            return resolveWriterBean(beanName, sourceName);
        }

        // Fallback: try camelCase (e.g., "calculatorSoapWriter") when sourceName contains hyphens
        if (sourceName.contains("-")) {
            String camelCaseBeanName = toCamelCase(sourceName) + "Writer";
            if (applicationContext.containsBean(camelCaseBeanName)) {
                log.debug("Source '{}' - convention-based writer, using camelCase bean '{}'",
                        sourceName, camelCaseBeanName);
                return resolveWriterBean(camelCaseBeanName, sourceName);
            }
        }

        // Neither found — delegate to resolveWriterBean which throws a descriptive error
        log.debug("Source '{}' - convention-based writer, looking for bean '{}'", sourceName, beanName);
        return resolveWriterBean(beanName, sourceName);
    }

    /**
     * Converts a hyphenated name to lowerCamelCase.
     * Example: "exchange-rates-frankfurter" → "exchangeRatesFrankfurter"
     */
    private static String toCamelCase(String hyphenated) {
        String[] parts = hyphenated.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
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
