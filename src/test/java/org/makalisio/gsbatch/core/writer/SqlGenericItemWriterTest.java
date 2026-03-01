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
package org.makalisio.gsbatch.core.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlGenericItemWriterTest {

    private static final String INSERT_SQL = "INSERT INTO ORDERS (id, amount) VALUES (:id, :amount)";

    // ── write() — chunk vide ─────────────────────────────────────────────────

    @Test
    void write_emptyChunk_doesNotCallBatchUpdate() throws Exception {
        SqlFileLoader sqlFileLoader = mock(SqlFileLoader.class);
        DataSource dataSource = mock(DataSource.class);
        when(sqlFileLoader.readRawSql(any(), any())).thenReturn(INSERT_SQL);

        try (MockedConstruction<NamedParameterJdbcTemplate> mocked =
                     Mockito.mockConstruction(NamedParameterJdbcTemplate.class)) {

            SqlGenericItemWriter writer = createWriter(sqlFileLoader, dataSource);
            writer.write(new Chunk<>(List.of()));

            NamedParameterJdbcTemplate mockTemplate = mocked.constructed().get(0);
            verifyNoInteractions(mockTemplate);
        }
    }

    // ── write() — un seul enregistrement ────────────────────────────────────

    @Test
    void write_singleRecord_callsBatchUpdateOnce() throws Exception {
        SqlFileLoader sqlFileLoader = mock(SqlFileLoader.class);
        DataSource dataSource = mock(DataSource.class);
        when(sqlFileLoader.readRawSql(any(), any())).thenReturn(INSERT_SQL);

        try (MockedConstruction<NamedParameterJdbcTemplate> mocked =
                     Mockito.mockConstruction(NamedParameterJdbcTemplate.class, (mock, ctx) ->
                             when(mock.batchUpdate(eq(INSERT_SQL), any(SqlParameterSource[].class)))
                                     .thenReturn(new int[]{1}))) {

            GenericRecord record = new GenericRecord();
            record.put("id", 42);
            record.put("amount", 100.0);

            SqlGenericItemWriter writer = createWriter(sqlFileLoader, dataSource);
            writer.write(new Chunk<>(List.of(record)));

            NamedParameterJdbcTemplate mockTemplate = mocked.constructed().get(0);
            verify(mockTemplate).batchUpdate(eq(INSERT_SQL), any(SqlParameterSource[].class));
        }
    }

    // ── write() — plusieurs enregistrements ─────────────────────────────────

    @Test
    void write_multipleRecords_passesBatchParamsOfCorrectSize() throws Exception {
        SqlFileLoader sqlFileLoader = mock(SqlFileLoader.class);
        DataSource dataSource = mock(DataSource.class);
        when(sqlFileLoader.readRawSql(any(), any())).thenReturn(INSERT_SQL);

        try (MockedConstruction<NamedParameterJdbcTemplate> mocked =
                     Mockito.mockConstruction(NamedParameterJdbcTemplate.class, (mock, ctx) -> {
                         when(mock.batchUpdate(eq(INSERT_SQL), any(SqlParameterSource[].class)))
                                 .thenAnswer(invoc -> {
                                     SqlParameterSource[] params = invoc.getArgument(1);
                                     return new int[params.length];
                                 });
                     })) {

            List<GenericRecord> records = List.of(
                    recordWith("id", 1, "amount", 50.0),
                    recordWith("id", 2, "amount", 75.0),
                    recordWith("id", 3, "amount", 25.0)
            );

            SqlGenericItemWriter writer = createWriter(sqlFileLoader, dataSource);
            writer.write(new Chunk<>(records));

            NamedParameterJdbcTemplate mockTemplate = mocked.constructed().get(0);
            verify(mockTemplate).batchUpdate(eq(INSERT_SQL),
                    argThat((SqlParameterSource[] params) -> params.length == 3));
        }
    }

    // ── Chargement SQL au constructeur ───────────────────────────────────────

    @Test
    void constructor_loadsSqlOnce() {
        SqlFileLoader sqlFileLoader = mock(SqlFileLoader.class);
        DataSource dataSource = mock(DataSource.class);
        when(sqlFileLoader.readRawSql("dir", "file.sql")).thenReturn(INSERT_SQL);

        try (MockedConstruction<NamedParameterJdbcTemplate> ignored =
                     Mockito.mockConstruction(NamedParameterJdbcTemplate.class)) {

            createWriter(sqlFileLoader, dataSource);

            verify(sqlFileLoader, times(1)).readRawSql("dir", "file.sql");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SqlGenericItemWriter createWriter(SqlFileLoader sqlFileLoader, DataSource dataSource) {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("dir");
        wc.setSqlFile("file.sql");
        return new SqlGenericItemWriter(wc, sqlFileLoader, dataSource, "test-source");
    }

    private GenericRecord recordWith(Object... keyValues) {
        GenericRecord record = new GenericRecord();
        for (int i = 0; i < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }
}
