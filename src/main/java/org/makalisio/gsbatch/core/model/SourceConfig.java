/*
 * Copyright 2026 Makalisio Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

    /** Delimiteur CSV (defaut : ";") */
    private String delimiter = ";";

    /** Ignorer la ligne d'en-tete (defaut : true) */
    private boolean skipHeader = true;

    /** Liste des colonnes */
    private List<ColumnConfig> columns = new ArrayList<>();

    // ── SQL ──────────────────────────────────────────────────────────────────

    /**
     * Repertoire contenant les fichiers SQL.
     * Peut etre absolu (ex : /data/sql) ou relatif au classpath.
     * Exemple : /opt/batch/sql  ou  D:/work/sql
     */
    private String sqlDirectory;

    /**
     * Nom du fichier SQL dans le sqlDirectory.
     * Exemple : orders_new.sql
     *
     * Le fichier peut contenir des variables bindees de la forme :paramName
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
     * Nombre de lignes chargees par batch JDBC (fetchSize).
     * Impacte la memoire et les performances. Defaut : 1000.
     */
    private Integer fetchSize;

    /**
     * Bean name de la DataSource a utiliser si plusieurs sources de donnees
     * sont declarees dans le backoffice. Optionnel  - utilise la DataSource
     * principale par defaut.
     */
    private String dataSourceBean;

    /**
     * Retourne le fetchSize, avec 1000 comme valeur par defaut.
     *
     * @return fetchSize effectif
     */
    public int getEffectiveFetchSize() {
        return fetchSize != null && fetchSize > 0 ? fetchSize : 1000;
    }

    // ── REST API CONFIGURATION ───────────────────────────────────────────────

    /**
     * REST API configuration (required when type=REST).
     * Contains URL, authentication, pagination, and JSON extraction settings.
     */
    private RestConfig rest;

    /**
     * Indicates if this source has REST configuration.
     *
     * @return {@code true} if {@code rest} is defined
     */
    public boolean hasRestConfig() {
        return rest != null;
    }

    // ── SOAP WEBSERVICE CONFIGURATION ────────────────────────────────────────

    /**
     * SOAP WebService configuration (required when type=SOAP).
     * Contains endpoint, authentication, and XPath extraction settings.
     */
    private SoapConfig soap;

    /**
     * Indicates if this source has SOAP configuration.
     *
     * @return {@code true} if {@code soap} is defined
     */
    public boolean hasSoapConfig() {
        return soap != null;
    }

    // ── STEPS PRE/POST PROCESSING ────────────────────────────────────────────

    /**
     * Configuration de la step de pre-processing (optionnelle).
     * Executee avant la step de lecture/ecriture chunk.
     */
    private StepConfig preprocessing = new StepConfig();

    /**
     * Configuration de la step de post-processing (optionnelle).
     * Executee apres la step de lecture/ecriture chunk.
     */
    private StepConfig postprocessing = new StepConfig();

    // ── WRITER GENERIQUE ─────────────────────────────────────────────────────

    /**
     * Configuration du writer.
     * Si absent, le framework cherche un bean "{sourceName}Writer" dans le contexte Spring.
     */
    private WriterConfig writer;

    /**
     * Indique si le writer est configure de maniere declarative dans le YAML.
     *
     * @return {@code true} si {@code writer} est defini
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

        // ── Validation REST ──────────────────────────────────────────────────
        if ("REST".equalsIgnoreCase(type)) {
            if (rest == null) {
                throw new IllegalStateException("rest configuration is required for REST source: " + name);
            }
            rest.validate();
        }

        // ── Validation SOAP ──────────────────────────────────────────────────
        if ("SOAP".equalsIgnoreCase(type)) {
            if (soap == null) {
                throw new IllegalStateException("soap configuration is required for SOAP source: " + name);
            }
            soap.validate();
        }
        
        if (chunkSize != null && chunkSize <= 0) {
            throw new IllegalStateException("Chunk size must be positive for source: " + name);
        }

        // ── Validation pre/post processing ───────────────────────────────────
        if (preprocessing != null) {
            preprocessing.validate("preprocessing");
        }
        if (postprocessing != null) {
            postprocessing.validate("postprocessing");
        }

        // ── Validation writer declaratif ─────────────────────────────────────
        if (writer != null) {
            writer.validate();
        }
    }
}
