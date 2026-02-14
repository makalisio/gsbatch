// org.makalisio.gsbatch.core.model.ColumnConfig
package org.makalisio.gsbatch.core.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Configuration for a single column in a data source.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ColumnConfig {

    /**
     * Column name
     */
    private String name;

    /**
     * Column data type: STRING, INTEGER, DECIMAL, DATE, BOOLEAN, etc.
     */
    private String type;

    /**
     * Optional format string for dates or numbers
     * Examples: "yyyy-MM-dd" for dates, "#,##0.00" for decimals
     */
    private String format;

    /**
     * Whether this column is required (not null)
     */
    private boolean required = false;

    /**
     * Default value if the column is missing or empty
     */
    private String defaultValue;

    /**
     * Validates the column configuration.
     *
     * @throws IllegalStateException if validation fails
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Column name is required");
        }
    }
}
