package org.makalisio.gsbatch.core.job;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.config.YamlSourceConfigLoader;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.processor.GenericItemProcessorFactory;
import org.makalisio.gsbatch.core.reader.GenericItemReaderFactory;
import org.makalisio.gsbatch.core.reader.SqlFileLoader;
import org.makalisio.gsbatch.core.tasklet.GenericTasklet;
import org.makalisio.gsbatch.core.writer.GenericItemWriterFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Map;

/**
 * Configuration du job générique d'ingestion.
 *
 * <h2>Cycle de vie des beans</h2>
 * <pre>
 *   Démarrage application
 *     └─ genericIngestionJob              (Singleton)
 *          ├─ genericPreprocessingStep    (@JobScope — no-op si disabled)
 *          ├─ genericIngestionStep        (@JobScope — chunk reader/processor/writer)
 *          └─ genericPostprocessingStep   (@JobScope — no-op si disabled)
 * </pre>
 *
 * <h2>Steps pre/post processing</h2>
 * <p>Toujours incluses dans le job mais sont des no-ops si {@code enabled=false}.
 * Cela permet de ne pas reconstruire la définition du job selon la configuration.</p>
 *
 * <h2>Writer fault-tolerance</h2>
 * <p>Si {@code writer.onError=SKIP} dans le YAML, la step ingestion est configurée
 * en mode {@code faultTolerant} avec {@code skipLimit} configurable.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Configuration
public class GenericIngestionJobConfig {

    private final YamlSourceConfigLoader configLoader;
    private final GenericItemReaderFactory readerFactory;
    private final GenericItemProcessorFactory processorFactory;
    private final GenericItemWriterFactory writerFactory;
    private final SqlFileLoader sqlFileLoader;
    private final DataSource defaultDataSource;
    private final ApplicationContext applicationContext;

    /**
     * @param configLoader       loader de configuration YAML
     * @param readerFactory      factory de readers
     * @param processorFactory   factory de processors
     * @param writerFactory      factory de writers
     * @param sqlFileLoader      loader de fichiers SQL (pour pre/post processing)
     * @param defaultDataSource  DataSource principale
     * @param applicationContext contexte Spring (pour les beans JAVA)
     */
    public GenericIngestionJobConfig(YamlSourceConfigLoader configLoader,
                                     GenericItemReaderFactory readerFactory,
                                     GenericItemProcessorFactory processorFactory,
                                     GenericItemWriterFactory writerFactory,
                                     SqlFileLoader sqlFileLoader,
                                     DataSource defaultDataSource,
                                     ApplicationContext applicationContext) {
        this.configLoader = configLoader;
        this.readerFactory = readerFactory;
        this.processorFactory = processorFactory;
        this.writerFactory = writerFactory;
        this.sqlFileLoader = sqlFileLoader;
        this.defaultDataSource = defaultDataSource;
        this.applicationContext = applicationContext;
        log.info("GenericIngestionJobConfig initialized");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JOB
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Job générique avec 3 steps : pre-processing → ingestion chunk → post-processing.
     * Les steps pre/post sont des no-ops si {@code enabled=false} dans le YAML.
     *
     * @param jobRepository              le dépôt Spring Batch
     * @param genericPreprocessingStep   step de pre-processing (proxy @JobScope)
     * @param genericIngestionStep       step chunk principale (proxy @JobScope)
     * @param genericPostprocessingStep  step de post-processing (proxy @JobScope)
     * @return le job configuré
     */
    @Bean
    public Job genericIngestionJob(JobRepository jobRepository,
                                   Step genericPreprocessingStep,
                                   Step genericIngestionStep,
                                   Step genericPostprocessingStep) {
        log.debug("Building genericIngestionJob");
        return new JobBuilder("genericIngestionJob", jobRepository)
                .start(genericPreprocessingStep)
                .next(genericIngestionStep)
                .next(genericPostprocessingStep)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PRE-PROCESSING STEP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step de pre-processing (Tasklet).
     * No-op si {@code preprocessing.enabled=false} dans le YAML.
     *
     * @param jobRepository  le dépôt Spring Batch
     * @param txManager      gestionnaire de transaction
     * @param sourceName     nom de la source (depuis jobParameters)
     * @param jobParameters  tous les paramètres du job
     * @return la step configurée
     */
    @Bean
    @JobScope
    public Step genericPreprocessingStep(
            JobRepository jobRepository,
            PlatformTransactionManager txManager,
            @Value("#{jobParameters['sourceName']}") String sourceName,
            @Value("#{jobParameters}") Map<String, Object> jobParameters) {

        SourceConfig config = configLoader.load(sourceName);
        Tasklet tasklet = new GenericTasklet(
                config.getPreprocessing(),
                Collections.unmodifiableMap(jobParameters),
                sqlFileLoader,
                defaultDataSource,
                applicationContext,
                sourceName
        );

        boolean enabled = config.getPreprocessing() != null && config.getPreprocessing().isEnabled();
        log.info("Building preprocessing step for '{}' (enabled={})", sourceName, enabled);

        return new StepBuilder("genericPreprocessingStep-" + sourceName, jobRepository)
                .tasklet(tasklet, txManager)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  INGESTION STEP (chunk)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step principale de lecture/traitement/écriture en mode chunk.
     *
     * <p>Si {@code writer.onError=SKIP}, la step est configurée en mode
     * {@code faultTolerant} avec {@code skipLimit} depuis le YAML.</p>
     *
     * @param jobRepository              le dépôt Spring Batch
     * @param txManager                  gestionnaire de transaction
     * @param genericIngestionReader     reader (proxy @StepScope)
     * @param genericIngestionProcessor  processor (proxy @StepScope)
     * @param genericIngestionWriter     writer (proxy @StepScope)
     * @param sourceName                 nom de la source (depuis jobParameters)
     * @return la step configurée
     */
    @Bean
    @JobScope
    public Step genericIngestionStep(
            JobRepository jobRepository,
            PlatformTransactionManager txManager,
            ItemStreamReader<GenericRecord> genericIngestionReader,
            ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor,
            ItemWriter<GenericRecord> genericIngestionWriter,
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        SourceConfig config = configLoader.load(sourceName);
        int chunkSize = config.getChunkSize();

        log.info("Building ingestion step for '{}' (chunkSize={})", sourceName, chunkSize);

        SimpleStepBuilder<GenericRecord, GenericRecord> stepBuilder =
                new StepBuilder("genericIngestionStep-" + sourceName, jobRepository)
                        .<GenericRecord, GenericRecord>chunk(chunkSize, txManager)
                        .reader(genericIngestionReader)
                        .processor(genericIngestionProcessor)
                        .writer(genericIngestionWriter);

        // ── Fault tolerance si writer.onError=SKIP ───────────────────────────
        if (config.hasWriterConfig()) {
            WriterConfig writerConfig = config.getWriter();
            if (writerConfig.isSkipOnError()) {
                int skipLimit = writerConfig.getSkipLimit();
                log.info("Source '{}' — fault-tolerant mode (onError=SKIP, skipLimit={})",
                        sourceName, skipLimit);

                FaultTolerantStepBuilder<GenericRecord, GenericRecord> ftBuilder =
                        stepBuilder.faultTolerant()
                                .skip(Exception.class)
                                .skipLimit(skipLimit);

                return ftBuilder.build();
            }
        }

        return stepBuilder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST-PROCESSING STEP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step de post-processing (Tasklet).
     * No-op si {@code postprocessing.enabled=false} dans le YAML.
     *
     * @param jobRepository  le dépôt Spring Batch
     * @param txManager      gestionnaire de transaction
     * @param sourceName     nom de la source (depuis jobParameters)
     * @param jobParameters  tous les paramètres du job
     * @return la step configurée
     */
    @Bean
    @JobScope
    public Step genericPostprocessingStep(
            JobRepository jobRepository,
            PlatformTransactionManager txManager,
            @Value("#{jobParameters['sourceName']}") String sourceName,
            @Value("#{jobParameters}") Map<String, Object> jobParameters) {

        SourceConfig config = configLoader.load(sourceName);
        Tasklet tasklet = new GenericTasklet(
                config.getPostprocessing(),
                Collections.unmodifiableMap(jobParameters),
                sqlFileLoader,
                defaultDataSource,
                applicationContext,
                sourceName
        );

        boolean enabled = config.getPostprocessing() != null && config.getPostprocessing().isEnabled();
        log.info("Building postprocessing step for '{}' (enabled={})", sourceName, enabled);

        return new StepBuilder("genericPostprocessingStep-" + sourceName, jobRepository)
                .tasklet(tasklet, txManager)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READER / PROCESSOR / WRITER  (@StepScope)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reader générique (@StepScope).
     * Tous les jobParameters sont transmis pour la résolution des bind variables SQL.
     *
     * @param sourceName    nom de la source (depuis jobParameters)
     * @param jobParameters tous les paramètres du job (bind variables SQL)
     * @return reader configuré (ItemStreamReader = ItemReader + ItemStream)
     */
    @Bean
    @StepScope
    public ItemStreamReader<GenericRecord> genericIngestionReader(
            @Value("#{jobParameters['sourceName']}") String sourceName,
            @Value("#{jobParameters}") Map<String, Object> jobParameters) {

        log.debug("Creating reader for source: {} ({} jobParameters)", sourceName, jobParameters.size());
        SourceConfig config = configLoader.load(sourceName);
        return readerFactory.buildReader(config, Collections.unmodifiableMap(jobParameters));
    }

    /**
     * Processor générique (@StepScope).
     *
     * @param sourceName nom de la source (depuis jobParameters)
     * @return processor configuré (pass-through si aucun bean métier trouvé)
     */
    @Bean
    @StepScope
    public ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        log.debug("Creating processor for source: {}", sourceName);
        SourceConfig config = configLoader.load(sourceName);
        return processorFactory.buildProcessor(config);
    }

    /**
     * Writer générique (@StepScope).
     * Résolution dans l'ordre : writer.type=SQL → writer.type=JAVA → bean {sourceName}Writer.
     *
     * @param sourceName nom de la source (depuis jobParameters)
     * @return writer configuré
     */
    @Bean
    @StepScope
    public ItemWriter<GenericRecord> genericIngestionWriter(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        log.debug("Creating writer for source: {}", sourceName);
        SourceConfig config = configLoader.load(sourceName);
        return writerFactory.buildWriter(config);
    }
}
