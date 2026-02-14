// org.makalisio.gsbatch.core.reader.GenericItemReaderFactory
package org.makalisio.gsbatch.core.reader;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemReader;
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

    public GenericItemReaderFactory(CsvGenericItemReaderBuilder csvReaderBuilder) {
        this.csvReaderBuilder = csvReaderBuilder;
        log.info("GenericItemReaderFactory initialized");
    }

    /**
     * Builds an ItemReader based on the source configuration.
     *
     * @param config the source configuration
     * @return configured ItemReader
     * @throws IllegalArgumentException if source type is unsupported or null
     */
    public ItemReader<GenericRecord> buildReader(SourceConfig config) {
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
                return csvReaderBuilder.build(config);
            
            // Future support for other types
            case "SQL":
                throw new UnsupportedOperationException(
                    "SQL reader not yet implemented for source: " + config.getName()
                );
            
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
                    "Unsupported source type '%s' for source: %s. Supported types: CSV",
                    type, config.getName()
                );
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
        }
    }
}
