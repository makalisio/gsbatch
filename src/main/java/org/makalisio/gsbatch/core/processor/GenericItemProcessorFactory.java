// org.makalisio.gsbatch.core.processor.GenericItemProcessorFactory
package org.makalisio.gsbatch.core.processor;

import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class GenericItemProcessorFactory {

    private final ApplicationContext applicationContext;

    public GenericItemProcessorFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @SuppressWarnings("unchecked")
    public ItemProcessor<GenericRecord, GenericRecord> buildProcessor(SourceConfig config) {

        String sourceName = config.getName();
        String beanName = sourceName + "Processor";

        if (!applicationContext.containsBean(beanName)) {
            // pass-through si pas de processor métier
            return item -> item;
        }

        Object bean = applicationContext.getBean(beanName);

        if (!(bean instanceof ItemProcessor)) {
            throw new IllegalStateException(
                    "Bean '" + beanName + "' is not an ItemProcessor. Actual type: " + bean.getClass().getName()
            );
        }

        return (ItemProcessor<GenericRecord, GenericRecord>) bean;
    }
}
