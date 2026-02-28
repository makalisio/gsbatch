package org.makalisio.gsbatch.core.tasklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.makalisio.gsbatch.core.model.StepConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenericTaskletTest {

    @Mock SqlFileLoader sqlFileLoader;
    @Mock DataSource dataSource;
    @Mock ApplicationContext applicationContext;
    @Mock StepContribution contribution;
    @Mock ChunkContext chunkContext;
    @Mock StepContext stepContext;

    // ── Étape désactivée ─────────────────────────────────────────────────────

    @Test
    void execute_disabledStep_returnsFinishedImmediately() throws Exception {
        StepConfig step = new StepConfig();  // enabled=false by default

        GenericTasklet tasklet = tasklet(step);
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(sqlFileLoader, applicationContext, contribution, chunkContext);
    }

    @Test
    void execute_nullStepConfig_returnsFinishedImmediately() throws Exception {
        GenericTasklet tasklet = new GenericTasklet(
                null, Map.of(), sqlFileLoader, dataSource, applicationContext, "test");

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(sqlFileLoader, applicationContext, contribution, chunkContext);
    }

    // ── Type SQL ─────────────────────────────────────────────────────────────

    @Test
    void execute_sqlType_emptyStatements_returnsFinished() throws Exception {
        StepConfig step = enabledSqlStep("/sql", "pre.sql");
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");
        when(sqlFileLoader.loadStatements("/sql", "pre.sql", Map.of()))
                .thenReturn(List.of());

        GenericTasklet tasklet = tasklet(step);
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(contribution);  // incrementWriteCount not called with empty list
    }

    @Test
    void execute_sqlType_oneStatement_executesAndReturnsFinished() throws Exception {
        StepConfig step = enabledSqlStep("/sql", "pre.sql");
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");

        SqlFileLoader.LoadedSql loadedSql = buildLoadedSql("UPDATE T SET x = 1");
        when(sqlFileLoader.loadStatements("/sql", "pre.sql", Map.of()))
                .thenReturn(List.of(loadedSql));

        try (MockedConstruction<JdbcTemplate> mocked =
                     Mockito.mockConstruction(JdbcTemplate.class, (mock, ctx) ->
                             when(mock.update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class)))
                                     .thenReturn(5))) {

            GenericTasklet tasklet = tasklet(step);
            RepeatStatus status = tasklet.execute(contribution, chunkContext);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            verify(contribution).incrementWriteCount(5);
            JdbcTemplate jdbcTemplate = mocked.constructed().get(0);
            verify(jdbcTemplate).update(eq("UPDATE T SET x = 1"),
                    any(org.springframework.jdbc.core.PreparedStatementSetter.class));
        }
    }

    @Test
    void execute_sqlType_twoStatements_executesBothAndAccumulatesWriteCount() throws Exception {
        StepConfig step = enabledSqlStep("/sql", "multi.sql");
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");

        SqlFileLoader.LoadedSql stmt1 = buildLoadedSql("UPDATE A SET x = 1");
        SqlFileLoader.LoadedSql stmt2 = buildLoadedSql("INSERT INTO LOG VALUES ('done')");
        when(sqlFileLoader.loadStatements("/sql", "multi.sql", Map.of()))
                .thenReturn(List.of(stmt1, stmt2));

        try (MockedConstruction<JdbcTemplate> mocked =
                     Mockito.mockConstruction(JdbcTemplate.class, (mock, ctx) ->
                             when(mock.update(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class)))
                                     .thenReturn(3))) {

            GenericTasklet tasklet = tasklet(step);
            tasklet.execute(contribution, chunkContext);

            // 3 rows for each of the 2 statements = 6 total
            verify(contribution, times(2)).incrementWriteCount(3);
        }
    }

    // ── Type JAVA ────────────────────────────────────────────────────────────

    @Test
    void execute_javaType_delegatesToBean() throws Exception {
        StepConfig step = enabledJavaStep("myTasklet");
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");

        Tasklet delegateTasklet = mock(Tasklet.class);
        when(delegateTasklet.execute(contribution, chunkContext)).thenReturn(RepeatStatus.FINISHED);
        when(applicationContext.containsBean("myTasklet")).thenReturn(true);
        when(applicationContext.getBean("myTasklet", Tasklet.class)).thenReturn(delegateTasklet);

        GenericTasklet tasklet = tasklet(step);
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(delegateTasklet).execute(contribution, chunkContext);
    }

    @Test
    void execute_javaType_beanNotFound_throwsIllegalState() {
        StepConfig step = enabledJavaStep("ghostTasklet");
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");
        when(applicationContext.containsBean("ghostTasklet")).thenReturn(false);

        GenericTasklet tasklet = tasklet(step);

        assertThatIllegalStateException()
                .isThrownBy(() -> tasklet.execute(contribution, chunkContext))
                .withMessageContaining("ghostTasklet");
    }

    // ── Type inconnu ─────────────────────────────────────────────────────────

    @Test
    void execute_unknownType_throwsIllegalState() {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("UNKNOWN");

        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getStepName()).thenReturn("testStep");

        GenericTasklet tasklet = tasklet(step);

        assertThatIllegalStateException()
                .isThrownBy(() -> tasklet.execute(contribution, chunkContext))
                .withMessageContaining("UNKNOWN");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GenericTasklet tasklet(StepConfig step) {
        return new GenericTasklet(step, Map.of(), sqlFileLoader, dataSource, applicationContext, "test-source");
    }

    private StepConfig enabledSqlStep(String dir, String file) {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("SQL");
        step.setSqlDirectory(dir);
        step.setSqlFile(file);
        return step;
    }

    private StepConfig enabledJavaStep(String beanName) {
        StepConfig step = new StepConfig();
        step.setEnabled(true);
        step.setType("JAVA");
        step.setBeanName(beanName);
        return step;
    }

    /**
     * Creates a LoadedSql via reflection since its constructor is package-private.
     */
    private SqlFileLoader.LoadedSql buildLoadedSql(String sql) throws Exception {
        Constructor<SqlFileLoader.LoadedSql> ctor = SqlFileLoader.LoadedSql.class
                .getDeclaredConstructor(
                        String.class,
                        org.springframework.jdbc.core.PreparedStatementSetter.class,
                        List.class);
        ctor.setAccessible(true);
        // Explicit type required — lambda can't be inferred inside Object varargs
        org.springframework.jdbc.core.PreparedStatementSetter noOpSetter = ps -> {};
        return ctor.newInstance(sql, noOpSetter, List.of());
    }
}
