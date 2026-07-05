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

class SoapAuthTypeTest {

    @Test
    void from_allKnownValues_match() {
        assertThat(SoapAuthType.from("NONE")).isEqualTo(SoapAuthType.NONE);
        assertThat(SoapAuthType.from("BASIC")).isEqualTo(SoapAuthType.BASIC);
        assertThat(SoapAuthType.from("WS_SECURITY")).isEqualTo(SoapAuthType.WS_SECURITY);
        assertThat(SoapAuthType.from("CUSTOM_HEADER")).isEqualTo(SoapAuthType.CUSTOM_HEADER);
    }

    @Test
    void from_lowercase_isCaseInsensitive() {
        assertThat(SoapAuthType.from("ws_security")).isEqualTo(SoapAuthType.WS_SECURITY);
    }

    @Test
    void from_null_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> SoapAuthType.from(null));
    }

    @Test
    void from_unrecognized_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SoapAuthType.from("DIGEST"))
                .withMessageContaining("DIGEST");
    }
}
