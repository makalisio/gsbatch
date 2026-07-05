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
package org.makalisio.gsbatch.core.util;

import org.springframework.context.ApplicationContext;

/**
 * Resolves the {@code {sourceName}{suffix}} bean-naming convention used by
 * {@code GenericItemProcessorFactory} and {@code GenericItemWriterFactory}
 * (e.g. {@code ordersWriter}, {@code calculator-soapProcessor}).
 *
 * <p>A hyphenated {@code sourceName} also falls back to its camelCase form
 * (e.g. {@code calculator-soap} -&gt; {@code calculatorSoap}) when the direct
 * name isn't a registered bean but the camelCase one is - this lets a source
 * named with hyphens (a natural YAML file-naming style) still match a bean
 * named in valid Java identifier style.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public final class BeanNameResolver {

    private BeanNameResolver() {
    }

    /**
     * Resolves the bean name for {@code sourceName + suffix}, following the
     * direct-name-first, camelCase-fallback convention.
     *
     * @param context    Spring context to check bean existence against
     * @param sourceName the source name (e.g. {@code "calculator-soap"})
     * @param suffix     the role suffix (e.g. {@code "Writer"}, {@code "Processor"})
     * @return the camelCase name if it exists and the direct name doesn't,
     *         otherwise the direct name (which may or may not be a registered
     *         bean - callers decide how to handle that)
     */
    public static String resolve(ApplicationContext context, String sourceName, String suffix) {
        String directName = sourceName + suffix;

        if (!context.containsBean(directName) && sourceName.contains("-")) {
            String camelCaseName = toCamelCase(sourceName) + suffix;
            if (context.containsBean(camelCaseName)) {
                return camelCaseName;
            }
        }

        return directName;
    }

    /**
     * Converts a hyphenated name to lowerCamelCase.
     * Example: {@code "calculator-soap"} -&gt; {@code "calculatorSoap"}.
     */
    public static String toCamelCase(String hyphenated) {
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
