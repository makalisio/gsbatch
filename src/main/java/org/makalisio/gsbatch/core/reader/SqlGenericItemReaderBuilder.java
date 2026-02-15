package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.ColumnConfig;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Builder pour les readers SQL basés sur des fichiers {@code .sql} externes.
 *
 * <h2>Flux d'exécution</h2>
 * <pre>
 *   orders.yml
 *     sqlDirectory: /opt/sql
 *     sqlFile: orders.sql         ──► SqlFileLoader.load()
 *                                         │
 *                                         ├─ lit orders.sql
 *                                         ├─ parse les :variables
 *                                         ├─ résout depuis jobParameters
 *                                         └─ retourne SQL(?) + PreparedStatementSetter
 *                                                     │
 *                                         JdbcCursorItemReader
 *                                            .sql(executableSql)
 *                                            .preparedStatementSetter(setter)
 * </pre>
 *
 * <h2>Exemple de fichier SQL</h2>
 * <pre>
 *   SELECT order_id, customer_id, amount, currency
 *   FROM   ORDERS
 *   WHERE  status     = :status
 *     AND  trade_date = :process_date
 *   ORDER BY order_id
 * </pre>
 *
 * <h2>Lancement</h2>
 * <pre>
 *   java -jar app.jar sourceName=orders status=NEW process_date=2024-01-15
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class SqlGenericItemReaderBuilder {

    private final SqlFileLoader sqlFileLoader;
    private final DataSource defaultDataSource;
    private final BeanFactory beanFactory;

    /**
     * @param sqlFileLoader     loader de fichiers SQL avec résolution des bind variables
     * @param defaultDataSource DataSource principale (auto-configurée par Spring Boot)
     * @param beanFactory       pour résoudre les DataSource nommées (multi-DB)
     */
    public SqlGenericItemReaderBuilder(SqlFileLoader sqlFileLoader,
                                       DataSource defaultDataSource,
                                       BeanFactory beanFactory) {
        this.sqlFileLoader = sqlFileLoader;
        this.defaultDataSource = defaultDataSource;
        this.beanFactory = beanFactory;
        log.info("SqlGenericItemReaderBuilder initialized");
    }

    /**
     * Construit un {@link JdbcCursorItemReader} pour une source SQL.
     *
     * <p>Le SQL est lu depuis le fichier {@code sqlDirectory/sqlFile} défini dans le YAML.
     * Les variables {@code :paramName} sont résolues depuis les {@code jobParameters}.</p>
     *
     * @param config        la configuration YAML de la source
     * @param jobParameters tous les paramètres du job (contient les valeurs des bind variables)
     * @return reader JDBC configuré et prêt pour Spring Batch
     * @throws SqlFileLoader.SqlFileException si le fichier est introuvable ou un paramètre manque
     */
    public JdbcCursorItemReader<GenericRecord> build(SourceConfig config,
                                                      Map<String, Object> jobParameters) {
        log.info("Building SQL reader for source '{}' - file: {}/{}",
                config.getName(), config.getSqlDirectory(), config.getSqlFile());

        // ── 1. Charger le SQL + résoudre les bind variables ──────────────────
        SqlFileLoader.LoadedSql loadedSql = sqlFileLoader.load(config, jobParameters);

        log.info("Source '{}' - SQL prêt, {} bind variable(s) : {}",
                config.getName(),
                loadedSql.getParameterNames().size(),
                loadedSql.getParameterNames());

        // ── 2. Résoudre la DataSource ────────────────────────────────────────
        DataSource dataSource = resolveDataSource(config);

        // ── 3. Construire le RowMapper ───────────────────────────────────────
        RowMapper<GenericRecord> rowMapper = buildRowMapper(config);

        // ── 4. Assembler le JdbcCursorItemReader ─────────────────────────────
        JdbcCursorItemReaderBuilder<GenericRecord> builder =
                new JdbcCursorItemReaderBuilder<GenericRecord>()
                        .name("sqlReader-" + config.getName())
                        .dataSource(dataSource)
                        .sql(loadedSql.getExecutableSql())
                        .fetchSize(config.getEffectiveFetchSize())
                        .rowMapper(rowMapper);

        // N'ajouter le setter que s'il y a des paramètres bindés
        if (!loadedSql.getParameterNames().isEmpty()) {
            builder.preparedStatementSetter(loadedSql.getPreparedStatementSetter());
        }

        log.debug("Source '{}' - JdbcCursorItemReader construit (fetchSize={})",
                config.getName(), config.getEffectiveFetchSize());

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Row Mappers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Choisit le RowMapper adapté selon la présence de colonnes dans le YAML.
     * Mapping explicite (colonnes déclarées) = recommandé en production.
     * Mapping auto (colonnes absentes) = pratique pour le prototypage.
     */
    private RowMapper<GenericRecord> buildRowMapper(SourceConfig config) {
        boolean hasExplicitColumns = config.getColumns() != null && !config.getColumns().isEmpty();
        if (hasExplicitColumns) {
            log.debug("Source '{}' - mapping explicite ({} colonnes)",
                    config.getName(), config.getColumns().size());
            return new ExplicitColumnRowMapper(config.getColumns());
        }
        log.debug("Source '{}' - mapping automatique (métadonnées JDBC)", config.getName());
        return new AutoColumnRowMapper();
    }

    /**
     * Mapping explicite : ne lit que les colonnes déclarées dans le YAML.
     * Avantage : indépendant des changements de schéma non listés.
     */
    private static class ExplicitColumnRowMapper implements RowMapper<GenericRecord> {
        private final List<ColumnConfig> columns;

        ExplicitColumnRowMapper(List<ColumnConfig> columns) {
            this.columns = columns;
        }

        @Override
        public GenericRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            GenericRecord record = new GenericRecord();
            for (ColumnConfig col : columns) {
                String name = col.getName();
                try {
                    record.put(name, rs.getObject(name));
                } catch (SQLException e) {
                    // Colonne absente du ResultSet → null plutôt que crash
                    record.put(name, null);
                }
            }
            return record;
        }
    }

    /**
     * Mapping automatique : lit toutes les colonnes du ResultSet via les métadonnées JDBC.
     * Utilise le COLUMN_LABEL (alias SQL) en priorité sur le COLUMN_NAME.
     */
    private static class AutoColumnRowMapper implements RowMapper<GenericRecord> {
        @Override
        public GenericRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            GenericRecord record = new GenericRecord();
            ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            for (int i = 1; i <= count; i++) {
                String label = meta.getColumnLabel(i);
                String name = (label != null && !label.isBlank()) ? label : meta.getColumnName(i);
                record.put(name, rs.getObject(i));
            }
            return record;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Résout la DataSource : utilise le bean nommé si {@code dataSourceBean}
     * est défini dans le YAML, sinon la DataSource principale.
     */
    private DataSource resolveDataSource(SourceConfig config) {
        String beanName = config.getDataSourceBean();
        if (beanName != null && !beanName.isBlank()) {
            log.debug("Source '{}' - DataSource nommée : '{}'", config.getName(), beanName);
            return beanFactory.getBean(beanName, DataSource.class);
        }
        log.debug("Source '{}' - DataSource principale", config.getName());
        return defaultDataSource;
    }
}
