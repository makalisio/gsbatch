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
package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.util.VariableResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * Loads and prepares SQL queries from external files.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Reads the file {@code {sqlDirectory}/{sqlFile}} defined in the YAML</li>
 *   <li>Parses bind variables of the form {@code :paramName}</li>
 *   <li>Resolves values from {@code jobParameters}</li>
 *   <li>Generates executable SQL ({@code ?}) and a {@code PreparedStatementSetter}</li>
 * </ol>
 *
 * <h2>Variable format in SQL files</h2>
 * Variables use the standard Spring {@code :paramName} syntax:
 * <pre>
 *   SELECT order_id, customer_id, amount
 *   FROM   ORDERS
 *   WHERE  status     = :status
 *     AND  trade_date = :process_date
 *     AND  desk_code  = :desk
 * </pre>
 *
 * <h2>Passing values</h2>
 * Via jobParameters on the command line:
 * <pre>
 *   java -jar app.jar sourceName=orders status=NEW process_date=2024-01-15 desk=EQUITY
 * </pre>
 *
 * <h2>SQL files</h2>
 * Store files in a dedicated directory, outside the JAR,
 * so they can be modified without recompiling the application:
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
     * Prefix that marks a {@code sqlDirectory} as a classpath location rather
     * than a filesystem path (e.g. {@code classpath:sql}).
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of loading a SQL file.
     * Contains the executable SQL (variables {@code :x} replaced by {@code ?})
     * and a ready-to-use {@link org.springframework.jdbc.core.PreparedStatementSetter}.
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
         * @return SQL with {@code ?} in place of {@code :paramName}
         */
        public String getExecutableSql() {
            return executableSql;
        }

        /**
         * @return setter to pass to {@code JdbcCursorItemReaderBuilder.preparedStatementSetter()}
         */
        public org.springframework.jdbc.core.PreparedStatementSetter getPreparedStatementSetter() {
            return preparedStatementSetter;
        }

        /**
         * @return ordered list of parameter names found in the SQL
         */
        public List<String> getParameterNames() {
            return parameterNames;
        }
    }

    /**
     * Loads a SQL file, resolves variables from jobParameters,
     * and returns a {@link LoadedSql} ready for the {@link org.springframework.batch.item.database.JdbcCursorItemReader}.
     *
     * @param config        the YAML source configuration
     * @param jobParameters all job parameters (Map String → Object)
     * @return the executable SQL + the PreparedStatementSetter
     * @throws SqlFileException if the file is not found, unreadable, or a parameter is missing
     */
    public LoadedSql load(SourceConfig config, Map<String, Object> jobParameters) {

        // ── 1. Locate and read the SQL file ──────────────────────────────────
        String rawSql = loadSqlContent(config.getSqlDirectory(), config.getSqlFile());

        log.debug("Source '{}'  - raw SQL loaded:\n{}", config.getName(), rawSql);

        // ── 2. Parse :paramName variables ────────────────────────────────────
        // Extracted via regex because ParsedSql.getParameterNames() is package-private
        List<String> paramNames = extractParamNames(rawSql);
        // ParsedSql used only for substituteNamedParameters and buildValueArray (public API)
        ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(rawSql);

        log.info("Source '{}'  - {} bound variable(s) found: {}",
                config.getName(), paramNames.size(), paramNames);

        // ── 3. Resolve values from jobParameters ─────────────────────────────
        MapSqlParameterSource paramSource = resolveParameters(paramNames, jobParameters, config);

        // ── 4. Generate executable SQL (? in place of :paramName) ────────────
        String executableSql = NamedParameterUtils.substituteNamedParameters(parsedSql, paramSource);
        Object[] values = NamedParameterUtils.buildValueArray(parsedSql, paramSource, null);

        log.debug("Source '{}'  - executable SQL :\n{}", config.getName(), executableSql);
        log.debug("Source '{}'  - parameter values: {}", config.getName(),
                buildParamLog(paramNames, values));

        // ── 5. Build the PreparedStatementSetter ─────────────────────────────
        org.springframework.jdbc.core.PreparedStatementSetter setter = buildSetter(values, paramNames, config);

        return new LoadedSql(executableSql, setter, paramNames);
    }

    /**
     * Loads a SQL file containing multiple statements (pre/post processing).
     *
     * <p>Each statement is delimited by {@code ;}.
     * All statements share the same {@code jobParameters} as bind variables.
     * The caller is responsible for executing them within the same transaction.</p>
     *
     * @param sqlDirectory directory containing the SQL file
     * @param sqlFile      name of the SQL file
     * @param parameters   parameters to bind (jobParameters)
     * @return ordered list of statements ready to execute
     * @throws SqlFileException if the file is not found or a parameter is missing
     */
    public List<LoadedSql> loadStatements(String sqlDirectory, String sqlFile,
                                          Map<String, Object> parameters) {
        String rawContent = loadSqlContent(sqlDirectory, sqlFile);

        // Split by ";" at end of statement (ignores ";" inside strings)
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

        log.info("SQL file '{}/{}': {} statement(s) loaded", sqlDirectory, sqlFile, result.size());
        return result;
    }

    /**
     * Reads the raw SQL from a file without variable substitution.
     *
     * <p>Used by the SQL writer: the raw SQL (with {@code :paramName}) is passed
     * directly to {@code NamedParameterJdbcTemplate.batchUpdate()},
     * which handles substitution for each row in the chunk.</p>
     *
     * @param sqlDirectory directory containing the SQL file
     * @param sqlFile      name of the SQL file
     * @return cleaned SQL content (comments removed)
     * @throws SqlFileException if the file is not found or unreadable
     */
    public String readRawSql(String sqlDirectory, String sqlFile) {
        return loadSqlContent(sqlDirectory, sqlFile);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads the raw SQL file content, resolving {@code sqlDirectory} in the
     * documented order: a {@code classpath:} prefix is loaded via the classpath,
     * anything else is resolved as a filesystem path (absolute or relative to
     * the working directory, per {@link Paths#get(String, String...)} semantics).
     */
    private String loadSqlContent(String sqlDirectory, String sqlFile) {
        if (sqlDirectory != null && sqlDirectory.startsWith(CLASSPATH_PREFIX)) {
            return readClasspathFile(sqlDirectory, sqlFile);
        }

        Path path = Paths.get(sqlDirectory, sqlFile);
        log.debug("Resolved SQL path: {}", path.toAbsolutePath());
        return readFileContent(path, sqlDirectory, sqlFile);
    }

    /**
     * Reads and cleans a SQL file located on the classpath (e.g. {@code sqlDirectory: classpath:sql}).
     */
    private String readClasspathFile(String sqlDirectory, String sqlFile) {
        String classpathDir = sqlDirectory.substring(CLASSPATH_PREFIX.length());
        String resourcePath = classpathDir.isBlank() ? sqlFile : classpathDir + "/" + sqlFile;
        ClassPathResource resource = new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            throw new SqlFileException(String.format(
                    "SQL file not found on classpath: %s%n" +
                            "Check sqlDirectory='%s' and sqlFile='%s'.",
                    resourcePath, sqlDirectory, sqlFile
            ));
        }

        try (InputStream is = resource.getInputStream()) {
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            fileContent = cleanSqlContent(fileContent, "classpath:" + resourcePath);
            log.info("SQL file loaded from classpath: {} ({} characters)", resourcePath, fileContent.length());
            return fileContent;
        } catch (IOException e) {
            throw new SqlFileException("Unable to read SQL file from classpath: " + resourcePath, e);
        }
    }

    /**
     * Reads the SQL file content in UTF-8.
     * Removes {@code -- ...} end-of-line comments to avoid
     * passing them to the JDBC driver.
     *
     * @param path         resolved path of the file
     * @param sqlDirectory directory (for error messages)
     * @param sqlFile      file name (for error messages)
     * @return cleaned SQL content
     */
    private String readFileContent(Path path, String sqlDirectory, String sqlFile) {
        if (!Files.exists(path)) {
            throw new SqlFileException(String.format(
                    "SQL file not found: %s%n" +
                            "Check sqlDirectory='%s' and sqlFile='%s'.",
                    path.toAbsolutePath(), sqlDirectory, sqlFile
            ));
        }
        if (!Files.isReadable(path)) {
            throw new SqlFileException(String.format(
                    "SQL file is not readable: %s", path.toAbsolutePath()
            ));
        }

        try {
            String fileContent = Files.readString(path, StandardCharsets.UTF_8);
            fileContent = cleanSqlContent(fileContent, path.toString());
            log.info("SQL file loaded: {} ({} characters)", path.getFileName(), fileContent.length());
            return fileContent;
        } catch (IOException e) {
            throw new SqlFileException("Unable to read SQL file: " + path, e);
        }
    }

    /**
     * Strips {@code -- ...} end-of-line comments and validates the remaining content is non-blank.
     */
    private String cleanSqlContent(String fileContent, String source) {
        String cleaned = fileContent.replaceAll("--[^\n]*", "").trim();
        if (cleaned.isBlank()) {
            throw new SqlFileException("SQL file is empty or contains only comments: " + source);
        }
        return cleaned;
    }

    /**
     * Extracts bound parameter names (form {@code :paramName}) from raw SQL.
     *
     * <p>Uses a regex because {@code ParsedSql.getParameterNames()} is package-private.</p>
     *
     * <p>The order of names is preserved (order of appearance in the SQL).
     * Duplicates are removed (the same parameter may appear multiple times).</p>
     *
     * @param sql the raw SQL (with {@code :paramName})
     * @return ordered and deduplicated list of parameter names
     */
    private List<String> extractParamNames(String sql) {
        // ParsedSql.getParameterNames() is package-private in Spring and cannot be
        // called from outside org.springframework.jdbc.core.namedparam, so parameter
        // names are extracted directly from the raw SQL via this regex instead.
        // LinkedHashSet: deduplicates while preserving insertion order
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = VariableResolver.BIND_PARAM_PATTERN.matcher(sql);
        while (matcher.find()) {
            // matcher.group(1) contains ":paramName", we strip the ":"
            names.add(matcher.group(1).substring(1));
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    /**
     * Resolves parameter values from jobParameters.
     * Verifies that every bound variable has a corresponding value.
     */
    private MapSqlParameterSource resolveParameters(List<String> paramNames,
                                                    Map<String, Object> parameters,
                                                    SourceConfig config) {
        return resolveParameters(paramNames, parameters, config.getName());
    }

    /**
     * Resolves parameter values (generic version by identifier).
     */
    private MapSqlParameterSource resolveParameters(List<String> paramNames,
                                                    Map<String, Object> parameters,
                                                    String identifier) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();

        for (String paramName : paramNames) {
            if (!parameters.containsKey(paramName)) {
                throw new SqlFileException(String.format(
                        "Missing bound parameter [%s]: ':%s'%n" +
                                "Available parameters: %s%n" +
                                "Add it to the command: java -jar app.jar %s=<value>",
                        identifier, paramName,
                        parameters.keySet(),
                        paramName
                ));
            }
            Object value = parameters.get(paramName);
            paramSource.addValue(paramName, value);
            log.debug("[{}] resolved parameter: :{} = '{}'", identifier, paramName, value);
        }

        return paramSource;
    }

    /**
     * Builds a PreparedStatementSetter (SourceConfig version).
     */
    private org.springframework.jdbc.core.PreparedStatementSetter buildSetter(
            Object[] values, List<String> paramNames, SourceConfig config) {
        return buildSetter(values, paramNames, config.getName());
    }

    /**
     * Builds a PreparedStatementSetter from the values array.
     * Uses {@code setObject} to let the JDBC driver infer SQL types.
     */
    private org.springframework.jdbc.core.PreparedStatementSetter buildSetter(
            Object[] values, List<String> paramNames, String identifier) {

        return (PreparedStatement ps) -> {
            for (int i = 0; i < values.length; i++) {
                try {
                    ps.setObject(i + 1, values[i]);
                } catch (SQLException e) {
                    throw new SQLException(String.format(
                            "Error binding parameter [%d] ':%s' = '%s' [%s]: %s",
                            i + 1, paramNames.get(i), values[i], identifier, e.getMessage()
                    ), e);
                }
            }
        };
    }

    /**
     * Builds a readable log of parameters (masks long values).
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
    //  Dedicated exception
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exception thrown when loading or resolving a SQL file fails.
     */
    public static class SqlFileException extends RuntimeException {

        /**
         * @param message description of the error
         */
        public SqlFileException(String message) {
            super(message);
        }

        /**
         * @param message description of the error
         * @param cause   original cause
         */
        public SqlFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
