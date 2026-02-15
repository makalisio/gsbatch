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
 * Builder for SQL readers based on external {@code .sql} files.
 *
 * <h2>Execution flow</h2>
 * <pre>
 *   orders.yml
 *     sqlDirectory: /opt/sql
 *     sqlFile: orders.sql         ──► SqlFileLoader.load()
 *                                         │
 *                                         ├─ reads orders.sql
 *                                         ├─ parses :variables
 *                                         ├─ resolves from jobParameters
 *                                         └─ returns SQL(?) + PreparedStatementSetter
 *                                                     │
 *                                         JdbcCursorItemReader
 *                                            .sql(executableSql)
 *                                            .preparedStatementSetter(setter)
 * </pre>
 *
 * <h2>Example SQL file</h2>
 * <pre>
 *   SELECT order_id, customer_id, amount, currency
 *   FROM   ORDERS
 *   WHERE  status     = :status
 *     AND  trade_date = :process_date
 *   ORDER BY order_id
 * </pre>
 *
 * <h2>Launch</h2>
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
     * @param sqlFileLoader     SQL file loader with bind variable resolution
     * @param defaultDataSource primary DataSource (auto-configured by Spring Boot)
     * @param beanFactory       for resolving named DataSources (multi-DB)
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
     * Builds a {@link JdbcCursorItemReader} for a SQL source.
     *
     * <p>The SQL is read from the file {@code sqlDirectory/sqlFile} defined in the YAML.
     * Variables {@code :paramName} are resolved from {@code jobParameters}.</p>
     *
     * @param config        the YAML source configuration
     * @param jobParameters all job parameters (contains bind variable values)
     * @return JDBC reader configured and ready for Spring Batch
     * @throws SqlFileLoader.SqlFileException if the file is not found or a parameter is missing
     */
    public JdbcCursorItemReader<GenericRecord> build(SourceConfig config,
                                                      Map<String, Object> jobParameters) {
        log.info("Building SQL reader for source '{}' - file: {}/{}",
                config.getName(), config.getSqlDirectory(), config.getSqlFile());

        // ── 1. Load SQL + resolve bind variables ─────────────────────────────
        SqlFileLoader.LoadedSql loadedSql = sqlFileLoader.load(config, jobParameters);

        log.info("Source '{}' - SQL ready, {} bind variable(s): {}",
                config.getName(),
                loadedSql.getParameterNames().size(),
                loadedSql.getParameterNames());

        // ── 2. Resolve DataSource ─────────────────────────────────────────────
        DataSource dataSource = resolveDataSource(config);

        // ── 3. Build RowMapper ────────────────────────────────────────────────
        RowMapper<GenericRecord> rowMapper = buildRowMapper(config);

        // ── 4. Assemble JdbcCursorItemReader ──────────────────────────────────
        JdbcCursorItemReaderBuilder<GenericRecord> builder =
                new JdbcCursorItemReaderBuilder<GenericRecord>()
                        .name("sqlReader-" + config.getName())
                        .dataSource(dataSource)
                        .sql(loadedSql.getExecutableSql())
                        .fetchSize(config.getEffectiveFetchSize())
                        .rowMapper(rowMapper);

        // Only add setter if there are bound parameters
        if (!loadedSql.getParameterNames().isEmpty()) {
            builder.preparedStatementSetter(loadedSql.getPreparedStatementSetter());
        }

        log.debug("Source '{}' - JdbcCursorItemReader built (fetchSize={})",
                config.getName(), config.getEffectiveFetchSize());

        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Row Mappers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selects the appropriate RowMapper based on whether columns are declared in the YAML.
     * Explicit mapping (columns declared) = recommended in production.
     * Auto mapping (no columns) = convenient for prototyping.
     */
    private RowMapper<GenericRecord> buildRowMapper(SourceConfig config) {
        boolean hasExplicitColumns = config.getColumns() != null && !config.getColumns().isEmpty();
        if (hasExplicitColumns) {
            log.debug("Source '{}' - explicit mapping ({} columns)",
                    config.getName(), config.getColumns().size());
            return new ExplicitColumnRowMapper(config.getColumns());
        }
        log.debug("Source '{}' - automatic mapping (JDBC metadata)", config.getName());
        return new AutoColumnRowMapper();
    }

    /**
     * Explicit mapping: reads only the columns declared in the YAML.
     * Advantage: independent of undeclared schema changes.
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
                    // Column absent from ResultSet → null instead of crash
                    record.put(name, null);
                }
            }
            return record;
        }
    }

    /**
     * Automatic mapping: reads all ResultSet columns via JDBC metadata.
     * Uses COLUMN_LABEL (SQL alias) in priority over COLUMN_NAME.
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
     * Resolves the DataSource: uses the named bean if {@code dataSourceBean}
     * is defined in the YAML, otherwise uses the primary DataSource.
     */
    private DataSource resolveDataSource(SourceConfig config) {
        String beanName = config.getDataSourceBean();
        if (beanName != null && !beanName.isBlank()) {
            log.debug("Source '{}' - named DataSource: '{}'", config.getName(), beanName);
            return beanFactory.getBean(beanName, DataSource.class);
        }
        log.debug("Source '{}' - primary DataSource", config.getName());
        return defaultDataSource;
    }
}
