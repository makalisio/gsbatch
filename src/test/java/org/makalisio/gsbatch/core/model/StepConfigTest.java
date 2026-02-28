package org.makalisio.gsbatch.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StepConfigTest {

    @Test
    void validate_disabled_doesNotThrow() {
        StepConfig step = new StepConfig();
        step.setEnabled(false);
        assertThatNoException().isThrownBy(() -> step.validate("preprocessing"));
    }

    @Test
    void validate_defaultState_doesNotThrow() {
        // enabled=false by default
        StepConfig step = new StepConfig();
        assertThatNoException().isThrownBy(() -> step.validate("postprocessing"));
    }

    @Test
    void validate_enabled_noType_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("preprocessing"))
                .withMessageContaining("preprocessing.type is required");
    }

    @Test
    void validate_sqlType_missingSqlDirectory_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("SQL");
        step.setSqlFile("orders.sql");
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("preprocessing"))
                .withMessageContaining("sqlDirectory");
    }

    @Test
    void validate_sqlType_missingSqlFile_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("SQL");
        step.setSqlDirectory("/opt/sql");
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("preprocessing"))
                .withMessageContaining("sqlFile");
    }

    @Test
    void validate_sqlType_complete_passes() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("SQL");
        step.setSqlDirectory("/opt/sql");
        step.setSqlFile("pre.sql");
        assertThatNoException().isThrownBy(() -> step.validate("preprocessing"));
    }

    @Test
    void validate_javaType_missingBeanName_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("JAVA");
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("postprocessing"))
                .withMessageContaining("beanName");
    }

    @Test
    void validate_javaType_withBeanName_passes() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("JAVA");
        step.setBeanName("myTasklet");
        assertThatNoException().isThrownBy(() -> step.validate("postprocessing"));
    }

    @Test
    void validate_unknownType_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("UNKNOWN");
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("preprocessing"))
                .withMessageContaining("invalid");
    }

    @Test
    void validate_typeIsCaseInsensitive_sql() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("sql");
        step.setSqlDirectory("/sql");
        step.setSqlFile("f.sql");
        assertThatNoException().isThrownBy(() -> step.validate("pre"));
    }

    @Test
    void validate_typeIsCaseInsensitive_java() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("java");
        step.setBeanName("myBean");
        assertThatNoException().isThrownBy(() -> step.validate("post"));
    }

    @Test
    void validate_errorMessageIncludesStepName() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        assertThatIllegalStateException()
                .isThrownBy(() -> step.validate("postprocessing"))
                .withMessageContaining("postprocessing");
    }
}
