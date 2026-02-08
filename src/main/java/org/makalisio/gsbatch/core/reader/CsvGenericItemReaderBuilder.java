package org.makalisio.gsbatch.core.reader;

import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
public class CsvGenericItemReaderBuilder {

    public FlatFileItemReader<GenericRecord> build(SourceConfig config) {

        FlatFileItemReader<GenericRecord> reader = new FlatFileItemReader<>();

        reader.setName("csvReader-" + config.getName());
        reader.setResource(new FileSystemResource(config.getPath()));
        reader.setLinesToSkip(config.isSkipHeader() ? 1 : 0);

        // Tokenizer
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(config.getDelimiter());
        tokenizer.setNames(config.getColumnNames());

        // Mapper
        DefaultLineMapper<GenericRecord> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSet -> {
            GenericRecord record = new GenericRecord();
            config.getColumns().forEach(col -> {
                String name = col.getName();
                String value = fieldSet.readString(name);
                record.put(name, value);
            });
            return record;
        });

        reader.setLineMapper(lineMapper);

        // Do not call afterPropertiesSet() here — let Spring Batch manage the reader lifecycle

        return reader;
    }
}
