// org.makalisio.gsbatch.core.config.CacheConfiguration
package org.makalisio.gsbatch.core.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for enabling caching in the application.
 * Source configurations are cached to improve performance.
 * 
 * @author Makalisio
 * @since 0.0.1
 */
@Configuration
@EnableCaching
public class CacheConfiguration {
    // Spring Boot auto-configures a simple in-memory cache by default
    // For production, consider using Redis, Caffeine, or Ehcache
}
