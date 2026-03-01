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
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Writer configuration in the source YAML file.
 *
 * <p>If absent from the YAML, the framework looks for a bean named
 * {@code {sourceName}Writer} in the Spring context (legacy behaviour).</p>
 *
 * <h2>YAML examples</h2>
 *
 * <p>SQL writer (INSERT file):</p>
 * <pre>
 * writer:
 *   type: SQL
 *   sqlDirectory: /opt/sql
 *   sqlFile: insert_orders.sql
 *   onError: SKIP
 *   skipLimit: 10
 * </pre>
 *
 * <p>Java writer (Spring bean):</p>
 * <pre>
 * writer:
 *   type: JAVA
 *   beanName: ordersWriter
 *   onError: FAIL
 * </pre>
 *
 * <p>The SQL file uses bind variables whose names correspond
 * to the fields of the {@code GenericRecord}:</p>
 * <pre>
 *   INSERT INTO ORDERS_OUT (order_id, amount, currency)
 *   VALUES (:order_id, :amount, :currency)
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class WriterConfig {

    /**
     * Writer type: {@code SQL} or {@code JAVA}.
     *
     * <ul>
     *   <li>{@code SQL}  - executes the SQL file in batch, bind variables = fields of {@code GenericRecord}</li>
     *   <li>{@code JAVA} - delegates to the Spring bean named {@code beanName} (must implement {@code ItemWriter})</li>
     * </ul>
     */
    private String type;

    /**
     * Directory containing the SQL file (required if {@code type=SQL}).
     */
    private String sqlDirectory;

    /**
     * Name of the SQL file in {@code sqlDirectory} (required if {@code type=SQL}).
     * Must contain a single INSERT/UPDATE/MERGE/DELETE statement.
     */
    private String sqlFile;

    /**
     * Name of the Spring {@code ItemWriter} bean (required if {@code type=JAVA}).
     */
    private String beanName;

    /**
     * Bean name of the DataSource to use (optional, multi-DB).
     */
    private String dataSourceBean;

    /**
     * Behaviour on row error.
     *
     * <ul>
     *   <li>{@code FAIL} (default) - the job fails immediately on the first error</li>
     *   <li>{@code SKIP} - the row is skipped, the error is logged, the job continues</li>
     * </ul>
     */
    private String onError = "FAIL";

    /**
     * Maximum number of skippable rows (used only if {@code onError=SKIP}).
     * If exceeded, the job fails. Default: 10.
     */
    private int skipLimit = 10;

    /**
     * Indicates whether the writer is configured in fault-tolerant mode.
     *
     * @return {@code true} if {@code onError=SKIP}
     */
    public boolean isSkipOnError() {
        return "SKIP".equalsIgnoreCase(onError);
    }

    /**
     * Validates the writer configuration.
     *
     * @throws IllegalStateException if the configuration is invalid
     */
    public void validate() {
        if (type == null || type.isBlank()) {
            throw new IllegalStateException(
                "writer.type is required (SQL or JAVA)");
        }

        if ("SQL".equalsIgnoreCase(type)) {
            if (sqlDirectory == null || sqlDirectory.isBlank()) {
                throw new IllegalStateException(
                    "writer.sqlDirectory is required when type=SQL");
            }
            if (sqlFile == null || sqlFile.isBlank()) {
                throw new IllegalStateException(
                    "writer.sqlFile is required when type=SQL");
            }
        } else if ("JAVA".equalsIgnoreCase(type)) {
            if (beanName == null || beanName.isBlank()) {
                throw new IllegalStateException(
                    "writer.beanName is required when type=JAVA");
            }
        } else {
            throw new IllegalStateException(
                "writer.type is invalid: '" + type + "'. Accepted values: SQL, JAVA");
        }

        if (!"FAIL".equalsIgnoreCase(onError) && !"SKIP".equalsIgnoreCase(onError)) {
            throw new IllegalStateException(
                "writer.onError is invalid: '" + onError + "'. Accepted values: FAIL, SKIP");
        }

        if (isSkipOnError() && skipLimit <= 0) {
            throw new IllegalStateException(
                "writer.skipLimit must be > 0 when onError=SKIP");
        }
    }
}
