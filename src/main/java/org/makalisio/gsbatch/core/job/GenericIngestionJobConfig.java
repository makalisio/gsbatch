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
 * Configuration for the generic ingestion job.
 *
 * <h2>Bean lifecycle</h2>
 * <pre>
 *   Application startup
 *     └─ genericIngestionJob              (Singleton)
 *          ├─ genericPreprocessingStep    (@JobScope — no-op if disabled)
 *          ├─ genericIngestionStep        (@JobScope — chunk reader/processor/writer)
 *          └─ genericPostprocessingStep   (@JobScope — no-op if disabled)
 * </pre>
 *
 * <h2>Pre/post processing steps</h2>
 * <p>Always included in the job but act as no-ops if {@code enabled=false}.
 * This avoids rebuilding the job definition based on configuration.</p>
 *
 * <h2>Writer fault-tolerance</h2>
 * <p>If {@code writer.onError=SKIP} in the YAML, the ingestion step is configured
 * in {@code faultTolerant} mode with a configurable {@code skipLimit}.</p>
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
     * @param configLoader       YAML configuration loader
     * @param readerFactory      reader factory
     * @param processorFactory   processor factory
     * @param writerFactory      writer factory
     * @param sqlFileLoader      SQL file loader (for pre/post processing)
     * @param defaultDataSource  primary DataSource
     * @param applicationContext Spring context (for JAVA beans)
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
     * Generic job with 3 steps: pre-processing → ingestion chunk → post-processing.
     * Pre/post steps are no-ops if {@code enabled=false} in the YAML.
     *
     * @param jobRepository              the Spring Batch job repository
     * @param genericPreprocessingStep   pre-processing step (@JobScope proxy)
     * @param genericIngestionStep       main chunk step (@JobScope proxy)
     * @param genericPostprocessingStep  post-processing step (@JobScope proxy)
     * @return the configured job
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
     * Pre-processing step (Tasklet).
     * No-op if {@code preprocessing.enabled=false} in the YAML.
     *
     * @param jobRepository  the Spring Batch job repository
     * @param txManager      transaction manager
     * @param sourceName     source name (from jobParameters)
     * @param jobParameters  all job parameters
     * @return the configured step
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
     * Main read/process/write step in chunk mode.
     *
     * <p>If {@code writer.onError=SKIP}, the step is configured in
     * {@code faultTolerant} mode with {@code skipLimit} from the YAML.</p>
     *
     * @param jobRepository              the Spring Batch job repository
     * @param txManager                  transaction manager
     * @param genericIngestionReader     reader (@StepScope proxy)
     * @param genericIngestionProcessor  processor (@StepScope proxy)
     * @param genericIngestionWriter     writer (@StepScope proxy)
     * @param sourceName                 source name (from jobParameters)
     * @return the configured step
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

        // ── Fault tolerance if writer.onError=SKIP ───────────────────────────
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
     * Post-processing step (Tasklet).
     * No-op if {@code postprocessing.enabled=false} in the YAML.
     *
     * @param jobRepository  the Spring Batch job repository
     * @param txManager      transaction manager
     * @param sourceName     source name (from jobParameters)
     * @param jobParameters  all job parameters
     * @return the configured step
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
     * Generic reader (@StepScope).
     * All jobParameters are passed for SQL bind variable resolution.
     *
     * @param sourceName    source name (from jobParameters)
     * @param jobParameters all job parameters (SQL bind variables)
     * @return configured reader (ItemStreamReader = ItemReader + ItemStream)
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
     * Generic processor (@StepScope).
     *
     * @param sourceName source name (from jobParameters)
     * @return configured processor (pass-through if no custom bean found)
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
     * Generic writer (@StepScope).
     * Resolution order: writer.type=SQL → writer.type=JAVA → bean {sourceName}Writer.
     *
     * @param sourceName source name (from jobParameters)
     * @return configured writer
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
