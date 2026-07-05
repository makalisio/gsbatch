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
package org.makalisio.gsbatch.core.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.makalisio.gsbatch.core.model.RestConfig;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RestGenericItemReaderBuilderTest {

    private RestGenericItemReaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new RestGenericItemReaderBuilder();
    }

    // ── Première tentative ───────────────────────────────────────────────────

    @Test
    void buildRetryTemplate_retryEnabled_firstAttemptExecutes() {
        // Regression test: Spring Retry consults canRetry() BEFORE the first
        // attempt (lastThrowable == null). A policy returning false there means
        // the HTTP call never executes at all.
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(3, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            return "OK";
        });

        assertThat(result).isEqualTo("OK");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void buildRetryTemplate_retryDisabled_firstAttemptExecutes() {
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(0, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            return "OK";
        });

        assertThat(result).isEqualTo("OK");
        assertThat(attempts).hasValue(1);
    }

    // ── Codes HTTP retryables ────────────────────────────────────────────────

    @Test
    void buildRetryTemplate_retryableHttpCode_retriesUpToMaxRetries() {
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(3, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        })).isInstanceOf(HttpServerErrorException.class);

        // 1 initial attempt + 3 retries
        assertThat(attempts).hasValue(4);
    }

    @Test
    void buildRetryTemplate_retryableHttpCode_succeedsAfterTransientError() {
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(3, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        String result = retryTemplate.execute(context -> {
            if (attempts.incrementAndGet() < 3) {
                throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return "OK";
        });

        assertThat(result).isEqualTo("OK");
        assertThat(attempts).hasValue(3);
    }

    // ── Erreurs non retryables ───────────────────────────────────────────────

    @Test
    void buildRetryTemplate_nonRetryableHttpCode_failsWithoutRetry() {
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(3, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void buildRetryTemplate_nonHttpException_failsWithoutRetry() {
        RetryTemplate retryTemplate = builder.buildRetryTemplate(restConfig(3, 10), "test");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(attempts).hasValue(1);
    }

    // ── Authentication dispatch ──────────────────────────────────────────────

    @Test
    void buildRestTemplate_apiKeyAuth_addsHeaderToRequest() {
        RestConfig config = new RestConfig();
        config.setUrl("https://api.example.com/x");
        config.getAuth().setType("API_KEY");
        config.getAuth().setApiKey("secret123");
        config.getAuth().setHeaderName("X-Api-Key");

        RestTemplate restTemplate = builder.buildRestTemplate(config, "test");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/x"))
                .andExpect(header("X-Api-Key", "secret123"))
                .andRespond(withSuccess());

        restTemplate.getForEntity("https://api.example.com/x", String.class);

        server.verify();
    }

    @Test
    void buildRestTemplate_bearerAuth_addsAuthorizationHeader() {
        RestConfig config = new RestConfig();
        config.setUrl("https://api.example.com/x");
        config.getAuth().setType("BEARER");
        config.getAuth().setBearerToken("token-abc");

        RestTemplate restTemplate = builder.buildRestTemplate(config, "test");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/x"))
                .andExpect(header("Authorization", "Bearer token-abc"))
                .andRespond(withSuccess());

        restTemplate.getForEntity("https://api.example.com/x", String.class);

        server.verify();
    }

    @Test
    void buildRestTemplate_noneAuth_addsNoAuthHeader() {
        RestConfig config = new RestConfig();
        config.setUrl("https://api.example.com/x");
        // auth.type defaults to NONE

        RestTemplate restTemplate = builder.buildRestTemplate(config, "test");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.example.com/x"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(headerDoesNotExist("X-Api-Key"))
                .andRespond(withSuccess());

        restTemplate.getForEntity("https://api.example.com/x", String.class);

        server.verify();
    }

    @Test
    void buildRestTemplate_oauth2ClientCredentials_throwsUnsupportedOperation() {
        RestConfig config = new RestConfig();
        config.setUrl("https://api.example.com/x");
        config.getAuth().setType("OAUTH2_CLIENT_CREDENTIALS");

        assertThatThrownBy(() -> builder.buildRestTemplate(config, "test"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("OAUTH2_CLIENT_CREDENTIALS");
    }

    @Test
    void buildRestTemplate_unknownAuthType_throwsIllegalState() {
        RestConfig config = new RestConfig();
        config.setUrl("https://api.example.com/x");
        config.getAuth().setType("HMAC");

        assertThatThrownBy(() -> builder.buildRestTemplate(config, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RestConfig restConfig(int maxRetries, long retryDelay) {
        RestConfig config = new RestConfig();
        config.getRetry().setMaxRetries(maxRetries);
        config.getRetry().setRetryDelay(retryDelay);
        return config;
    }
}
