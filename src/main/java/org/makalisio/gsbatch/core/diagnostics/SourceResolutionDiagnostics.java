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
package org.makalisio.gsbatch.core.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.config.YamlSourceConfigLoader;
import org.makalisio.gsbatch.core.model.ExecutionType;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.makalisio.gsbatch.core.model.SourceType;
import org.makalisio.gsbatch.core.model.StepConfig;
import org.makalisio.gsbatch.core.model.WriterConfig;
import org.makalisio.gsbatch.core.util.BeanNameResolver;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import static org.makalisio.gsbatch.core.diagnostics.SourceResolutionReport.Resolution;

/**
 * Explains, for a given source name, exactly which reader builder, processor
 * bean, writer (SQL file or bean), and pre/post-processing tasklet the
 * framework will use - without running the job.
 *
 * <p>This turns "why isn't my writer bean being picked up?" from a grep
 * through {@code GenericItemWriterFactory} into a single call. Bean checks
 * here only inspect the Spring context (via {@code containsBean}/{@code
 * isTypeMatch}) - they never instantiate a new bean, run SQL, or make a
 * network call, so calling {@link #describe(String)} is safe at any time.</p>
 *
 * <p>Not wired to any HTTP/actuator endpoint by the framework itself (this is
 * a library, not an application) - consuming applications can expose it
 * however suits them, e.g. a custom Actuator {@code @Endpoint} or a debug
 * controller. See the README for an example.</p>
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class SourceResolutionDiagnostics {

    private final YamlSourceConfigLoader configLoader;
    private final ApplicationContext applicationContext;

    public SourceResolutionDiagnostics(YamlSourceConfigLoader configLoader, ApplicationContext applicationContext) {
        this.configLoader = configLoader;
        this.applicationContext = applicationContext;
        log.info("SourceResolutionDiagnostics initialized");
    }

    /**
     * Describes how {@code sourceName} resolves. Loads and validates the
     * source's YAML config exactly as the job would (so a broken config
     * surfaces here too), then inspects the Spring context for each role.
     *
     * @param sourceName the source name (as passed via jobParameters)
     * @return the resolution report
     * @throws org.makalisio.gsbatch.core.exception.ConfigurationLoadException if the config can't be loaded/is invalid
     */
    public SourceResolutionReport describe(String sourceName) {
        SourceConfig config = configLoader.load(sourceName);

        return new SourceResolutionReport(
                config.getName(),
                config.getType(),
                describeReader(config),
                describeProcessor(config),
                describeWriter(config),
                describeStep(config.getPreprocessing()),
                describeStep(config.getPostprocessing())
        );
    }

    private Resolution describeReader(SourceConfig config) {
        SourceType type;
        try {
            type = config.getSourceType();
        } catch (IllegalStateException e) {
            return Resolution.unresolvable(e.getMessage());
        }

        if (!type.isImplemented()) {
            return Resolution.unresolvable(type + " reader not yet implemented");
        }

        String builderClass = switch (type) {
            case CSV -> "CsvGenericItemReaderBuilder";
            case SQL -> "SqlGenericItemReaderBuilder";
            case REST -> "RestGenericItemReaderBuilder";
            case SOAP -> "SoapGenericItemReaderBuilder";
            case JSON, XML -> throw new IllegalStateException("unreachable: checked isImplemented() above");
        };

        return Resolution.resolved(type + " via " + builderClass);
    }

    private Resolution describeProcessor(SourceConfig config) {
        String beanName = BeanNameResolver.resolve(applicationContext, config.getName(), "Processor");

        if (!applicationContext.containsBean(beanName)) {
            return Resolution.resolved("pass-through (identity) - no bean '" + beanName + "' found");
        }

        return describeBean(beanName, ItemProcessor.class);
    }

    private Resolution describeWriter(SourceConfig config) {
        if (config.hasWriterConfig()) {
            WriterConfig writerConfig = config.getWriter();

            ExecutionType executionType;
            try {
                executionType = writerConfig.getExecutionType();
            } catch (IllegalStateException e) {
                return Resolution.unresolvable(e.getMessage());
            }

            return switch (executionType) {
                case SQL -> Resolution.resolved(
                        "SQL writer: " + writerConfig.getSqlDirectory() + "/" + writerConfig.getSqlFile());
                case JAVA -> describeBean(writerConfig.getBeanName(), ItemWriter.class);
            };
        }

        String beanName = BeanNameResolver.resolve(applicationContext, config.getName(), "Writer");
        return describeBean(beanName, ItemWriter.class);
    }

    private Resolution describeStep(StepConfig stepConfig) {
        if (stepConfig == null || !stepConfig.isEnabled()) {
            return Resolution.resolved("disabled (no-op)");
        }

        ExecutionType executionType;
        try {
            executionType = stepConfig.getExecutionType();
        } catch (IllegalStateException e) {
            return Resolution.unresolvable(e.getMessage());
        }

        return switch (executionType) {
            case SQL -> Resolution.resolved(
                    "SQL: " + stepConfig.getSqlDirectory() + "/" + stepConfig.getSqlFile());
            case JAVA -> describeBean(stepConfig.getBeanName(), Tasklet.class);
        };
    }

    private Resolution describeBean(String beanName, Class<?> expectedType) {
        if (beanName == null || beanName.isBlank()) {
            return Resolution.unresolvable("beanName is not configured");
        }
        if (!applicationContext.containsBean(beanName)) {
            return Resolution.unresolvable("no bean named '" + beanName + "' found");
        }
        if (!applicationContext.isTypeMatch(beanName, expectedType)) {
            return Resolution.unresolvable(
                    "bean '" + beanName + "' exists but does not implement " + expectedType.getSimpleName());
        }
        return Resolution.resolved("bean '" + beanName + "'");
    }
}
