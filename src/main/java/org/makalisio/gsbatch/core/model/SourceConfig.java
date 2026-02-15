// org.makalisio.gsbatch.core.model.SourceConfig
package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration model for a data source.
 * Loaded from YAML files in the ingestion/ directory.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@ToString
public class SourceConfig {

    /**
     * Name of the source (e.g., "trades", "orders")
     */
    private String name;

    /**
     * Type of source: CSV, SQL, JSON, etc.
     */
    private String type;

    /**
     * Chunk size for batch processing (default: 1000)
     */
    private Integer chunkSize;

    // ── CSV ──────────────────────────────────────────────────────────────────

    /** Path to the CSV file */
    private String path;

    /** CSV delimiter (default: ";") */
    private String delimiter = ";";

    /** Skip the header line (default: true) */
    private boolean skipHeader = true;

    /** List of columns */
    private List<ColumnConfig> columns = new ArrayList<>();

    // ── SQL ──────────────────────────────────────────────────────────────────

    /**
     * Directory containing the SQL files.
     * Can be absolute (e.g.: /data/sql) or relative to the classpath.
     * Example: /opt/batch/sql  or  D:/work/sql
     */
    private String sqlDirectory;

    /**
     * Name of the SQL file in the sqlDirectory.
     * Example: orders_new.sql
     *
     * The file may contain bind variables of the form :paramName
     * whose values are passed via jobParameters.
     *
     * Example:
     *   SELECT * FROM ORDERS
     *   WHERE status = :status
     *     AND trade_date = :process_date
     *
     * Launch: java -jar app.jar sourceName=orders status=NEW process_date=2024-01-15
     */
    private String sqlFile;

    /**
     * Number of rows loaded per JDBC batch (fetchSize).
     * Impacts memory and performance. Default: 1000.
     */
    private Integer fetchSize;

    /**
     * Bean name of the DataSource to use when multiple data sources
     * are declared in the backoffice. Optional — uses the primary DataSource
     * by default.
     */
    private String dataSourceBean;

    /**
     * Returns the fetchSize, with 1000 as the default value.
     *
     * @return effective fetchSize
     */
    public int getEffectiveFetchSize() {
        return fetchSize != null && fetchSize > 0 ? fetchSize : 1000;
    }

    // ── PRE/POST PROCESSING STEPS ─────────────────────────────────────────────

    /**
     * Pre-processing step configuration (optional).
     * Executed before the read/write chunk step.
     */
    private StepConfig preprocessing = new StepConfig();

    /**
     * Post-processing step configuration (optional).
     * Executed after the read/write chunk step.
     */
    private StepConfig postprocessing = new StepConfig();

    // ── GENERIC WRITER ────────────────────────────────────────────────────────

    /**
     * Writer configuration.
     * If absent, the framework looks for a bean named "{sourceName}Writer" in the Spring context.
     */
    private WriterConfig writer;

    /**
     * Indicates whether the writer is declaratively configured in the YAML.
     *
     * @return {@code true} if {@code writer} is defined
     */
    public boolean hasWriterConfig() {
        return writer != null;
    }

    /**
     * Gets the chunk size, returning 1000 if not configured.
     *
     * @return the chunk size
     */
    public Integer getChunkSize() {
        return chunkSize != null && chunkSize > 0 ? chunkSize : 1000;
    }

    /**
     * Extracts column names as a String array.
     *
     * @return array of column names
     */
    public String[] getColumnNames() {
        if (columns == null || columns.isEmpty()) {
            return new String[0];
        }
        return columns.stream()
                .map(ColumnConfig::getName)
                .toArray(String[]::new);
    }

    /**
     * Validates the configuration.
     *
     * @throws IllegalStateException if validation fails
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Source name is required");
        }
        
        if (type == null || type.isBlank()) {
            throw new IllegalStateException("Source type is required for source: " + name);
        }

        // ── CSV validation ────────────────────────────────────────────────────
        if ("CSV".equalsIgnoreCase(type)) {
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("Path is required for CSV source: " + name);
            }
            if (columns == null || columns.isEmpty()) {
                throw new IllegalStateException("Columns configuration is required for CSV source: " + name);
            }
            for (int i = 0; i < columns.size(); i++) {
                ColumnConfig col = columns.get(i);
                if (col.getName() == null || col.getName().isBlank()) {
                    throw new IllegalStateException(
                        String.format("Column name is required at index %d for source: %s", i, name)
                    );
                }
            }
        }

        // ── SQL validation ────────────────────────────────────────────────────
        if ("SQL".equalsIgnoreCase(type)) {
            if (sqlDirectory == null || sqlDirectory.isBlank()) {
                throw new IllegalStateException("sqlDirectory is required for SQL source: " + name);
            }
            if (sqlFile == null || sqlFile.isBlank()) {
                throw new IllegalStateException("sqlFile is required for SQL source: " + name);
            }
        }
        
        if (chunkSize != null && chunkSize <= 0) {
            throw new IllegalStateException("Chunk size must be positive for source: " + name);
        }

        // ── Pre/post processing validation ────────────────────────────────────
        if (preprocessing != null) {
            preprocessing.validate("preprocessing");
        }
        if (postprocessing != null) {
            postprocessing.validate("postprocessing");
        }

        // ── Declarative writer validation ─────────────────────────────────────
        if (writer != null) {
            writer.validate();
        }
    }
}
