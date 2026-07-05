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

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeanNameResolverTest {

    @Test
    void resolve_directNameExists_returnsDirectName() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.containsBean("ordersWriter")).thenReturn(true);

        assertThat(BeanNameResolver.resolve(context, "orders", "Writer")).isEqualTo("ordersWriter");
    }

    @Test
    void resolve_hyphenatedNameWithCamelCaseBean_fallsBackToCamelCase() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.containsBean("calculator-soapWriter")).thenReturn(false);
        when(context.containsBean("calculatorSoapWriter")).thenReturn(true);

        assertThat(BeanNameResolver.resolve(context, "calculator-soap", "Writer"))
                .isEqualTo("calculatorSoapWriter");
    }

    @Test
    void resolve_neitherNameExists_returnsDirectNameAnyway() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.containsBean(anyString())).thenReturn(false);

        assertThat(BeanNameResolver.resolve(context, "calculator-soap", "Writer"))
                .isEqualTo("calculator-soapWriter");
    }

    @Test
    void resolve_nonHyphenatedNameNotFound_neverChecksCamelCase() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.containsBean(anyString())).thenReturn(false);

        assertThat(BeanNameResolver.resolve(context, "orders", "Processor")).isEqualTo("ordersProcessor");
        verify(context, never()).containsBean("ordersProcessor".toUpperCase());
    }

    @Test
    void toCamelCase_singleHyphen_capitalizesSecondPart() {
        assertThat(BeanNameResolver.toCamelCase("calculator-soap")).isEqualTo("calculatorSoap");
    }

    @Test
    void toCamelCase_multipleHyphens_capitalizesEachPart() {
        assertThat(BeanNameResolver.toCamelCase("exchange-rates-frankfurter"))
                .isEqualTo("exchangeRatesFrankfurter");
    }

    @Test
    void toCamelCase_noHyphens_returnsUnchanged() {
        assertThat(BeanNameResolver.toCamelCase("orders")).isEqualTo("orders");
    }
}
