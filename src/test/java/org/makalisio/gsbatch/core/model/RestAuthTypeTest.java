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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RestAuthTypeTest {

    @Test
    void from_allKnownValues_match() {
        assertThat(RestAuthType.from("NONE")).isEqualTo(RestAuthType.NONE);
        assertThat(RestAuthType.from("API_KEY")).isEqualTo(RestAuthType.API_KEY);
        assertThat(RestAuthType.from("BEARER")).isEqualTo(RestAuthType.BEARER);
        assertThat(RestAuthType.from("OAUTH2_CLIENT_CREDENTIALS")).isEqualTo(RestAuthType.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Test
    void from_lowercase_isCaseInsensitive() {
        assertThat(RestAuthType.from("api_key")).isEqualTo(RestAuthType.API_KEY);
    }

    @Test
    void from_null_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> RestAuthType.from(null));
    }

    @Test
    void from_unrecognized_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RestAuthType.from("OAUTH1"))
                .withMessageContaining("OAUTH1");
    }
}
