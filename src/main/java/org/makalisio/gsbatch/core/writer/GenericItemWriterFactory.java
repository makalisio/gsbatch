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
 * Factory pour creer les {@code ItemWriter} en fonction de la configuration YAML.
 *
 * <h2>Ordre de resolution du writer</h2>
 * <ol>
 *   <li>Si {@code writer.type=SQL} dans le YAML → {@link SqlGenericItemWriter}</li>
 *   <li>Si {@code writer.type=JAVA} dans le YAML → bean nomme {@code writer.beanName}</li>
 *   <li>Si {@code writer} absent du YAML → bean nomme {@code {sourceName}Writer}
 *       (comportement historique)</li>
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
     * @param applicationContext contexte Spring pour resoudre les beans JAVA
     * @param sqlFileLoader      loader de fichiers SQL
     * @param defaultDataSource  DataSource principale
     * @param beanFactory        pour resoudre les DataSource nommees
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
     * Construit un {@code ItemWriter} selon la configuration YAML.
     *
     * @param config la configuration de la source
     * @return writer configure
     */
    public ItemWriter<GenericRecord> buildWriter(SourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }

        // ── Cas 1 : WriterConfig declare dans le YAML ────────────────────────
        if (config.hasWriterConfig()) {
            return buildFromWriterConfig(config);
        }

        // ── Cas 2 : comportement historique  - bean "{sourceName}Writer" ──────
        return buildFromBeanConvention(config);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cas 1 : WriterConfig declaratif
    // ─────────────────────────────────────────────────────────────────────────

    private ItemWriter<GenericRecord> buildFromWriterConfig(SourceConfig config) {
        WriterConfig writerConfig = config.getWriter();
        String type = writerConfig.getType();
        log.debug("Source '{}'  - writer declaratif, type={}", config.getName(), type);

        if ("SQL".equalsIgnoreCase(type)) {
            return buildSqlWriter(config, writerConfig);
        } else if ("JAVA".equalsIgnoreCase(type)) {
            return buildJavaWriter(config, writerConfig.getBeanName());
        } else {
            throw new IllegalStateException(
                    "writer.type invalide pour la source '" + config.getName() +
                            "' : '" + type + "'. Valeurs acceptees : SQL, JAVA"
            );
        }
    }

    /**
     * Construit un {@link SqlGenericItemWriter} a partir du fichier SQL.
     */
    private ItemWriter<GenericRecord> buildSqlWriter(SourceConfig config, WriterConfig writerConfig) {
        DataSource dataSource = resolveDataSource(writerConfig.getDataSourceBean(), config.getName());

        log.info("Source '{}'  - writer SQL : {}/{}",
                config.getName(), writerConfig.getSqlDirectory(), writerConfig.getSqlFile());

        return new SqlGenericItemWriter(writerConfig, sqlFileLoader, dataSource, config.getName());
    }

    /**
     * Resout un bean Java {@code ItemWriter} depuis le contexte Spring.
     */
    private ItemWriter<GenericRecord> buildJavaWriter(SourceConfig config, String beanName) {
        log.info("Source '{}'  - writer JAVA bean : '{}'", config.getName(), beanName);
        return resolveWriterBean(beanName, config.getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cas 2 : Convention {sourceName}Writer (comportement historique)
    // ─────────────────────────────────────────────────────────────────────────

    private ItemWriter<GenericRecord> buildFromBeanConvention(SourceConfig config) {
        String beanName = config.getName() + "Writer";
        log.debug("Source '{}'  - writer par convention, recherche bean '{}'",
                config.getName(), beanName);
        return resolveWriterBean(beanName, config.getName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resout un bean {@code ItemWriter} depuis le contexte Spring.
     */
    @SuppressWarnings("unchecked")
    private ItemWriter<GenericRecord> resolveWriterBean(String beanName, String sourceName) {
        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(String.format(
                    "Aucun writer trouve pour la source '%s'.%n" +
                            "Option 1 (declaratif) : ajoutez une section 'writer:' dans %s.yml%n" +
                            "Option 2 (convention) : creez un @Component(\"%s\") implementant ItemWriter<GenericRecord>",
                    sourceName, sourceName, beanName
            ));
        }

        Object bean = applicationContext.getBean(beanName);
        if (!(bean instanceof ItemWriter)) {
            throw new IllegalStateException(String.format(
                    "Le bean '%s' n'implemente pas ItemWriter. Type reel : %s",
                    beanName, bean.getClass().getName()
            ));
        }

        log.info("Source '{}'  - writer bean '{}' resolu", sourceName, beanName);
        return (ItemWriter<GenericRecord>) bean;
    }

    /**
     * Resout la DataSource (nommee ou principale).
     */
    private DataSource resolveDataSource(String dataSourceBeanName, String sourceName) {
        if (dataSourceBeanName != null && !dataSourceBeanName.isBlank()) {
            log.debug("Source '{}'  - DataSource nommee : '{}'", sourceName, dataSourceBeanName);
            return beanFactory.getBean(dataSourceBeanName, DataSource.class);
        }
        return defaultDataSource;
    }
}