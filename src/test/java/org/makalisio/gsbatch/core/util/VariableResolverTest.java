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
package org.makalisio.gsbatch.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class VariableResolverTest {

    // ── resolveBindVariables() ───────────────────────────────────────────────

    @Test
    void resolveBindVariables_replacesKnownParam() {
        String result = VariableResolver.resolveBindVariables(
                "status = :status", Map.of("status", "NEW"), "ctx");
        assertThat(result).isEqualTo("status = NEW");
    }

    @Test
    void resolveBindVariables_missingParam_throws() {
        assertThatIllegalStateException()
                .isThrownBy(() -> VariableResolver.resolveBindVariables(":status", Map.of(), "ctx"))
                .withMessageContaining("Bind variable not found");
    }

    @Test
    void resolveBindVariables_nullValue_throwsInsteadOfNpe() {
        Map<String, Object> params = new HashMap<>();
        params.put("status", null);
        assertThatIllegalStateException()
                .isThrownBy(() -> VariableResolver.resolveBindVariables(":status", params, "ctx"))
                .withMessageContaining("resolved to null");
    }

    @Test
    void resolveBindVariables_doesNotCorruptPrefixCollidingNames() {
        Map<String, Object> params = Map.of("status", "A", "statusCode", "B");
        String result = VariableResolver.resolveBindVariables(":status/:statusCode", params, "ctx");
        assertThat(result).isEqualTo("A/B");
    }

    @Test
    void resolveBindVariables_nullInput_returnsNull() {
        assertThat(VariableResolver.resolveBindVariables(null, Map.of(), "ctx")).isNull();
    }

    // ── resolveEnvVariables() ────────────────────────────────────────────────

    @Test
    void resolveEnvVariables_missingVar_throws() {
        assertThatIllegalStateException()
                .isThrownBy(() -> VariableResolver.resolveEnvVariables(
                        "${THIS_VAR_DOES_NOT_EXIST_12345}", "ctx"))
                .withMessageContaining("Environment variable not found");
    }

    @Test
    void resolveEnvVariables_noPlaceholder_returnsUnchanged() {
        assertThat(VariableResolver.resolveEnvVariables("plain value", "ctx")).isEqualTo("plain value");
    }

    @Test
    void resolveEnvVariables_nullInput_returnsNull() {
        assertThat(VariableResolver.resolveEnvVariables(null, "ctx")).isNull();
    }

    // ── resolve() ─────────────────────────────────────────────────────────────

    @Test
    void resolve_combinesBindThenEnv() {
        String result = VariableResolver.resolve(":status", Map.of("status", "NEW"), "ctx");
        assertThat(result).isEqualTo("NEW");
    }

    @Test
    void resolve_nullInput_returnsNull() {
        assertThat(VariableResolver.resolve(null, Map.of(), "ctx")).isNull();
    }
}
