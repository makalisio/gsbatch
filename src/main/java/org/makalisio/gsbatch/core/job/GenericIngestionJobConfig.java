package org.makalisio.gsbatch.core.job;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.config.YamlSourceConfigLoader;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.processor.GenericItemProcessorFactory;
import org.makalisio.gsbatch.core.reader.GenericItemReaderFactory;
import org.makalisio.gsbatch.core.writer.GenericItemWriterFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;   // ← AJOUT
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import java.util.Collections;
import java.util.Map;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Configuration du job générique d'ingestion.
 *
 * <h2>Cycle de vie des beans</h2>
 * <pre>
 *   Démarrage application
 *     └─ genericIngestionJob        (singleton)
 *          └─ genericIngestionStep  (@JobScope  → créé au lancement du job)
 *               ├─ reader           (@StepScope → créé au démarrage du step)
 *               ├─ processor        (@StepScope → créé au démarrage du step)
 *               └─ writer           (@StepScope → créé au démarrage du step)
 * </pre>
 *
 * <h2>Pourquoi @JobScope sur le Step ?</h2>
 * <p>
 * {@code @Value("#{jobParameters['sourceName']}")} utilise SpEL pour lire les paramètres
 * du job. Ces paramètres ne sont disponibles qu'à l'exécution du job, pas au démarrage
 * de l'application. Sans {@code @JobScope}, Spring tente de résoudre l'expression au
 * démarrage → {@code SpelEvaluationException: jobParameters cannot be found}.
 * </p>
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

    /**
     * @param configLoader    le loader de configuration YAML
     * @param readerFactory   la factory de readers
     * @param processorFactory la factory de processors
     * @param writerFactory   la factory de writers
     */
    public GenericIngestionJobConfig(
            YamlSourceConfigLoader configLoader,
            GenericItemReaderFactory readerFactory,
            GenericItemProcessorFactory processorFactory,
            GenericItemWriterFactory writerFactory
    ) {
        this.configLoader = configLoader;
        this.readerFactory = readerFactory;
        this.processorFactory = processorFactory;
        this.writerFactory = writerFactory;
        log.info("GenericIngestionJobConfig initialized");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JOB  (Singleton — pas d'accès aux jobParameters)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Job générique d'ingestion.
     * Bean singleton : créé au démarrage de l'application.
     * Injecte le Step via son proxy JobScope.
     *
     * @param jobRepository le dépôt Spring Batch
     * @param genericIngestionStep le step à exécuter (proxy JobScope)
     * @return le job configuré
     */
    @Bean
    public Job genericIngestionJob(JobRepository jobRepository,
                                   Step genericIngestionStep) {
        log.debug("Building genericIngestionJob");
        return new JobBuilder("genericIngestionJob", jobRepository)
                .start(genericIngestionStep)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STEP  (@JobScope — créé au lancement du job, jobParameters disponibles)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Step générique d'ingestion.
     *
     * <p><b>@JobScope est obligatoire</b> : permet à Spring de résoudre
     * {@code #{jobParameters['sourceName']}} au moment du lancement du job,
     * et non au démarrage de l'application.</p>
     *
     * <p>Les beans reader/processor/writer injectés ici sont des <b>proxys CGLIB</b>
     * (@StepScope). Spring Batch les résoudra au démarrage du step.</p>
     *
     * @param jobRepository le dépôt Spring Batch
     * @param transactionManager le gestionnaire de transaction
     * @param genericIngestionReader le reader (proxy StepScope)
     * @param genericIngestionProcessor le processor (proxy StepScope)
     * @param genericIngestionWriter le writer (proxy StepScope)
     * @param sourceName injecté depuis les paramètres du job
     * @return le step configuré
     */
    @Bean
    @JobScope  // ← CORRECTION CLEF : résout jobParameters au lancement du job
    public Step genericIngestionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemStreamReader<GenericRecord> genericIngestionReader,
            ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor,
            ItemWriter<GenericRecord> genericIngestionWriter,
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        SourceConfig config = configLoader.load(sourceName);
        int chunkSize = config.getChunkSize();

        log.info("Building step for source '{}' with chunk size: {}", sourceName, chunkSize);

        return new StepBuilder("genericIngestionStep-" + sourceName, jobRepository)
                .<GenericRecord, GenericRecord>chunk(chunkSize, transactionManager)
                .reader(genericIngestionReader)
                .processor(genericIngestionProcessor)
                .writer(genericIngestionWriter)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  READER / PROCESSOR / WRITER  (@StepScope — créés au démarrage du step)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reader générique, créé au démarrage du step (StepScope).
     *
     * <p>Tous les jobParameters sont injectés et transmis à la factory :
     * pour les sources SQL, ils servent à résoudre les bind variables
     * {@code :paramName} du fichier SQL.</p>
     *
     * @param sourceName    le nom de la source (depuis jobParameters)
     * @param jobParameters tous les paramètres du job (pour les bind variables SQL)
     * @return le reader configuré pour la source (ItemStreamReader = ItemReader + ItemStream)
     */
    @Bean
    @StepScope
    public ItemStreamReader<GenericRecord> genericIngestionReader(
            @Value("#{jobParameters['sourceName']}") String sourceName,
            @Value("#{jobParameters}") Map<String, Object> jobParameters) {

        log.debug("Creating reader for source: {} with {} jobParameters", sourceName, jobParameters.size());
        SourceConfig config = configLoader.load(sourceName);
        return readerFactory.buildReader(config, Collections.unmodifiableMap(jobParameters));
    }

    /**
     * Processor générique, créé au démarrage du step (StepScope).
     * Le sourceName est résolu depuis les jobParameters.
     *
     * @param sourceName le nom de la source (depuis jobParameters)
     * @return le processor configuré pour la source
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
     * Writer générique, créé au démarrage du step (StepScope).
     * Le sourceName est résolu depuis les jobParameters.
     *
     * @param sourceName le nom de la source (depuis jobParameters)
     * @return le writer configuré pour la source
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
