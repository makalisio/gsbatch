// org.makalisio.gsbatch.core.writer.GenericItemWriterFactory
package org.makalisio.gsbatch.core.writer;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Factory for creating ItemWriters.
 * Looks for custom writer beans in the Spring context.
 * A writer bean is mandatory for each source.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class GenericItemWriterFactory {

    private final ApplicationContext applicationContext;

    public GenericItemWriterFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        log.info("GenericItemWriterFactory initialized");
    }

    /**
     * Builds an ItemWriter for the given source configuration.
     * Searches for a bean named "{sourceName}Writer".
     *
     * @param config the source configuration
     * @return configured ItemWriter
     * @throws IllegalArgumentException if config is null
     * @throws IllegalStateException if writer bean not found or wrong type
     */
    public ItemWriter<GenericRecord> buildWriter(SourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }

        String sourceName = config.getName();
        String beanName = sourceName + "Writer";

        log.debug("Looking for writer bean: {}", beanName);

        if (!applicationContext.containsBean(beanName)) {
            String errorMsg = String.format(
                "No writer bean found for source '%s'. Expected bean name: '%s'. " +
                "Please create a @Bean or @Component named '%s' that implements ItemWriter<GenericRecord>.",
                sourceName, beanName, beanName
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        Object bean = applicationContext.getBean(beanName);

        if (!(bean instanceof ItemWriter)) {
            String errorMsg = String.format(
                "Bean '%s' exists but is not an ItemWriter. Actual type: %s. " +
                "The bean must implement ItemWriter<GenericRecord>.",
                beanName, bean.getClass().getName()
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.info("Using writer bean '{}' for source '{}'", beanName, sourceName);

        @SuppressWarnings("unchecked")
        ItemWriter<GenericRecord> writer = (ItemWriter<GenericRecord>) bean;

        return writer;
    }
}
