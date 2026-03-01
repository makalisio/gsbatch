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
 * Configuration for a pre/post processing step (Tasklet type).
 *
 * <p>Used for {@code preprocessing} and {@code postprocessing} steps
 * in the source YAML file. The step is skipped if {@code enabled=false}.</p>
 *
 * <h2>YAML example</h2>
 * <pre>
 * preprocessing:
 *   enabled: true
 *   type: SQL
 *   sqlDirectory: /opt/sql
 *   sqlFile: pre_orders.sql
 *
 * postprocessing:
 *   enabled: true
 *   type: JAVA
 *   beanName: ordersPostprocessor
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class StepConfig {

    /**
     * Enables or disables this step.
     * If {@code false}, the step executes but does nothing (no-op).
     * Default: {@code false}.
     */
    private boolean enabled = false;

    /**
     * Execution type: {@code SQL} or {@code JAVA}.
     *
     * <ul>
     *   <li>{@code SQL}  - executes the SQL file at {@code sqlDirectory/sqlFile}</li>
     *   <li>{@code JAVA} - delegates to the Spring bean named {@code beanName} (must implement {@code Tasklet})</li>
     * </ul>
     */
    private String type;

    /**
     * Directory containing the SQL file (required if {@code type=SQL}).
     * Can be absolute or relative to the launch directory.
     */
    private String sqlDirectory;

    /**
     * Name of the SQL file in {@code sqlDirectory} (required if {@code type=SQL}).
     *
     * <p>The file may contain multiple statements separated by {@code ;},
     * all executed within the same transaction.</p>
     *
     * <p>Bind variables {@code :paramName} are resolved from {@code jobParameters}.</p>
     */
    private String sqlFile;

    /**
     * Name of the Spring bean to call (required if {@code type=JAVA}).
     * The bean must implement {@code org.springframework.batch.core.step.tasklet.Tasklet}.
     */
    private String beanName;

    /**
     * Bean name of the DataSource to use (optional, multi-DB).
     * Uses the primary DataSource if absent.
     */
    private String dataSourceBean;

    /**
     * Validates the configuration of this step.
     *
     * @param stepName step name for error messages ("preprocessing" or "postprocessing")
     * @throws IllegalStateException if the configuration is invalid
     */
    public void validate(String stepName) {
        if (!enabled) return;

        if (type == null || type.isBlank()) {
            throw new IllegalStateException(
                stepName + ".type is required (SQL or JAVA)");
        }

        if ("SQL".equalsIgnoreCase(type)) {
            if (sqlDirectory == null || sqlDirectory.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".sqlDirectory is required when type=SQL");
            }
            if (sqlFile == null || sqlFile.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".sqlFile is required when type=SQL");
            }
        } else if ("JAVA".equalsIgnoreCase(type)) {
            if (beanName == null || beanName.isBlank()) {
                throw new IllegalStateException(
                    stepName + ".beanName is required when type=JAVA");
            }
        } else {
            throw new IllegalStateException(
                stepName + ".type is invalid: '" + type + "'. Accepted values: SQL, JAVA");
        }
    }
}
