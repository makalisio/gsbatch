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

    /** Chemin vers le fichier CSV */
    private String path;

    /** Délimiteur CSV (défaut : ";") */
    private String delimiter = ";";

    /** Ignorer la ligne d'en-tête (défaut : true) */
    private boolean skipHeader = true;

    /** Liste des colonnes */
    private List<ColumnConfig> columns = new ArrayList<>();

    // ── SQL ──────────────────────────────────────────────────────────────────

    /**
     * Répertoire contenant les fichiers SQL.
     * Peut être absolu (ex : /data/sql) ou relatif au classpath.
     * Exemple : /opt/batch/sql  ou  D:/work/sql
     */
    private String sqlDirectory;

    /**
     * Nom du fichier SQL dans le sqlDirectory.
     * Exemple : orders_new.sql
     *
     * Le fichier peut contenir des variables bindées de la forme :paramName
     * dont les valeurs sont transmises via les jobParameters.
     *
     * Exemple :
     *   SELECT * FROM ORDERS
     *   WHERE status = :status
     *     AND trade_date = :process_date
     *
     * Lancement : java -jar app.jar sourceName=orders status=NEW process_date=2024-01-15
     */
    private String sqlFile;

    /**
     * Nombre de lignes chargées par batch JDBC (fetchSize).
     * Impacte la mémoire et les performances. Défaut : 1000.
     */
    private Integer fetchSize;

    /**
     * Bean name de la DataSource à utiliser si plusieurs sources de données
     * sont déclarées dans le backoffice. Optionnel — utilise la DataSource
     * principale par défaut.
     */
    private String dataSourceBean;

    /**
     * Retourne le fetchSize, avec 1000 comme valeur par défaut.
     *
     * @return fetchSize effectif
     */
    public int getEffectiveFetchSize() {
        return fetchSize != null && fetchSize > 0 ? fetchSize : 1000;
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

        // ── Validation CSV ───────────────────────────────────────────────────
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

        // ── Validation SQL ───────────────────────────────────────────────────
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
    }
}
