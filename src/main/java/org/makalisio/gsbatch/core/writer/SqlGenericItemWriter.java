package org.makalisio.gsbatch.core.writer;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic writer driven by an external SQL file.
 *
 * <p>Loads the SQL once at construction time, then for each chunk
 * executes a {@code batchUpdate} using the {@link GenericRecord} fields
 * as bind variables.</p>
 *
 * <h2>Example SQL file</h2>
 * <pre>
 * INSERT INTO ORDERS_PROCESSED
 *   (order_id, customer_id, amount, tva_amount, total_ttc, currency, status)
 * VALUES
 *   (:order_id, :customer_id, :amount, :tva_amount, :total_ttc, :currency, :status)
 * </pre>
 *
 * <p>The bind variable names ({@code :paramName}) must match
 * the keys of the {@code GenericRecord} returned by the processor.</p>
 *
 * <h2>Error handling</h2>
 * <p>Error behaviour is configured in the YAML via
 * {@code writer.onError} (FAIL or SKIP) and managed by Spring Batch
 * at the Step level via {@code faultTolerant().skip().skipLimit()}.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class SqlGenericItemWriter implements ItemWriter<GenericRecord> {

    private final String rawSql;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final String sourceName;

    /**
     * @param writerConfig  writer configuration (sqlDirectory, sqlFile)
     * @param sqlFileLoader loader for reading the SQL file
     * @param dataSource    DataSource to use
     * @param sourceName    source name (for logging)
     */
    public SqlGenericItemWriter(WriterConfig writerConfig,
                                SqlFileLoader sqlFileLoader,
                                DataSource dataSource,
                                String sourceName) {
        this.sourceName = sourceName;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

        // Load SQL once – NamedParameterJdbcTemplate handles
        // :paramName substitution for each row in the chunk
        this.rawSql = sqlFileLoader.readRawSql(
                writerConfig.getSqlDirectory(),
                writerConfig.getSqlFile()
        );

        log.info("Source '{}' – SqlGenericItemWriter initialized from {}/{}",
                sourceName, writerConfig.getSqlDirectory(), writerConfig.getSqlFile());
        log.debug("Source '{}' – SQL writer: {}", sourceName, abbreviate(rawSql, 200));
    }

    /**
     * Writes a chunk of {@link GenericRecord} via a {@code batchUpdate}.
     *
     * <p>The fields of each {@code GenericRecord} are mapped to the SQL bind variables
     * ({@code :paramName} → {@code record.get("paramName")}).</p>
     *
     * <p>The {@code batchUpdate} sends all rows of the chunk in a single
     * JDBC round-trip, which is more efficient than individual INSERTs.</p>
     *
     * @param chunk the chunk of records to persist
     * @throws Exception on JDBC error (behaviour depends on {@code onError} in the YAML)
     */
    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        if (chunk.isEmpty()) {
            log.debug("Source '{}' – empty chunk, nothing to write", sourceName);
            return;
        }

        log.info("Source '{}' – writing {} record(s)", sourceName, chunk.size());

        // Build SqlParameterSource for each record in the chunk
        SqlParameterSource[] batchParams = buildBatchParams(chunk);

        // Execute batchUpdate – single JDBC round-trip for the whole chunk
        int[] rowCounts = namedJdbcTemplate.batchUpdate(rawSql, batchParams);

        // Count affected rows
        int totalAffected = 0;
        int skipped = 0;
        for (int count : rowCounts) {
            if (count >= 0) {
                totalAffected += count;
            } else {
                skipped++; // SUCCESS_NO_INFO (-2) or EXECUTE_FAILED (-3)
            }
        }

        log.info("Source '{}' – chunk written: {} row(s) affected, {} with no info",
                sourceName, totalAffected, skipped);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the {@code SqlParameterSource} array for the {@code batchUpdate}.
     * Each record is converted to a {@code MapSqlParameterSource} with all its fields.
     */
    private SqlParameterSource[] buildBatchParams(Chunk<? extends GenericRecord> chunk) {
        List<SqlParameterSource> params = new ArrayList<>(chunk.size());

        for (GenericRecord record : chunk) {
            // getValues() returns an immutable Map<String, Object>
            // MapSqlParameterSource accepts a Map directly
            MapSqlParameterSource paramSource = new MapSqlParameterSource(record.getValues());
            params.add(paramSource);
        }

        return params.toArray(new SqlParameterSource[0]);
    }

    /**
     * Truncates a string for logging purposes.
     */
    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
