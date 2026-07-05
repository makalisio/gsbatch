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
package org.makalisio.gsbatch.core.model;

import java.util.Locale;

/**
 * Recognized values for {@link SoapConfig.AuthConfig#getType()}.
 *
 * @author Makalisio
 * @since 0.0.1
 */
public enum SoapAuthType {
    NONE,
    BASIC,
    WS_SECURITY,
    CUSTOM_HEADER;

    /**
     * Parses a YAML {@code soap.auth.type} value, case-insensitively.
     *
     * @param value the raw string value
     * @return the matching {@code SoapAuthType}
     * @throws IllegalArgumentException if {@code value} is null, blank, or unrecognized
     */
    public static SoapAuthType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("soap.auth.type must not be null or blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unrecognized soap.auth.type: '" + value + "'");
        }
    }
}
