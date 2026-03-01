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
