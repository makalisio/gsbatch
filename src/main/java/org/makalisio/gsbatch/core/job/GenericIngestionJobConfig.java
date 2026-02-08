package org.makalisio.gsbatch.core.job;

import org.makalisio.gsbatch.core.config.YamlSourceConfigLoader;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.reader.GenericItemReaderFactory;
import org.makalisio.gsbatch.core.processor.GenericItemProcessorFactory;
import org.makalisio.gsbatch.core.writer.GenericItemWriterFactory;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;

import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

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
    }

    // -----------------------------
    //  JOB
    // -----------------------------
    @Bean
    public Job genericIngestionJob(JobRepository jobRepository,
                                   Step genericIngestionStep) {

        return new JobBuilder("genericIngestionJob", jobRepository)
                .start(genericIngestionStep)
                .build();
    }

    // -----------------------------
    //  STEP
    // -----------------------------
    @Bean
    public Step genericIngestionStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager) {

        return new StepBuilder("genericIngestionStep", jobRepository)
                .<GenericRecord, GenericRecord>chunk(1000)
                .reader(genericIngestionReader(null))
                .processor(genericIngestionProcessor(null))
                .writer(genericIngestionWriter(null))
                .transactionManager(transactionManager)
                .build();
    }

    // -----------------------------
    //  READER
    // -----------------------------
    @Bean
    @StepScope
    public FlatFileItemReader<GenericRecord> genericIngestionReader(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        SourceConfig config = configLoader.load(sourceName);
        // buildReader returns an ItemReader — cast to FlatFileItemReader for Batch lifecycle
        return (FlatFileItemReader<GenericRecord>) readerFactory.buildReader(config);
    }

    // -----------------------------
    //  PROCESSOR
    // -----------------------------
    @Bean
    @StepScope
    public ItemProcessor<GenericRecord, GenericRecord> genericIngestionProcessor(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        SourceConfig config = configLoader.load(sourceName);
        return processorFactory.buildProcessor(config);
    }

    // -----------------------------
    //  WRITER
    // -----------------------------
    @Bean
    @StepScope
    public ItemWriter<GenericRecord> genericIngestionWriter(
            @Value("#{jobParameters['sourceName']}") String sourceName) {

        SourceConfig config = configLoader.load(sourceName);
        return writerFactory.buildWriter(config);
    }
}
