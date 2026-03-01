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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.makalisio.gsbatch.core.model.SourceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SqlFileLoaderTest {

    @TempDir
    Path tempDir;

    private SqlFileLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SqlFileLoader();
    }

    // ── load() — cas nominal ─────────────────────────────────────────────────

    @Test
    void load_noBindParams_returnsExecutableSql() throws IOException {
        writeSql("query.sql", "SELECT * FROM ORDERS");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("query.sql"), Map.of());

        assertThat(result.getExecutableSql()).containsIgnoringCase("SELECT");
        assertThat(result.getParameterNames()).isEmpty();
        assertThat(result.getPreparedStatementSetter()).isNotNull();
    }

    @Test
    void load_withBindParam_replacesWithPlaceholder() throws IOException {
        writeSql("filtered.sql", "SELECT * FROM ORDERS WHERE status = :status");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("filtered.sql"), Map.of("status", "NEW"));

        assertThat(result.getParameterNames()).containsExactly("status");
        assertThat(result.getExecutableSql()).contains("?");
        assertThat(result.getExecutableSql()).doesNotContain(":status");
    }

    @Test
    void load_withMultipleBindParams_resolvesAll() throws IOException {
        writeSql("multi.sql", "SELECT * FROM T WHERE a = :a AND b = :b");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("multi.sql"), Map.of("a", 1, "b", 2));

        assertThat(result.getParameterNames()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void load_duplicateParam_deduplicatedInParamNames() throws IOException {
        writeSql("dup.sql", "SELECT * FROM T WHERE a = :x AND b = :x");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("dup.sql"), Map.of("x", "val"));

        assertThat(result.getParameterNames()).containsExactly("x");
    }

    // ── load() — erreurs ─────────────────────────────────────────────────────

    @Test
    void load_missingBindParam_throwsSqlFileException() throws IOException {
        writeSql("missing.sql", "SELECT * FROM T WHERE status = :status");

        assertThatThrownBy(() -> loader.load(sqlConfig("missing.sql"), Map.of()))
                .isInstanceOf(SqlFileLoader.SqlFileException.class)
                .hasMessageContaining("status");
    }

    @Test
    void load_fileNotFound_throwsSqlFileException() {
        SourceConfig config = sqlConfig("nonexistent.sql");

        assertThatThrownBy(() -> loader.load(config, Map.of()))
                .isInstanceOf(SqlFileLoader.SqlFileException.class)
                .hasMessageContaining("not found");
    }

    // ── load() — nettoyage des commentaires ──────────────────────────────────

    @Test
    void load_sqlCommentsRemoved() throws IOException {
        writeSql("commented.sql", "SELECT * FROM ORDERS -- filter by status\nWHERE id = :id");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("commented.sql"), Map.of("id", 1));

        assertThat(result.getExecutableSql()).doesNotContain("filter by status");
        assertThat(result.getExecutableSql()).doesNotContain("--");
    }

    // ── Compatibilité PostgreSQL :: ──────────────────────────────────────────

    @Test
    void load_postgresqlCastOperator_notTreatedAsBindParam() throws IOException {
        writeSql("pgcast.sql", "SELECT id::text, amount::numeric FROM ORDERS");
        SqlFileLoader.LoadedSql result = loader.load(sqlConfig("pgcast.sql"), Map.of());

        assertThat(result.getParameterNames()).isEmpty();
    }

    // ── readRawSql() ─────────────────────────────────────────────────────────

    @Test
    void readRawSql_returnsRawContent() throws IOException {
        writeSql("insert.sql", "INSERT INTO T VALUES (:v)");
        String raw = loader.readRawSql(tempDir.toString(), "insert.sql");

        assertThat(raw).contains("INSERT INTO T VALUES (:v)");
    }

    @Test
    void readRawSql_commentsStripped() throws IOException {
        writeSql("with-comment.sql", "SELECT 1 -- a comment\nFROM T");
        String raw = loader.readRawSql(tempDir.toString(), "with-comment.sql");

        assertThat(raw).doesNotContain("a comment");
    }

    @Test
    void readRawSql_fileNotFound_throwsSqlFileException() {
        assertThatThrownBy(() -> loader.readRawSql(tempDir.toString(), "missing.sql"))
                .isInstanceOf(SqlFileLoader.SqlFileException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void readRawSql_emptyFile_throwsSqlFileException() throws IOException {
        writeSql("empty.sql", "  ");

        assertThatThrownBy(() -> loader.readRawSql(tempDir.toString(), "empty.sql"))
                .isInstanceOf(SqlFileLoader.SqlFileException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void readRawSql_onlyComments_throwsSqlFileException() throws IOException {
        writeSql("comments-only.sql", "-- just a comment\n-- another comment");

        assertThatThrownBy(() -> loader.readRawSql(tempDir.toString(), "comments-only.sql"))
                .isInstanceOf(SqlFileLoader.SqlFileException.class)
                .hasMessageContaining("empty");
    }

    // ── loadStatements() ─────────────────────────────────────────────────────

    @Test
    void loadStatements_singleStatement_returnsOneItem() throws IOException {
        writeSql("single.sql", "UPDATE T SET x = 1");
        List<SqlFileLoader.LoadedSql> stmts = loader.loadStatements(
                tempDir.toString(), "single.sql", Map.of());

        assertThat(stmts).hasSize(1);
    }

    @Test
    void loadStatements_twoStatements_returnsTwoItems() throws IOException {
        writeSql("two.sql", "UPDATE T SET x = 1;\nINSERT INTO LOG VALUES ('done')");
        List<SqlFileLoader.LoadedSql> stmts = loader.loadStatements(
                tempDir.toString(), "two.sql", Map.of());

        assertThat(stmts).hasSize(2);
    }

    @Test
    void loadStatements_withBindParams_resolvesCorrectly() throws IOException {
        writeSql("param-stmts.sql", "UPDATE T SET status = :status");
        List<SqlFileLoader.LoadedSql> stmts = loader.loadStatements(
                tempDir.toString(), "param-stmts.sql", Map.of("status", "DONE"));

        assertThat(stmts).hasSize(1);
        assertThat(stmts.get(0).getExecutableSql()).contains("?");
        assertThat(stmts.get(0).getParameterNames()).containsExactly("status");
    }

    @Test
    void loadStatements_emptyStatementsIgnored() throws IOException {
        // Trailing semicolon produces an empty token
        writeSql("trailing.sql", "UPDATE T SET x = 1;");
        List<SqlFileLoader.LoadedSql> stmts = loader.loadStatements(
                tempDir.toString(), "trailing.sql", Map.of());

        assertThat(stmts).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void writeSql(String filename, String content) throws IOException {
        Files.writeString(tempDir.resolve(filename), content);
    }

    private SourceConfig sqlConfig(String sqlFile) {
        SourceConfig config = new SourceConfig();
        config.setName("test-source");
        config.setType("SQL");
        config.setSqlDirectory(tempDir.toString());
        config.setSqlFile(sqlFile);
        return config;
    }
}
