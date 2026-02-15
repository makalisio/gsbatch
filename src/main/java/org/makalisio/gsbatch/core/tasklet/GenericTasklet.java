package org.makalisio.gsbatch.core.tasklet;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.StepConfig;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Generic tasklet driven by the YAML source configuration.
 *
 * <p>Used for pre-processing and post-processing steps.
 * Behaviour is determined by the {@link StepConfig}:</p>
 *
 * <ul>
 *   <li>{@code enabled=false}  - no-op, returns {@code FINISHED} immediately</li>
 *   <li>{@code type=SQL}       - executes all statements from the SQL file
 *                               within the <b>same transaction</b> (managed by Spring Batch)</li>
 *   <li>{@code type=JAVA}      - delegates to the Spring bean named {@code beanName}
 *                               (must implement {@code Tasklet})</li>
 * </ul>
 *
 * <h2>Multi-statement SQL within the same transaction</h2>
 * <p>Spring Batch automatically starts a transaction before calling
 * {@code execute()}. All SQL statements are executed within this
 * transaction. In case of an exception, Spring Batch performs a rollback.</p>
 *
 * <pre>
 * -- pre_orders.sql: multiple statements in the same transaction
 * UPDATE ORDERS SET status = 'PROCESSING'
 * WHERE status = :status AND order_date = :process_date;
 *
 * INSERT INTO BATCH_LOG (source, process_date, action)
 * VALUES ('orders', :process_date, 'PRE_PROCESSING_START');
 * </pre>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
public class GenericTasklet implements Tasklet {

    private final StepConfig stepConfig;
    private final Map<String, Object> jobParameters;
    private final SqlFileLoader sqlFileLoader;
    private final DataSource defaultDataSource;
    private final ApplicationContext applicationContext;
    private final String sourceName;

    /**
     * @param stepConfig         step configuration (pre or post)
     * @param jobParameters      job parameters for SQL bind variables
     * @param sqlFileLoader      SQL file loader
     * @param defaultDataSource  primary DataSource
     * @param applicationContext Spring context (for JAVA beans)
     * @param sourceName         source name (for logging)
     */
    public GenericTasklet(StepConfig stepConfig,
                          Map<String, Object> jobParameters,
                          SqlFileLoader sqlFileLoader,
                          DataSource defaultDataSource,
                          ApplicationContext applicationContext,
                          String sourceName) {
        this.stepConfig = stepConfig;
        this.jobParameters = jobParameters;
        this.sqlFileLoader = sqlFileLoader;
        this.defaultDataSource = defaultDataSource;
        this.applicationContext = applicationContext;
        this.sourceName = sourceName;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {

        if (stepConfig == null || !stepConfig.isEnabled()) {
            log.debug("Source '{}'  - step disabled, skipping", sourceName);
            return RepeatStatus.FINISHED;
        }

        String type = stepConfig.getType();
        log.info("Source '{}'  - executing {} step (type={})",
                sourceName,
                chunkContext.getStepContext().getStepName(),
                type);

        if ("SQL".equalsIgnoreCase(type)) {
            executeSql(contribution);
        } else if ("JAVA".equalsIgnoreCase(type)) {
            executeJava(contribution, chunkContext);
        } else {
            throw new IllegalStateException(
                "Unknown step type: '" + type + "'. Accepted values: SQL, JAVA");
        }

        return RepeatStatus.FINISHED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SQL execution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes all SQL statements from the file within the current transaction.
     *
     * <p>Statements are executed sequentially.
     * The transaction is managed by Spring Batch (started before the call to {@code execute()}).
     * A failure on any statement triggers a full rollback.</p>
     */
    private void executeSql(StepContribution contribution) {
        List<SqlFileLoader.LoadedSql> statements = sqlFileLoader.loadStatements(
                stepConfig.getSqlDirectory(),
                stepConfig.getSqlFile(),
                jobParameters
        );

        if (statements.isEmpty()) {
            log.warn("Source '{}'  - no SQL statement found in {}/{}",
                    sourceName, stepConfig.getSqlDirectory(), stepConfig.getSqlFile());
            return;
        }

        DataSource dataSource = resolveDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        log.info("Source '{}'  - executing {} SQL statement(s) within the same transaction",
                sourceName, statements.size());

        int totalAffected = 0;
        for (int i = 0; i < statements.size(); i++) {
            SqlFileLoader.LoadedSql stmt = statements.get(i);
            log.debug("Source '{}'  - statement [{}/{}]: {}",
                    sourceName, i + 1, statements.size(),
                    abbreviate(stmt.getExecutableSql(), 120));

            int rowsAffected = jdbcTemplate.update(
                    stmt.getExecutableSql(),
                    stmt.getPreparedStatementSetter()
            );

            log.debug("Source '{}'  - statement [{}/{}]: {} row(s) affected",
                    sourceName, i + 1, statements.size(), rowsAffected);

            totalAffected += rowsAffected;
            contribution.incrementWriteCount(rowsAffected);
        }

        log.info("Source '{}'  - {} statement(s) executed, {} row(s) affected in total",
                sourceName, statements.size(), totalAffected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Java delegation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delegates execution to the Spring bean named {@code stepConfig.beanName}.
     * The bean must implement {@code Tasklet}.
     */
    private void executeJava(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        String beanName = stepConfig.getBeanName();
        log.info("Source '{}'  - delegating to Java bean: '{}'", sourceName, beanName);

        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(String.format(
                "Bean '%s' not found in the Spring context for source '%s'.%n" +
                "Create a @Component(\"%s\") implementing Tasklet in the backoffice.",
                beanName, sourceName, beanName
            ));
        }

        Tasklet delegate = applicationContext.getBean(beanName, Tasklet.class);
        RepeatStatus status = delegate.execute(contribution, chunkContext);
        log.info("Source '{}'  - bean '{}' executed with status: {}", sourceName, beanName, status);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the DataSource to use (named or primary).
     */
    private DataSource resolveDataSource() {
        String beanName = stepConfig.getDataSourceBean();
        if (beanName != null && !beanName.isBlank()) {
            log.debug("Source '{}'  - named DataSource: '{}'", sourceName, beanName);
            return applicationContext.getBean(beanName, DataSource.class);
        }
        return defaultDataSource;
    }

    /**
     * Truncates a string for logging purposes.
     */
    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
