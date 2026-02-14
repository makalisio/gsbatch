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
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Configuration class for the generic ingestion job.
 * This job reads data from various sources (CSV, SQL, etc.) using dynamic configuration,
 * processes it, and writes it to configured destinations.
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

    /**
     * Defines the main ingestion job.
     *
     * @param jobRepository the Spring Batch job repository
     * @param genericIngestionStep the step to execute
     * @return the configured Job
     */
    @Bean
    public Job genericIngestionJob(JobRepository jobRepository,
                                   Step genericIngestionStep) {
        log.debug("Building genericIngestionJob");
        return new JobBuilder("genericIngestionJob", jobRepository)
                .start(genericIngestionStep)
                .build();
    }

    /**
     * Defines the ingestion step with chunk-oriented processing.
     * The chunk size is configured per source in the YAML configuration.
     *
     * @param jobRepository the Spring Batch job repository
     * @param transactionManager the transaction manager
     * @param genericIngestionReader the item reader
     * @param genericIngestionProcessor the item processor
     * @param genericIngestionWriter the item writer
     * @param sourceName the source name from job parameters
     * @return the configured Step
     */
    @Bean
    public Step genericIngestionStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager,
                                     ItemReader<GenericRecord> genericIngestionReader,
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

    /**
     * Creates the item reader bean for the current source.
     * The reader is step-scoped to allow dynamic configuration per job execution.
     *
     * @param sourceName the source name from job parameters
     * @return the configured ItemReader
     */
    @Bean
    @StepScope
    public ItemReader<GenericRecord> genericIngestionReader(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        log.debug("Creating reader for source: {}", sourceName);
        SourceConfig config = configLoader.load(sourceName);
        return readerFactory.buildReader(config);
    }

    /**
     * Creates the item processor bean for the current source.
     * The processor is step-scoped to allow dynamic configuration per job execution.
     *
     * @param sourceName the source name from job parameters
     * @return the configured ItemProcessor
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
     * Creates the item writer bean for the current source.
     * The writer is step-scoped to allow dynamic configuration per job execution.
     *
     * @param sourceName the source name from job parameters
     * @return the configured ItemWriter
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
