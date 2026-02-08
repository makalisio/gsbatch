// org.makalisio.gsbatch.core.writer.GenericItemWriterFactory
package org.makalisio.gsbatch.core.writer;

import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class GenericItemWriterFactory {

    private final ApplicationContext applicationContext;

    public GenericItemWriterFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @SuppressWarnings("unchecked")
    public ItemWriter<GenericRecord> buildWriter(SourceConfig config) {

        String sourceName = config.getName();
        String beanName = sourceName + "Writer";

        if (!applicationContext.containsBean(beanName)) {
            throw new IllegalStateException(
                    "No writer bean found for source '" + sourceName +
                            "'. Expected bean name: '" + beanName + "'"
            );
        }

        Object bean = applicationContext.getBean(beanName);

        if (!(bean instanceof ItemWriter)) {
            throw new IllegalStateException(
                    "Bean '" + beanName + "' is not an ItemWriter. Actual type: " + bean.getClass().getName()
            );
        }

        return (ItemWriter<GenericRecord>) bean;
    }
}
