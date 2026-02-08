// org.makalisio.gsbatch.core.config.YamlSourceConfigLoader
package org.makalisio.gsbatch.core.config;

import org.makalisio.gsbatch.core.model.SourceConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

@Component
public class YamlSourceConfigLoader {

    private final ResourceLoader resourceLoader;

    public YamlSourceConfigLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public SourceConfig load(String sourceName) {
        try {
            String path = "classpath:ingestion/" + sourceName + ".yml";
            Resource resource = resourceLoader.getResource(path);

            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "No ingestion configuration found for source '" + sourceName +
                                "'. Expected file: ingestion/" + sourceName + ".yml"
                );
            }

            Yaml yaml = new Yaml();
            try (InputStream in = resource.getInputStream()) {
                SourceConfig config = yaml.loadAs(in, SourceConfig.class);
                if (config.getName() == null || config.getName().isBlank()) {
                    config.setName(sourceName);
                }
                return config;
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load ingestion config for source: " + sourceName, e);
        }
    }
}
