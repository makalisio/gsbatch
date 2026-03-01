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
package org.makalisio.gsbatch.core.config;

import lombok.extern.slf4j.Slf4j;
import org.makalisio.gsbatch.core.exception.ConfigurationLoadException;
import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loader responsible for loading source configurations from YAML files.
 * Configuration files are expected to be located in the classpath under 'ingestion/{sourceName}.yml'.
 *
 * @author Makalisio
 * @since 0.0.1
 */
@Slf4j
@Component
public class YamlSourceConfigLoader {

    private static final String CONFIG_PATH_TEMPLATE = "classpath:ingestion/%s.yml";
    private static final String INGESTION_DIR = "ingestion/";

    private final ResourceLoader resourceLoader;
    private final Yaml yaml;

    /**
     * Constructs a YamlSourceConfigLoader with the specified ResourceLoader.
     * Initializes a secure YAML parser.
     *
     * @param resourceLoader Spring's resource loader for accessing classpath resources
     */
    public YamlSourceConfigLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;

        // Configure SnakeYAML for safe deserialization (compatible with SnakeYAML 2.x)
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(SourceConfig.class, loaderOptions);

        this.yaml = new Yaml(constructor);

        log.info("YamlSourceConfigLoader initialized");
    }

    /**
     * Loads the ingestion configuration for the specified source.
     * Results are cached to avoid repeated file I/O operations.
     *
     * @param sourceName the name of the source (without .yml extension)
     * @return the loaded SourceConfig
     * @throws ConfigurationLoadException if the configuration cannot be loaded or is invalid
     * @throws IllegalArgumentException if sourceName is null or blank
     */
    @Cacheable(value = "sourceConfigs", key = "#sourceName")
    public SourceConfig load(String sourceName) {
        validateSourceName(sourceName);

        log.debug("Loading ingestion configuration for source: {}", sourceName);

        String path = String.format(CONFIG_PATH_TEMPLATE, sourceName);
        Resource resource = resourceLoader.getResource(path);

        if (!resource.exists()) {
            String errorMsg = String.format(
                    "No ingestion configuration found for source '%s'. Expected file: %s%s.yml",
                    sourceName, INGESTION_DIR, sourceName
            );
            log.error(errorMsg);
            throw new ConfigurationLoadException(errorMsg);
        }

        try (InputStream in = resource.getInputStream()) {
            SourceConfig config = yaml.loadAs(in, SourceConfig.class);

            if (config == null) {
                throw new ConfigurationLoadException(
                        "Configuration file is empty or invalid for source: " + sourceName
                );
            }

            // Set default name if not provided
            if (config.getName() == null || config.getName().isBlank()) {
                log.debug("Setting default name '{}' for source configuration", sourceName);
                config.setName(sourceName);
            }

            validateConfig(config, sourceName);

            // Validate configuration structure
            config.validate();

            log.info("Successfully loaded configuration for source: {} (name: {})",
                    sourceName, config.getName());

            return config;

        } catch (FileNotFoundException e) {
            String errorMsg = String.format(
                    "Configuration file not found for source '%s' at path: %s",
                    sourceName, path
            );
            log.error(errorMsg, e);
            throw new ConfigurationLoadException(errorMsg, e);

        } catch (IOException e) {
            String errorMsg = String.format(
                    "Failed to read configuration file for source '%s'", sourceName
            );
            log.error(errorMsg, e);
            throw new ConfigurationLoadException(errorMsg, e);

        } catch (ConfigurationLoadException e) {
            // Re-throw configuration exceptions
            throw e;

        } catch (Exception e) {
            String errorMsg = String.format(
                    "Failed to parse YAML configuration for source '%s'", sourceName
            );
            log.error(errorMsg, e);
            throw new ConfigurationLoadException(errorMsg, e);
        }
    }

    /**
     * Validates the source name parameter.
     *
     * @param sourceName the source name to validate
     * @throws IllegalArgumentException if the source name is null or blank
     */
    private void validateSourceName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Source name cannot be null or blank");
        }

        // Validate for path traversal attacks
        if (sourceName.contains("..") || sourceName.contains("/") || sourceName.contains("\\")) {
            throw new IllegalArgumentException(
                    "Source name contains invalid characters: " + sourceName
            );
        }
    }

    /**
     * Validates the loaded configuration.
     * Override or extend this method to add custom validation rules.
     *
     * @param config the configuration to validate
     * @param sourceName the source name for error messages
     * @throws ConfigurationLoadException if validation fails
     */
    protected void validateConfig(SourceConfig config, String sourceName) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new ConfigurationLoadException(
                    "Configuration name is missing for source: " + sourceName
            );
        }

        log.debug("Configuration validation passed for source: {}", sourceName);
    }
}