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
     * JsonPath expression for extracting this field from JSON (REST sources only).
     * 
     * <p>Used when the JSON key differs from the column name or when extracting nested values.</p>
     * 
     * <p>Examples:
     * <ul>
     *   <li>{@code $.orderId} - Simple field mapping (JSON key is 'orderId')</li>
     *   <li>{@code $.customer.id} - Nested field extraction</li>
     *   <li>{@code $.pricing.totalAmount} - Deeply nested field</li>
     * </ul>
     * </p>
     * 
     * <p>If not specified, direct mapping is used (JSON key = column name).</p>
     */
    private String jsonPath;

    /**
     * XPath expression for extracting this field from XML (SOAP sources only).
     * 
     * <p>Used to extract values from SOAP/XML responses.</p>
     * 
     * <p>Examples:
     * <ul>
     *   <li>{@code ./tradeId/text()} - Text content of &lt;tradeId&gt; element</li>
     *   <li>{@code ./counterparty/name/text()} - Nested element</li>
     *   <li>{@code ./@id} - Attribute value</li>
     * </ul>
     * </p>
     * 
     * <p>XPath is evaluated relative to each item node extracted by soap.dataPath.</p>
     */
    private String xpath;

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
