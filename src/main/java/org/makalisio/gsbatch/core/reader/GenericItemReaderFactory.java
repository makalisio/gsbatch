// org.makalisio.gsbatch.core.reader.GenericItemReaderFactory
package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import java.util.Collections;
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

    /**
     * @param csvReaderBuilder le builder pour les sources CSV
     * @param sqlReaderBuilder le builder pour les sources SQL
     */
    public GenericItemReaderFactory(CsvGenericItemReaderBuilder csvReaderBuilder,
                                    SqlGenericItemReaderBuilder sqlReaderBuilder) {
        this.csvReaderBuilder = csvReaderBuilder;
        this.sqlReaderBuilder = sqlReaderBuilder;
        log.info("GenericItemReaderFactory initialized");
    }

    /**
     * Builds an ItemReader based on the source configuration.
     *
     * @param config the source configuration
     * @param jobParameters tous les paramètres du job (utilisés pour les bind variables SQL)
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
                return csvReaderBuilder.build(config);  // CSV n'utilise pas les jobParameters
            
            case "SQL":
                return sqlReaderBuilder.build(config, jobParameters);
            
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
                    "Unsupported source type '%s' for source: %s. Supported types: CSV, SQL",
                    type, config.getName()
                );
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
        }
    }
}
