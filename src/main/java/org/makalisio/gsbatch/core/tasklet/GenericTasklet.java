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
 * Tasklet generique pilotee par la configuration YAML de la source.
 *
 * <p>Utilisee pour les steps de pre-processing et post-processing.
 * Le comportement est determine par le {@link StepConfig} :</p>
 *
 * <ul>
 *   <li>{@code enabled=false}  - no-op, retourne immediatement {@code FINISHED}</li>
 *   <li>{@code type=SQL}       - execute toutes les instructions du fichier SQL
 *                               dans la <b>meme transaction</b> (geree par Spring Batch)</li>
 *   <li>{@code type=JAVA}      - delegue au bean Spring nomme {@code beanName}
 *                               (doit implementer {@code Tasklet})</li>
 * </ul>
 *
 * <h2>Multi-statement SQL dans une meme transaction</h2>
 * <p>Spring Batch demarre automatiquement une transaction avant d'appeler
 * {@code execute()}. Toutes les instructions SQL sont executees dans cette
 * transaction. En cas d'exception, Spring Batch effectue le rollback.</p>
 *
 * <pre>
 * -- pre_orders.sql : plusieurs instructions dans la meme transaction
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
     * @param stepConfig         configuration de la step (pre ou post)
     * @param jobParameters      parametres du job pour les bind variables SQL
     * @param sqlFileLoader      loader de fichiers SQL
     * @param defaultDataSource  DataSource principale
     * @param applicationContext contexte Spring (pour les beans JAVA)
     * @param sourceName         nom de la source (pour les logs)
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
                "Type de step inconnu : '" + type + "'. Valeurs acceptees : SQL, JAVA");
        }

        return RepeatStatus.FINISHED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Execution SQL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute toutes les instructions SQL du fichier dans la transaction courante.
     *
     * <p>Les instructions sont executees sequentiellement.
     * La transaction est geree par Spring Batch (demarree avant l'appel a {@code execute()}).
     * Un echec sur n'importe quelle instruction provoque un rollback complet.</p>
     */
    private void executeSql(StepContribution contribution) {
        List<SqlFileLoader.LoadedSql> statements = sqlFileLoader.loadStatements(
                stepConfig.getSqlDirectory(),
                stepConfig.getSqlFile(),
                jobParameters
        );

        if (statements.isEmpty()) {
            log.warn("Source '{}'  - aucune instruction SQL dans {}/{}",
                    sourceName, stepConfig.getSqlDirectory(), stepConfig.getSqlFile());
            return;
        }

        DataSource dataSource = resolveDataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        log.info("Source '{}'  - execution de {} instruction(s) SQL dans la meme transaction",
                sourceName, statements.size());

        int totalAffected = 0;
        for (int i = 0; i < statements.size(); i++) {
            SqlFileLoader.LoadedSql stmt = statements.get(i);
            log.debug("Source '{}'  - instruction [{}/{}] : {}",
                    sourceName, i + 1, statements.size(),
                    abbreviate(stmt.getExecutableSql(), 120));

            int rowsAffected = jdbcTemplate.update(
                    stmt.getExecutableSql(),
                    stmt.getPreparedStatementSetter()
            );

            log.debug("Source '{}'  - instruction [{}/{}] : {} ligne(s) affectee(s)",
                    sourceName, i + 1, statements.size(), rowsAffected);

            totalAffected += rowsAffected;
            contribution.incrementWriteCount(rowsAffected);
        }

        log.info("Source '{}'  - {} instruction(s) executee(s), {} ligne(s) affectee(s) au total",
                sourceName, statements.size(), totalAffected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Delegation Java
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delegue l'execution au bean Spring nomme {@code stepConfig.beanName}.
     * Le bean doit implementer {@code Tasklet}.
     */
    private void executeJava(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        String beanName = stepConfig.getBeanName();
        log.info("Source '{}'  - delegation au bean Java : '{}'", sourceName, beanName);

        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(String.format(
                "Bean '%s' introuvable dans le contexte Spring pour la source '%s'.%n" +
                "Creez un @Component(\"%s\") qui implemente Tasklet dans le backoffice.",
                beanName, sourceName, beanName
            ));
        }

        Tasklet delegate = applicationContext.getBean(beanName, Tasklet.class);
        RepeatStatus status = delegate.execute(contribution, chunkContext);
        log.info("Source '{}'  - bean '{}' execute avec statut : {}", sourceName, beanName, status);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resout la DataSource a utiliser (nommee ou principale).
     */
    private DataSource resolveDataSource() {
        String beanName = stepConfig.getDataSourceBean();
        if (beanName != null && !beanName.isBlank()) {
            log.debug("Source '{}'  - DataSource nommee : '{}'", sourceName, beanName);
            return applicationContext.getBean(beanName, DataSource.class);
        }
        return defaultDataSource;
    }

    /**
     * Tronque une chaine pour les logs.
     */
    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
