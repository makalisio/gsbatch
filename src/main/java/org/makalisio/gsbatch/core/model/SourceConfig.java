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

    // CSV-specific properties
    /**
     * File path for CSV sources
     */
    private String path;

    /**
     * CSV delimiter (default: ";")
     */
    private String delimiter = ";";

    /**
     * Whether to skip the header row (default: true)
     */
    private boolean skipHeader = true;

    /**
     * List of column configurations
     */
    private List<ColumnConfig> columns = new ArrayList<>();

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

        // CSV-specific validation
        if ("CSV".equalsIgnoreCase(type)) {
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("Path is required for CSV source: " + name);
            }
            if (columns == null || columns.isEmpty()) {
                throw new IllegalStateException("Columns configuration is required for CSV source: " + name);
            }
            // Validate each column
            for (int i = 0; i < columns.size(); i++) {
                ColumnConfig col = columns.get(i);
                if (col.getName() == null || col.getName().isBlank()) {
                    throw new IllegalStateException(
                        String.format("Column name is required at index %d for source: %s", i, name)
                    );
                }
            }
        }
        
        if (chunkSize != null && chunkSize <= 0) {
            throw new IllegalStateException("Chunk size must be positive for source: " + name);
        }
    }
}
