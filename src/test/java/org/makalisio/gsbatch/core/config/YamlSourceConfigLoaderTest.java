package org.makalisio.gsbatch.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.exception.ConfigurationLoadException;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for YamlSourceConfigLoader.
 *
 * Note: @Cacheable is a Spring AOP proxy — instantiating the class directly
 * bypasses caching, which is the correct approach for unit testing the logic.
 */
class YamlSourceConfigLoaderTest {

    private YamlSourceConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlSourceConfigLoader(new DefaultResourceLoader());
    }

    // ── Chargement normal ────────────────────────────────────────────────────

    @Test
    void load_validCsvSource_returnsConfiguredSourceConfig() {
        SourceConfig config = loader.load("test-csv");
        assertThat(config).isNotNull();
        assertThat(config.getName()).isEqualTo("test-csv");
        assertThat(config.getType()).isEqualToIgnoringCase("CSV");
        assertThat(config.getColumns()).hasSize(2);
    }

    @Test
    void load_validSqlSource_returnsConfiguredSourceConfig() {
        SourceConfig config = loader.load("test-sql");
        assertThat(config).isNotNull();
        assertThat(config.getName()).isEqualTo("test-sql");
        assertThat(config.getType()).isEqualToIgnoringCase("SQL");
        assertThat(config.getSqlDirectory()).isEqualTo("/opt/sql");
        assertThat(config.getSqlFile()).isEqualTo("orders.sql");
    }

    @Test
    void load_yamlWithoutName_setsNameFromSourceName() {
        // Both test YAMLs don't have a "name:" field — loader should set it automatically
        SourceConfig config = loader.load("test-csv");
        assertThat(config.getName()).isEqualTo("test-csv");
    }

    // ── Validation du sourceName ─────────────────────────────────────────────

    @Test
    void load_nullSourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load(null))
                .withMessageContaining("Source name cannot be null or blank");
    }

    @Test
    void load_blankSourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load("  "));
    }

    @Test
    void load_emptySourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load(""));
    }

    // ── Protection contre path traversal ────────────────────────────────────

    @Test
    void load_dotDotInSourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load("../secret"))
                .withMessageContaining("invalid characters");
    }

    @Test
    void load_slashInSourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load("etc/passwd"))
                .withMessageContaining("invalid characters");
    }

    @Test
    void load_backslashInSourceName_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.load("windows\\path"))
                .withMessageContaining("invalid characters");
    }

    // ── Fichier introuvable ──────────────────────────────────────────────────

    @Test
    void load_nonExistentSource_throwsConfigurationLoadException() {
        assertThatThrownBy(() -> loader.load("does-not-exist-xyz"))
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("does-not-exist-xyz");
    }
}
