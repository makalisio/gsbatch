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
 * Writer générique piloté par un fichier SQL externe.
 *
 * <p>Charge le SQL une seule fois à la construction, puis pour chaque chunk
 * exécute un {@code batchUpdate} en utilisant les champs du {@link GenericRecord}
 * comme bind variables.</p>
 *
 * <h2>Exemple de fichier SQL</h2>
 * <pre>
 * INSERT INTO ORDERS_PROCESSED
 *   (order_id, customer_id, amount, tva_amount, total_ttc, currency, status)
 * VALUES
 *   (:order_id, :customer_id, :amount, :tva_amount, :total_ttc, :currency, :status)
 * </pre>
 *
 * <p>Les noms des bind variables ({@code :paramName}) doivent correspondre
 * aux clés du {@code GenericRecord} retourné par le processor.</p>
 *
 * <h2>Gestion des erreurs</h2>
 * <p>Le comportement en cas d'erreur est configuré dans le YAML via
 * {@code writer.onError} (FAIL ou SKIP) et géré par Spring Batch
 * au niveau du Step via {@code faultTolerant().skip().skipLimit()}.</p>
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
     * @param writerConfig  configuration du writer (sqlDirectory, sqlFile)
     * @param sqlFileLoader loader pour lire le fichier SQL
     * @param dataSource    DataSource à utiliser
     * @param sourceName    nom de la source (pour les logs)
     */
    public SqlGenericItemWriter(WriterConfig writerConfig,
                                SqlFileLoader sqlFileLoader,
                                DataSource dataSource,
                                String sourceName) {
        this.sourceName = sourceName;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

        // Charge le SQL une seule fois — NamedParameterJdbcTemplate gère
        // la substitution des :paramName pour chaque ligne du chunk
        this.rawSql = sqlFileLoader.readRawSql(
                writerConfig.getSqlDirectory(),
                writerConfig.getSqlFile()
        );

        log.info("Source '{}' — SqlGenericItemWriter initialisé depuis {}/{}",
                sourceName, writerConfig.getSqlDirectory(), writerConfig.getSqlFile());
        log.debug("Source '{}' — SQL writer : {}", sourceName, abbreviate(rawSql, 200));
    }

    /**
     * Écrit un chunk de {@link GenericRecord} via un {@code batchUpdate}.
     *
     * <p>Les champs de chaque {@code GenericRecord} sont mappés aux bind variables
     * du SQL ({@code :paramName} → {@code record.get("paramName")}).</p>
     *
     * <p>Le {@code batchUpdate} envoie toutes les lignes du chunk en un seul
     * round-trip JDBC, ce qui est plus performant que des INSERT individuels.</p>
     *
     * @param chunk le chunk de records à persister
     * @throws Exception en cas d'erreur JDBC (comportement selon {@code onError} dans le YAML)
     */
    @Override
    public void write(Chunk<? extends GenericRecord> chunk) throws Exception {
        if (chunk.isEmpty()) {
            log.debug("Source '{}' — chunk vide, rien à écrire", sourceName);
            return;
        }

        log.info("Source '{}' — écriture de {} enregistrement(s)", sourceName, chunk.size());

        // Construire les SqlParameterSource pour chaque record du chunk
        SqlParameterSource[] batchParams = buildBatchParams(chunk);

        // Exécuter le batchUpdate — un seul round-trip JDBC pour tout le chunk
        int[] rowCounts = namedJdbcTemplate.batchUpdate(rawSql, batchParams);

        // Comptage des lignes affectées
        int totalAffected = 0;
        int skipped = 0;
        for (int count : rowCounts) {
            if (count >= 0) {
                totalAffected += count;
            } else {
                skipped++; // SUCCESS_NO_INFO (-2) ou EXECUTE_FAILED (-3)
            }
        }

        log.info("Source '{}' — chunk écrit : {} ligne(s) affectée(s), {} sans info",
                sourceName, totalAffected, skipped);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construit le tableau de {@code SqlParameterSource} pour le {@code batchUpdate}.
     * Chaque record est converti en {@code MapSqlParameterSource} avec tous ses champs.
     */
    private SqlParameterSource[] buildBatchParams(Chunk<? extends GenericRecord> chunk) {
        List<SqlParameterSource> params = new ArrayList<>(chunk.size());

        for (GenericRecord record : chunk) {
            // getValues() retourne une Map<String, Object> immutable
            // MapSqlParameterSource accepte directement une Map
            MapSqlParameterSource paramSource = new MapSqlParameterSource(record.getValues());
            params.add(paramSource);
        }

        return params.toArray(new SqlParameterSource[0]);
    }

    /**
     * Tronque une chaîne pour les logs.
     */
    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
