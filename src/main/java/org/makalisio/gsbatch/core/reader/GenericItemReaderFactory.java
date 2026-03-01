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
import org.springframework.batch.item.ItemStreamReader;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Factory for creating ItemReaders based on source type.
 * Supports multiple source types (CSV, SQL, etc.) with extensible architecture.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class GenericItemReaderFactory {

    private final CsvGenericItemReaderBuilder csvReaderBuilder;
    private final SqlGenericItemReaderBuilder sqlReaderBuilder;
    private final RestGenericItemReaderBuilder restReaderBuilder;
    private final SoapGenericItemReaderBuilder soapReaderBuilder;

    /**
     * @param csvReaderBuilder  builder for CSV sources
     * @param sqlReaderBuilder  builder for SQL sources
     * @param restReaderBuilder builder for REST API sources
     * @param soapReaderBuilder builder for SOAP WebService sources
     */
    public GenericItemReaderFactory(CsvGenericItemReaderBuilder csvReaderBuilder,
                                    SqlGenericItemReaderBuilder sqlReaderBuilder,
                                    RestGenericItemReaderBuilder restReaderBuilder,
                                    SoapGenericItemReaderBuilder soapReaderBuilder) {
        this.csvReaderBuilder = csvReaderBuilder;
        this.sqlReaderBuilder = sqlReaderBuilder;
        this.restReaderBuilder = restReaderBuilder;
        this.soapReaderBuilder = soapReaderBuilder;
        log.info("GenericItemReaderFactory initialized");
    }

    /**
     * Builds an ItemReader based on the source configuration.
     *
     * @param config        the source configuration
     * @param jobParameters all job parameters (used for bind variables in SQL and REST)
     * @return configured ItemStreamReader (extends ItemReader + ItemStream)
     * @throws IllegalArgumentException if source type is unsupported or null
     */
    public ItemStreamReader<GenericRecord> buildReader(SourceConfig config, Map<String, Object> jobParameters) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }

        String type = config.getType();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "Source type must not be null or blank for source: " + config.getName()
            );
        }

        log.debug("Building reader for source '{}' of type: {}", config.getName(), type);

        switch (type.toUpperCase()) {
            case "CSV":
                return csvReaderBuilder.build(config);  // CSV does not use jobParameters
            
            case "SQL":
                return sqlReaderBuilder.build(config, jobParameters);
            
            case "REST":
                return restReaderBuilder.build(config, jobParameters);
            
            case "SOAP":
                return soapReaderBuilder.build(config, jobParameters);
            
            case "JSON":
                throw new UnsupportedOperationException(
                    "JSON reader not yet implemented for source: " + config.getName()
                );
            
            case "XML":
                throw new UnsupportedOperationException(
                    "XML reader not yet implemented for source: " + config.getName()
                );
            
            default:
                String errorMsg = String.format(
                    "Unsupported source type '%s' for source: %s. Supported types: CSV, SQL, REST, SOAP",
                    type, config.getName()
                );
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
        }
    }
}
