package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Charge et prépare les requêtes SQL depuis des fichiers externes.
 *
 * <h2>Fonctionnement</h2>
 * <ol>
 *   <li>Lit le fichier {@code {sqlDirectory}/{sqlFile}} défini dans le YAML</li>
 *   <li>Parse les variables bindées de la forme {@code :paramName}</li>
 *   <li>Résout les valeurs depuis les {@code jobParameters}</li>
 *   <li>Génère un SQL exécutable ({@code ?}) et un {@code PreparedStatementSetter}</li>
 * </ol>
 *
 * <h2>Format des variables dans le fichier SQL</h2>
 * Les variables utilisent la syntaxe standard Spring {@code :paramName} :
 * <pre>
 *   SELECT order_id, customer_id, amount
 *   FROM   ORDERS
 *   WHERE  status     = :status
 *     AND  trade_date = :process_date
 *     AND  desk_code  = :desk
 * </pre>
 *
 * <h2>Passage des valeurs</h2>
 * Via les jobParameters en ligne de commande :
 * <pre>
 *   java -jar app.jar sourceName=orders status=NEW process_date=2024-01-15 desk=EQUITY
 * </pre>
 *
 * <h2>Fichiers SQL</h2>
 * Stocker les fichiers dans un répertoire dédié, hors du JAR,
 * afin de pouvoir les modifier sans recompiler l'application :
 * <pre>
 *   /opt/batch/sql/
 *     orders_new.sql
 *     trades_pending.sql
 *     positions_eod.sql
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class SqlFileLoader {

    /**
     * Regex pour extraire les paramètres bindés de la forme {@code :paramName}.
     *
     * <p>{@code ParsedSql.getParameterNames()} est package-private dans Spring
     * et ne peut pas être appelé depuis l'extérieur du package
     * {@code org.springframework.jdbc.core.namedparam}. On extrait donc les noms
     * de paramètres directement depuis le SQL brut via cette regex.</p>
     *
     * <p>La regex ignore {@code ::} (opérateur de cast PostgreSQL) et exige
     * que le nom commence par une lettre.</p>
     */
    private static final Pattern BIND_PARAM_PATTERN =
            Pattern.compile("(?<![:])(:[a-zA-Z][a-zA-Z0-9_]*)");

    // ─────────────────────────────────────────────────────────────────────────
    //  API publique
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Résultat du chargement d'un fichier SQL.
     * Contient le SQL exécutable (variables {@code :x} remplacées par {@code ?})
     * et un {@link org.springframework.jdbc.core.PreparedStatementSetter} prêt à l'emploi.
     */
    public static class LoadedSql {

        private final String executableSql;
        private final org.springframework.jdbc.core.PreparedStatementSetter preparedStatementSetter;
        private final List<String> parameterNames;

        LoadedSql(String executableSql,
                  org.springframework.jdbc.core.PreparedStatementSetter setter,
                  List<String> parameterNames) {
            this.executableSql = executableSql;
            this.preparedStatementSetter = setter;
            this.parameterNames = parameterNames;
        }

        /**
         * @return SQL avec {@code ?} à la place des {@code :paramName}
         */
        public String getExecutableSql() {
            return executableSql;
        }

        /**
         * @return setter à passer à {@code JdbcCursorItemReaderBuilder.preparedStatementSetter()}
         */
        public org.springframework.jdbc.core.PreparedStatementSetter getPreparedStatementSetter() {
            return preparedStatementSetter;
        }

        /**
         * @return liste ordonnée des noms de paramètres trouvés dans le SQL
         */
        public List<String> getParameterNames() {
            return parameterNames;
        }
    }

    /**
     * Charge un fichier SQL, résout les variables depuis les jobParameters
     * et retourne un {@link LoadedSql} prêt pour le {@link org.springframework.batch.item.database.JdbcCursorItemReader}.
     *
     * @param config        la configuration YAML de la source
     * @param jobParameters tous les paramètres du job (Map String → Object)
     * @return le SQL exécutable + le PreparedStatementSetter
     * @throws SqlFileException si le fichier est introuvable, illisible ou si un paramètre est manquant
     */
    public LoadedSql load(SourceConfig config, Map<String, Object> jobParameters) {

        // ── 1. Localiser et lire le fichier SQL ──────────────────────────────
        Path sqlPath = resolvePath(config);
        String rawSql = readFile(sqlPath, config);

        log.debug("Source '{}' — SQL brut chargé depuis {} :\n{}", config.getName(), sqlPath, rawSql);

        // ── 2. Parser les variables :paramName ───────────────────────────────
        // Extraction via regex car ParsedSql.getParameterNames() est package-private
        List<String> paramNames = extractParamNames(rawSql);
        // ParsedSql utilisé uniquement pour substituteNamedParameters et buildValueArray (API publique)
        ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(rawSql);

        log.info("Source '{}' — {} variable(s) bindée(s) trouvée(s) : {}",
                config.getName(), paramNames.size(), paramNames);

        // ── 3. Résoudre les valeurs depuis jobParameters ──────────────────────
        MapSqlParameterSource paramSource = resolveParameters(paramNames, jobParameters, config);

        // ── 4. Générer le SQL exécutable (? à la place de :paramName) ────────
        String executableSql = NamedParameterUtils.substituteNamedParameters(parsedSql, paramSource);
        Object[] values = NamedParameterUtils.buildValueArray(parsedSql, paramSource, null);

        log.debug("Source '{}' — SQL exécutable :\n{}", config.getName(), executableSql);
        log.debug("Source '{}' — Valeurs des paramètres : {}", config.getName(),
                buildParamLog(paramNames, values));

        // ── 5. Construire le PreparedStatementSetter ─────────────────────────
        org.springframework.jdbc.core.PreparedStatementSetter setter = buildSetter(values, paramNames, config);

        return new LoadedSql(executableSql, setter, paramNames);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Implémentation privée
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Résout le chemin absolu du fichier SQL.
     */
    private Path resolvePath(SourceConfig config) {
        Path path = Paths.get(config.getSqlDirectory(), config.getSqlFile());
        log.debug("Source '{}' — chemin SQL résolu : {}", config.getName(), path.toAbsolutePath());
        return path;
    }

    /**
     * Lit le contenu du fichier SQL en UTF-8.
     * Supprime les commentaires {@code -- ...} de fin de ligne pour éviter
     * de les transmettre au driver JDBC.
     */
    private String readFile(Path path, SourceConfig config) {
        if (!Files.exists(path)) {
            throw new SqlFileException(String.format(
                    "Fichier SQL introuvable pour la source '%s' : %s%n" +
                            "Vérifiez que sqlDirectory='%s' et sqlFile='%s' sont corrects.",
                    config.getName(), path.toAbsolutePath(),
                    config.getSqlDirectory(), config.getSqlFile()
            ));
        }
        if (!Files.isReadable(path)) {
            throw new SqlFileException(String.format(
                    "Fichier SQL non lisible pour la source '%s' : %s",
                    config.getName(), path.toAbsolutePath()
            ));
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // Supprimer les commentaires -- sur une ligne entière ou en fin de ligne
            content = content.replaceAll("--[^\n]*", "").trim();
            if (content.isBlank()) {
                throw new SqlFileException(String.format(
                        "Fichier SQL vide ou ne contenant que des commentaires : %s", path));
            }
            log.info("Source '{}' — fichier SQL chargé : {} ({} caractères)",
                    config.getName(), path.getFileName(), content.length());
            return content;
        } catch (IOException e) {
            throw new SqlFileException(
                    "Impossible de lire le fichier SQL : " + path, e);
        }
    }

    /**
     * Extrait les noms de paramètres bindés (forme {@code :paramName}) depuis le SQL brut.
     *
     * <p>Utilise une regex car {@code ParsedSql.getParameterNames()} est package-private.</p>
     *
     * <p>L'ordre des noms est préservé (ordre d'apparition dans le SQL).
     * Les doublons sont supprimés (un même param peut apparaître plusieurs fois).</p>
     *
     * @param sql le SQL brut (avec les {@code :paramName})
     * @return liste ordonnée et dédupliquée des noms de paramètres
     */
    private List<String> extractParamNames(String sql) {
        // LinkedHashSet : déduplique tout en préservant l'ordre d'insertion
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = BIND_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            // matcher.group(1) contient ":paramName", on retire le ":"
            names.add(matcher.group(1).substring(1));
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    /**
     * Résout les valeurs des paramètres depuis les jobParameters.
     * Vérifie que chaque variable bindée a bien une valeur transmise.
     */
    private MapSqlParameterSource resolveParameters(List<String> paramNames,
                                                    Map<String, Object> jobParameters,
                                                    SourceConfig config) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();

        for (String paramName : paramNames) {
            if (!jobParameters.containsKey(paramName)) {
                throw new SqlFileException(String.format(
                        "Paramètre bindé manquant pour la source '%s' : ':%s'%n" +
                                "Paramètres disponibles : %s%n" +
                                "Ajoutez-le à la commande : java -jar app.jar sourceName=%s %s=<valeur>",
                        config.getName(), paramName,
                        jobParameters.keySet(),
                        config.getName(), paramName
                ));
            }
            Object value = jobParameters.get(paramName);
            paramSource.addValue(paramName, value);
            log.debug("Source '{}' — paramètre résolu : :{} = '{}'",
                    config.getName(), paramName, value);
        }

        return paramSource;
    }

    /**
     * Construit un PreparedStatementSetter à partir du tableau de valeurs.
     * Utilise {@code setObject} pour laisser le driver JDBC inférer les types SQL.
     */
    private org.springframework.jdbc.core.PreparedStatementSetter buildSetter(
            Object[] values, List<String> paramNames, SourceConfig config) {

        return (PreparedStatement ps) -> {
            for (int i = 0; i < values.length; i++) {
                try {
                    ps.setObject(i + 1, values[i]);
                } catch (SQLException e) {
                    throw new SQLException(String.format(
                            "Erreur lors du binding du paramètre [%d] ':%s' = '%s' pour la source '%s' : %s",
                            i + 1, paramNames.get(i), values[i], config.getName(), e.getMessage()
                    ), e);
                }
            }
        };
    }

    /**
     * Construit un log lisible des paramètres (masque les valeurs longues).
     */
    private String buildParamLog(List<String> names, Object[] values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            String val = values[i] == null ? "null" : values[i].toString();
            sb.append(names.get(i)).append("=").append(val);
        }
        sb.append("}");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Exception dédiée
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exception levée lors du chargement ou de la résolution d'un fichier SQL.
     */
    public static class SqlFileException extends RuntimeException {

        /**
         * @param message description de l'erreur
         */
        public SqlFileException(String message) {
            super(message);
        }

        /**
         * @param message description de l'erreur
         * @param cause   cause originale
         */
        public SqlFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
