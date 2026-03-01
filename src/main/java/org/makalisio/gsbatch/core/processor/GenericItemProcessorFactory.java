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
package org.makalisio.gsbatch.core.processor;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.model.GenericRecord;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Factory for creating ItemProcessors.
 * Looks for custom processor beans in the Spring context.
 * If no custom processor is found, returns a pass-through processor.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class GenericItemProcessorFactory {

    private final ApplicationContext applicationContext;

    /**
     * @param applicationContext the Spring context for finding business beans
     */
    public GenericItemProcessorFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        log.info("GenericItemProcessorFactory initialized");
    }

    /**
     * Builds an ItemProcessor for the given source configuration.
     * Searches for a bean named "{sourceName}Processor".
     * If not found, returns a pass-through processor.
     *
     * @param config the source configuration
     * @return configured ItemProcessor
     * @throws IllegalArgumentException if config is null
     * @throws IllegalStateException if the found bean is not an ItemProcessor
     */
    public ItemProcessor<GenericRecord, GenericRecord> buildProcessor(SourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("SourceConfig cannot be null");
        }

        String sourceName = config.getName();
        String beanName = sourceName + "Processor";

        // Fallback: try camelCase (e.g., "calculatorSoapProcessor") when sourceName contains hyphens
        if (!applicationContext.containsBean(beanName) && sourceName.contains("-")) {
            String camelCaseBeanName = toCamelCase(sourceName) + "Processor";
            if (applicationContext.containsBean(camelCaseBeanName)) {
                beanName = camelCaseBeanName;
            }
        }

        log.debug("Looking for processor bean: {}", beanName);

        if (!applicationContext.containsBean(beanName)) {
            log.debug("No custom processor found for source '{}', using pass-through processor", sourceName);
            // Return pass-through processor
            return item -> {
                log.trace("Pass-through processing record: {}", item);
                return item;
            };
        }

        Object bean = applicationContext.getBean(beanName);

        if (!(bean instanceof ItemProcessor)) {
            String errorMsg = String.format(
                    "Bean '%s' exists but is not an ItemProcessor. Actual type: %s",
                    beanName, bean.getClass().getName()
            );
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        log.info("Using custom processor bean '{}' for source '{}'", beanName, sourceName);

        @SuppressWarnings("unchecked")
        ItemProcessor<GenericRecord, GenericRecord> processor =
                (ItemProcessor<GenericRecord, GenericRecord>) bean;

        return processor;
    }

    /**
     * Converts a hyphenated name to lowerCamelCase.
     * Example: "calculator-soap" → "calculatorSoap"
     */
    private static String toCamelCase(String hyphenated) {
        String[] parts = hyphenated.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }
}
