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
package org.makalisio.gsbatch.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the gsbatch-source-config.schema.json JSON Schema in sync with both
 * {@code SourceConfig.validate()} (via the real ingestion test fixtures) and
 * with itself (via hand-built invalid documents that must stay rejected).
 *
 * <p>This does not replace {@code SourceConfigTest}: it guards the schema
 * file used for editor autocompletion/validation, which has no other
 * automated coverage since it is not read by any Java code at runtime.</p>
 */
class SourceConfigSchemaTest {

    private static JsonSchema schema;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Yaml YAML = new Yaml();

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = SourceConfigSchemaTest.class.getClassLoader()
                .getResourceAsStream("schema/gsbatch-source-config.schema.json")) {
            assertThat(in).as("schema/gsbatch-source-config.schema.json must be on the classpath").isNotNull();
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            schema = factory.getSchema(in);
        }
    }

    // ── Real ingestion fixtures must validate cleanly ────────────────────────

    @ParameterizedTest(name = "{0} validates against the schema")
    @MethodSource("ingestionYamlFixtures")
    @DisplayName("existing ingestion/*.yml fixtures satisfy the schema")
    void ingestionFixture_isValid(Path yamlFile) throws IOException {
        JsonNode node = yamlToJsonNode(yamlFile);
        Set<ValidationMessage> errors = schema.validate(node);
        assertThat(errors).as("errors for %s", yamlFile.getFileName()).isEmpty();
    }

    static Stream<Path> ingestionYamlFixtures() throws IOException, URISyntaxException {
        Path dir = Paths.get(SourceConfigSchemaTest.class.getClassLoader()
                .getResource("ingestion").toURI());
        return Files.list(dir).filter(p -> p.toString().endsWith(".yml"));
    }

    // ── Regression guards: the schema must keep rejecting invalid configs ───

    @Test
    @DisplayName("CSV source without columns is rejected")
    void csv_missingColumns_isRejected() {
        assertInvalid("""
                type: CSV
                path: /tmp/x.csv
                """);
    }

    @Test
    @DisplayName("REST column without type is rejected")
    void rest_columnMissingType_isRejected() {
        assertInvalid("""
                type: REST
                rest:
                  url: https://api.example.com/orders
                columns:
                  - name: orderId
                """);
    }

    @Test
    @DisplayName("REST auth type API_KEY without apiKey is rejected")
    void rest_apiKeyAuthMissingKey_isRejected() {
        assertInvalid("""
                type: REST
                rest:
                  url: https://api.example.com/orders
                  auth:
                    type: API_KEY
                """);
    }

    @Test
    @DisplayName("REST CURSOR pagination without cursorPath is rejected")
    void rest_cursorPaginationMissingCursorPath_isRejected() {
        assertInvalid("""
                type: REST
                rest:
                  url: https://api.example.com/orders
                  pagination:
                    strategy: CURSOR
                """);
    }

    @Test
    @DisplayName("configVersion 1 is accepted")
    void configVersion_one_isAccepted() {
        assertValid("""
                type: CSV
                configVersion: 1
                path: /tmp/x.csv
                columns:
                  - name: id
                """);
    }

    @Test
    @DisplayName("an unsupported configVersion is rejected")
    void configVersion_unsupported_isRejected() {
        assertInvalid("""
                type: CSV
                configVersion: 2
                path: /tmp/x.csv
                columns:
                  - name: id
                """);
    }

    @Test
    @DisplayName("SOAP request template without a recognized envelope tag is rejected")
    void soap_requestTemplateMissingEnvelope_isRejected() {
        assertInvalid("""
                type: SOAP
                soap:
                  endpoint: https://api.example.com/TradeService
                  soapAction: GetTrades
                  requestTemplate: "<notAnEnvelope/>"
                  dataPath: "//trade"
                """);
    }

    @Test
    @DisplayName("an unknown key is rejected (additionalProperties: false)")
    void unknownKey_isRejected() {
        assertInvalid("""
                type: REST
                rest:
                  url: https://api.example.com/orders
                  datapath: "$.orders"
                """);
    }

    @Test
    @DisplayName("a fully valid REST source is accepted")
    void rest_valid_isAccepted() {
        assertValid("""
                type: REST
                rest:
                  url: https://api.example.com/orders
                  pagination:
                    strategy: CURSOR
                    cursorPath: "$.meta.nextCursor"
                    cursorParam: cursor
                columns:
                  - name: orderId
                    type: STRING
                """);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertInvalid(String yaml) {
        Set<ValidationMessage> errors = schema.validate(yamlToJsonNode(yaml));
        assertThat(errors).isNotEmpty();
    }

    private void assertValid(String yaml) {
        Set<ValidationMessage> errors = schema.validate(yamlToJsonNode(yaml));
        assertThat(errors).isEmpty();
    }

    private JsonNode yamlToJsonNode(String yamlContent) {
        Object parsed = YAML.load(yamlContent);
        return JSON.valueToTree(parsed);
    }

    private JsonNode yamlToJsonNode(Path yamlFile) throws IOException {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object parsed = YAML.load(in);
            return JSON.valueToTree(parsed);
        }
    }
}
