package org.makalisio.gsbatch.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WriterConfigTest {

    // ── validate() — SQL type ────────────────────────────────────────────────

    @Test
    void validate_sqlType_complete_passes() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("insert.sql");
        assertThatNoException().isThrownBy(wc::validate);
    }

    @Test
    void validate_sqlType_missingSqlDirectory_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlFile("insert.sql");
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("sqlDirectory");
    }

    @Test
    void validate_sqlType_missingSqlFile_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("sqlFile");
    }

    // ── validate() — JAVA type ───────────────────────────────────────────────

    @Test
    void validate_javaType_withBeanName_passes() {
        WriterConfig wc = new WriterConfig();
        wc.setType("JAVA");
        wc.setBeanName("myWriter");
        assertThatNoException().isThrownBy(wc::validate);
    }

    @Test
    void validate_javaType_missingBeanName_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("JAVA");
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("beanName");
    }

    // ── validate() — type invalide / absent ──────────────────────────────────

    @Test
    void validate_nullType_throws() {
        WriterConfig wc = new WriterConfig();
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("writer.type is required");
    }

    @Test
    void validate_unknownType_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("FILE");
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("invalid");
    }

    @Test
    void validate_typeIsCaseInsensitive() {
        WriterConfig wc = new WriterConfig();
        wc.setType("sql");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("x.sql");
        assertThatNoException().isThrownBy(wc::validate);
    }

    // ── validate() — onError ─────────────────────────────────────────────────

    @Test
    void validate_invalidOnError_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("x.sql");
        wc.setOnError("INVALID");
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("onError");
    }

    @Test
    void validate_skipOnError_zeroSkipLimit_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("x.sql");
        wc.setOnError("SKIP");
        wc.setSkipLimit(0);
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("skipLimit");
    }

    @Test
    void validate_skipOnError_negativeSkipLimit_throws() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("x.sql");
        wc.setOnError("SKIP");
        wc.setSkipLimit(-5);
        assertThatIllegalStateException()
                .isThrownBy(wc::validate)
                .withMessageContaining("skipLimit");
    }

    @Test
    void validate_skipOnError_positiveSkipLimit_passes() {
        WriterConfig wc = new WriterConfig();
        wc.setType("SQL");
        wc.setSqlDirectory("/sql");
        wc.setSqlFile("x.sql");
        wc.setOnError("SKIP");
        wc.setSkipLimit(10);
        assertThatNoException().isThrownBy(wc::validate);
    }

    // ── isSkipOnError() ──────────────────────────────────────────────────────

    @Test
    void isSkipOnError_skip_returnsTrue() {
        WriterConfig wc = new WriterConfig();
        wc.setOnError("SKIP");
        assertThat(wc.isSkipOnError()).isTrue();
    }

    @Test
    void isSkipOnError_fail_returnsFalse() {
        WriterConfig wc = new WriterConfig();
        wc.setOnError("FAIL");
        assertThat(wc.isSkipOnError()).isFalse();
    }

    @Test
    void isSkipOnError_caseInsensitive() {
        WriterConfig wc = new WriterConfig();
        wc.setOnError("skip");
        assertThat(wc.isSkipOnError()).isTrue();
    }

    @Test
    void isSkipOnError_defaultOnError_returnsFalse() {
        // Default onError is "FAIL"
        assertThat(new WriterConfig().isSkipOnError()).isFalse();
    }
}
