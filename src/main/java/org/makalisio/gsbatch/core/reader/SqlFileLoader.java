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
 * Charge et prepare les requetes SQL depuis des fichiers externes.
 *
 * <h2>Fonctionnement</h2>
 * <ol>
 *   <li>Lit le fichier {@code {sqlDirectory}/{sqlFile}} defini dans le YAML</li>
 *   <li>Parse les variables bindees de la forme {@code :paramName}</li>
 *   <li>Resout les valeurs depuis les {@code jobParameters}</li>
 *   <li>Genere un SQL executable ({@code ?}) et un {@code PreparedStatementSetter}</li>
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
 * Stocker les fichiers dans un repertoire dedie, hors du JAR,
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
     * Regex pour extraire les parametres bindes de la forme {@code :paramName}.
     *
     * <p>{@code ParsedSql.getParameterNames()} est package-private dans Spring
     * et ne peut pas etre appele depuis l'exterieur du package
     * {@code org.springframework.jdbc.core.namedparam}. On extrait donc les noms
     * de parametres directement depuis le SQL brut via cette regex.</p>
     *
     * <p>La regex ignore {@code ::} (operateur de cast PostgreSQL) et exige
     * que le nom commence par une lettre.</p>
     */
    private static final Pattern BIND_PARAM_PATTERN =
            Pattern.compile("(?<![:])(:[a-zA-Z][a-zA-Z0-9_]*)");

    // ─────────────────────────────────────────────────────────────────────────
    //  API publique
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resultat du chargement d'un fichier SQL.
     * Contient le SQL executable (variables {@code :x} remplacees par {@code ?})
     * et un {@link org.springframework.jdbc.core.PreparedStatementSetter} pret a l'emploi.
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
         * @return SQL avec {@code ?} a la place des {@code :paramName}
         */
        public String getExecutableSql() {
            return executableSql;
        }

        /**
         * @return setter a passer a {@code JdbcCursorItemReaderBuilder.preparedStatementSetter()}
         */
        public org.springframework.jdbc.core.PreparedStatementSetter getPreparedStatementSetter() {
            return preparedStatementSetter;
        }

        /**
         * @return liste ordonnee des noms de parametres trouves dans le SQL
         */
        public List<String> getParameterNames() {
            return parameterNames;
        }
    }

    /**
     * Charge un fichier SQL, resout les variables depuis les jobParameters
     * et retourne un {@link LoadedSql} pret pour le {@link org.springframework.batch.item.database.JdbcCursorItemReader}.
     *
     * @param config        la configuration YAML de la source
     * @param jobParameters tous les parametres du job (Map String → Object)
     * @return le SQL executable + le PreparedStatementSetter
     * @throws SqlFileException si le fichier est introuvable, illisible ou si un parametre est manquant
     */
    public LoadedSql load(SourceConfig config, Map<String, Object> jobParameters) {

        // ── 1. Localiser et lire le fichier SQL ──────────────────────────────
        Path sqlPath = resolvePath(config);
        String rawSql = readFile(sqlPath, config);

        log.debug("Source '{}'  - SQL brut charge depuis {} :\n{}", config.getName(), sqlPath, rawSql);

        // ── 2. Parser les variables :paramName ───────────────────────────────
        // Extraction via regex car ParsedSql.getParameterNames() est package-private
        List<String> paramNames = extractParamNames(rawSql);
        // ParsedSql utilise uniquement pour substituteNamedParameters et buildValueArray (API publique)
        ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(rawSql);

        log.info("Source '{}'  - {} variable(s) bindee(s) trouvee(s) : {}",
                config.getName(), paramNames.size(), paramNames);

        // ── 3. Resoudre les valeurs depuis jobParameters ──────────────────────
        MapSqlParameterSource paramSource = resolveParameters(paramNames, jobParameters, config);

        // ── 4. Generer le SQL executable (? a la place de :paramName) ────────
        String executableSql = NamedParameterUtils.substituteNamedParameters(parsedSql, paramSource);
        Object[] values = NamedParameterUtils.buildValueArray(parsedSql, paramSource, null);

        log.debug("Source '{}'  - SQL executable :\n{}", config.getName(), executableSql);
        log.debug("Source '{}'  - Valeurs des parametres : {}", config.getName(),
                buildParamLog(paramNames, values));

        // ── 5. Construire le PreparedStatementSetter ─────────────────────────
        org.springframework.jdbc.core.PreparedStatementSetter setter = buildSetter(values, paramNames, config);

        return new LoadedSql(executableSql, setter, paramNames);
    }

    /**
     * Charge un fichier SQL contenant plusieurs instructions (pre/post processing).
     *
     * <p>Chaque instruction est delimitee par {@code ;}.
     * Toutes les instructions partagent les memes {@code jobParameters} comme bind variables.
     * L'appelant est responsable de les executer dans la meme transaction.</p>
     *
     * @param sqlDirectory repertoire contenant le fichier SQL
     * @param sqlFile      nom du fichier SQL
     * @param parameters   parametres a binder (jobParameters)
     * @return liste ordonnee des instructions pretes a executer
     * @throws SqlFileException si le fichier est introuvable ou un parametre manque
     */
    public List<LoadedSql> loadStatements(String sqlDirectory, String sqlFile,
                                          Map<String, Object> parameters) {
        Path path = Paths.get(sqlDirectory, sqlFile);
        String rawContent = readFileContent(path, sqlDirectory, sqlFile);

        // Decouper par ";" en fin d'instruction (ignore les ";" dans les chaines)
        String[] rawStatements = rawContent.split(";\s*(?=\n|\r|$)");

        List<LoadedSql> result = new ArrayList<>();
        for (String rawStmt : rawStatements) {
            String stmt = rawStmt.trim();
            if (stmt.isBlank()) continue;

            List<String> paramNames = extractParamNames(stmt);
            ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(stmt);
            MapSqlParameterSource paramSource = resolveParameters(paramNames, parameters,
                    sqlDirectory + "/" + sqlFile);

            String executableSql = NamedParameterUtils.substituteNamedParameters(parsedSql, paramSource);
            Object[] values = NamedParameterUtils.buildValueArray(parsedSql, paramSource, null);
            org.springframework.jdbc.core.PreparedStatementSetter setter = buildSetter(values, paramNames,
                    sqlDirectory + "/" + sqlFile);

            result.add(new LoadedSql(executableSql, setter, paramNames));
        }

        log.info("Fichier SQL '{}/{}' : {} instruction(s) chargee(s)", sqlDirectory, sqlFile, result.size());
        return result;
    }

    /**
     * Lit le SQL brut d'un fichier sans substitution de variables.
     *
     * <p>Utilise par le writer SQL : le SQL brut (avec {@code :paramName}) est passe
     * directement a {@code NamedParameterJdbcTemplate.batchUpdate()},
     * qui gere la substitution pour chaque ligne du chunk.</p>
     *
     * @param sqlDirectory repertoire contenant le fichier SQL
     * @param sqlFile      nom du fichier SQL
     * @return contenu SQL nettoye (commentaires supprimes)
     * @throws SqlFileException si le fichier est introuvable ou illisible
     */
    public String readRawSql(String sqlDirectory, String sqlFile) {
        Path path = Paths.get(sqlDirectory, sqlFile);
        return readFileContent(path, sqlDirectory, sqlFile);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Implementation privee
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resout le chemin absolu du fichier SQL.
     */
    private Path resolvePath(SourceConfig config) {
        Path path = Paths.get(config.getSqlDirectory(), config.getSqlFile());
        log.debug("Source '{}'  - chemin SQL resolu : {}", config.getName(), path.toAbsolutePath());
        return path;
    }

    /**
     * Lit le contenu du fichier SQL en UTF-8 (delegation vers la methode generique).
     */
    private String readFile(Path path, SourceConfig config) {
        return readFileContent(path, config.getSqlDirectory(), config.getSqlFile());
    }

    /**
     * Lit le contenu du fichier SQL en UTF-8.
     * Supprime les commentaires {@code -- ...} de fin de ligne pour eviter
     * de les transmettre au driver JDBC.
     *
     * @param path         chemin resolu du fichier
     * @param sqlDirectory repertoire (pour les messages d'erreur)
     * @param sqlFile      nom du fichier (pour les messages d'erreur)
     * @return contenu SQL nettoye
     */
    private String readFileContent(Path path, String sqlDirectory, String sqlFile) {
        if (!Files.exists(path)) {
            throw new SqlFileException(String.format(
                    "Fichier SQL introuvable : %s%n" +
                            "Verifiez sqlDirectory='%s' et sqlFile='%s'.",
                    path.toAbsolutePath(), sqlDirectory, sqlFile
            ));
        }
        if (!Files.isReadable(path)) {
            throw new SqlFileException(String.format(
                    "Fichier SQL non lisible : %s", path.toAbsolutePath()
            ));
        }

        try {
            String fileContent = Files.readString(path, StandardCharsets.UTF_8);
            fileContent = fileContent.replaceAll("--[^\n]*", "").trim();
            if (fileContent.isBlank()) {
                throw new SqlFileException(
                        "Fichier SQL vide ou ne contenant que des commentaires : " + path);
            }
            log.info("Fichier SQL charge : {} ({} caracteres)", path.getFileName(), fileContent.length());
            return fileContent;
        } catch (IOException e) {
            throw new SqlFileException("Impossible de lire le fichier SQL : " + path, e);
        }
    }

    /**
     * Extrait les noms de parametres bindes (forme {@code :paramName}) depuis le SQL brut.
     *
     * <p>Utilise une regex car {@code ParsedSql.getParameterNames()} est package-private.</p>
     *
     * <p>L'ordre des noms est preserve (ordre d'apparition dans le SQL).
     * Les doublons sont supprimes (un meme param peut apparaitre plusieurs fois).</p>
     *
     * @param sql le SQL brut (avec les {@code :paramName})
     * @return liste ordonnee et dedupliquee des noms de parametres
     */
    private List<String> extractParamNames(String sql) {
        // LinkedHashSet : deduplique tout en preservant l'ordre d'insertion
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = BIND_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            // matcher.group(1) contient ":paramName", on retire le ":"
            names.add(matcher.group(1).substring(1));
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    /**
     * Resout les valeurs des parametres depuis les jobParameters.
     * Verifie que chaque variable bindee a bien une valeur transmise.
     */
    private MapSqlParameterSource resolveParameters(List<String> paramNames,
                                                    Map<String, Object> parameters,
                                                    SourceConfig config) {
        return resolveParameters(paramNames, parameters, config.getName());
    }

    /**
     * Resout les valeurs des parametres (version generique par identifiant).
     */
    private MapSqlParameterSource resolveParameters(List<String> paramNames,
                                                    Map<String, Object> parameters,
                                                    String identifier) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();

        for (String paramName : paramNames) {
            if (!parameters.containsKey(paramName)) {
                throw new SqlFileException(String.format(
                        "Parametre binde manquant [%s] : ':%s'%n" +
                                "Parametres disponibles : %s%n" +
                                "Ajoutez-le a la commande : java -jar app.jar %s=<valeur>",
                        identifier, paramName,
                        parameters.keySet(),
                        paramName
                ));
            }
            Object value = parameters.get(paramName);
            paramSource.addValue(paramName, value);
            log.debug("[{}] parametre resolu : :{} = '{}'", identifier, paramName, value);
        }

        return paramSource;
    }

    /**
     * Construit un PreparedStatementSetter (version SourceConfig).
     */
    private org.springframework.jdbc.core.PreparedStatementSetter buildSetter(
            Object[] values, List<String> paramNames, SourceConfig config) {
        return buildSetter(values, paramNames, config.getName());
    }

    /**
     * Construit un PreparedStatementSetter a partir du tableau de valeurs.
     * Utilise {@code setObject} pour laisser le driver JDBC inferer les types SQL.
     */
    private org.springframework.jdbc.core.PreparedStatementSetter buildSetter(
            Object[] values, List<String> paramNames, String identifier) {

        return (PreparedStatement ps) -> {
            for (int i = 0; i < values.length; i++) {
                try {
                    ps.setObject(i + 1, values[i]);
                } catch (SQLException e) {
                    throw new SQLException(String.format(
                            "Erreur binding parametre [%d] ':%s' = '%s' [%s] : %s",
                            i + 1, paramNames.get(i), values[i], identifier, e.getMessage()
                    ), e);
                }
            }
        };
    }

    /**
     * Construit un log lisible des parametres (masque les valeurs longues).
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
    //  Exception dediee
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exception levee lors du chargement ou de la resolution d'un fichier SQL.
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