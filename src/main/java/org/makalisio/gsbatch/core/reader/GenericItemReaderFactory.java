// org.makalisio.gsbatch.core.reader.GenericItemReaderFactory
package org.makalisio.gsbatch.core.reader;

import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

@Component
public class GenericItemReaderFactory {

    private final CsvGenericItemReaderBuilder csvReaderBuilder;

    public GenericItemReaderFactory(CsvGenericItemReaderBuilder csvReaderBuilder) {
        this.csvReaderBuilder = csvReaderBuilder;
    }

    public ItemReader<GenericRecord> buildReader(SourceConfig config) {

        String type = config.getType();
        if (type == null) {
            throw new IllegalArgumentException("SourceConfig.type must not be null");
        }

        switch (type.toUpperCase()) {
            case "CSV":
                return csvReaderBuilder.build(config);
            default:
                throw new IllegalArgumentException("Unsupported source type: " + type);
        }
    }
}
