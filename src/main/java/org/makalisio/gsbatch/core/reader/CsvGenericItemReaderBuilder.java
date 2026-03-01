/*
 * Copyright 2026 Makalisio Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Builder for creating CSV-based ItemReaders.
 * Configures a FlatFileItemReader based on SourceConfig.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class CsvGenericItemReaderBuilder {

    /**
     * Builds a FlatFileItemReader for CSV files.
     *
     * @param config the source configuration
     * @return configured FlatFileItemReader
     * @throws IllegalArgumentException if configuration is invalid
     * @throws IllegalStateException if the CSV file doesn't exist
     */
    public FlatFileItemReader<GenericRecord> build(SourceConfig config) {
        validateConfig(config);

        String filePath = config.getPath();
        File file = new File(filePath);
        
        if (!file.exists()) {
            String errorMsg = String.format(
                "CSV file not found for source '%s': %s", 
                config.getName(), filePath
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        if (!file.canRead()) {
            String errorMsg = String.format(
                "CSV file is not readable for source '%s': %s", 
                config.getName(), filePath
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.info("Building CSV reader for source '{}' from file: {}", config.getName(), filePath);

        FlatFileItemReader<GenericRecord> reader = new FlatFileItemReader<>();
        reader.setName("csvReader-" + config.getName());
        reader.setResource(new FileSystemResource(file));
        reader.setLinesToSkip(config.isSkipHeader() ? 1 : 0);
        reader.setStrict(true); // Fail if file doesn't exist

        // Configure tokenizer
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(config.getDelimiter());
        tokenizer.setNames(config.getColumnNames());
        tokenizer.setStrict(false); // Don't fail if line has fewer tokens

        // Configure line mapper
        DefaultLineMapper<GenericRecord> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSet -> {
            GenericRecord record = new GenericRecord();
            
            config.getColumns().forEach(col -> {
                String name = col.getName();
                try {
                    String value = fieldSet.readString(name);
                    // Only add non-null values
                    if (value != null) {
                        record.put(name, value.trim());
                    }
                } catch (Exception e) {
                    log.warn("Error reading column '{}' for source '{}': {}", 
                            name, config.getName(), e.getMessage());
                    record.put(name, null);
                }
            });
            
            return record;
        });

        reader.setLineMapper(lineMapper);

        log.debug("CSV reader configured for source '{}' with delimiter '{}', skipHeader: {}", 
                config.getName(), config.getDelimiter(), config.isSkipHeader());

        return reader;
    }

    /**
     * Validates the configuration before building the reader.
     *
     * @param config the configuration to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateConfig(SourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }
        
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("Source name is required");
        }
        
        if (config.getPath() == null || config.getPath().isBlank()) {
            throw new IllegalArgumentException(
                "File path is required for CSV source: " + config.getName()
            );
        }
        
        if (config.getColumns() == null || config.getColumns().isEmpty()) {
            throw new IllegalArgumentException(
                "Columns configuration is required for CSV source: " + config.getName()
            );
        }
        
        if (config.getDelimiter() == null || config.getDelimiter().isEmpty()) {
            throw new IllegalArgumentException(
                "Delimiter is required for CSV source: " + config.getName()
            );
        }
    }
}
