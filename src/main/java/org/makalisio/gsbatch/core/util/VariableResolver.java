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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared implementation of the two placeholder substitution mechanisms used
 * throughout gsbatch: bind variables ({@code :paramName}, resolved from
 * jobParameters) and environment variables ({@code ${VAR_NAME}}, resolved from
 * the system environment).
 *
 * <p>Used by REST and SOAP readers/builders wherever a URL, header, query
 * parameter, request body, or request template needs substitution.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
public final class VariableResolver {

    /**
     * Matches {@code :paramName} bind variables. The negative lookbehind avoids
     * matching {@code ::} (e.g. the PostgreSQL cast operator) and requires the
     * name to start with a letter.
     */
    public static final Pattern BIND_PARAM_PATTERN = Pattern.compile("(?<![:])(:[a-zA-Z][a-zA-Z0-9_]*)");

    /**
     * Matches {@code ${VAR_NAME}} environment variable placeholders.
     */
    public static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private VariableResolver() {
    }

    /**
     * Resolves bind variables ({@code :paramName}), then environment variables
     * ({@code ${VAR}}), in that order.
     *
     * @param input         string that may contain {@code :paramName} and/or {@code ${VAR}} placeholders
     * @param jobParameters job parameters used to resolve bind variables
     * @param context       context for error messages (e.g. "rest.url")
     * @return the fully resolved string, or {@code null} if {@code input} is {@code null}
     * @throws IllegalStateException if a bind variable or environment variable cannot be resolved
     */
    public static String resolve(String input, Map<String, Object> jobParameters, String context) {
        if (input == null) {
            return null;
        }
        return resolveEnvVariables(resolveBindVariables(input, jobParameters, context), context);
    }

    /**
     * Resolves {@code :paramName} bind variables from {@code jobParameters}.
     *
     * @throws IllegalStateException if a bind variable is missing or resolves to {@code null}
     */
    public static String resolveBindVariables(String input, Map<String, Object> jobParameters, String context) {
        if (input == null) {
            return null;
        }

        Matcher matcher = BIND_PARAM_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1).substring(1);  // Remove ":"

            if (!jobParameters.containsKey(paramName)) {
                throw new IllegalStateException(String.format(
                        "Bind variable not found in jobParameters [%s]: ':%s'%n" +
                                "Available parameters: %s",
                        context, paramName, jobParameters.keySet()
                ));
            }

            Object value = jobParameters.get(paramName);
            if (value == null) {
                throw new IllegalStateException(String.format(
                        "Bind variable [%s]: ':%s' resolved to null in jobParameters",
                        context, paramName
                ));
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(value.toString()));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves {@code ${VAR_NAME}} environment variable placeholders.
     *
     * @throws IllegalStateException if an environment variable is not set
     */
    public static String resolveEnvVariables(String input, String context) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String envValue = System.getenv(varName);

            if (envValue == null) {
                throw new IllegalStateException(String.format(
                        "Environment variable not found [%s]: ${%s}%n" +
                                "Set it before running the job:%n" +
                                "  export %s=<value>  # Linux/Mac%n" +
                                "  set %s=<value>     # Windows CMD%n" +
                                "  $env:%s='<value>'  # Windows PowerShell",
                        context, varName, varName, varName, varName
                ));
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }
}
