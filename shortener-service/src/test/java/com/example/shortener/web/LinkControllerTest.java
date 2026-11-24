package com.example.shortener.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.config.Settings;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.store.InMemoryLinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract tests: routing, status codes, headers and error mapping, without starting a server. */
class LinkControllerTest {

    private MockMvc mvc;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        mvc = mvcWith(new TokenBucketRateLimiter(100, 100));
    }

    private MockMvc mvcWith(TokenBucketRateLimiter limiter) {
        Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
        metrics = new SimpleMeterRegistry();
        return MockMvcBuilders
                .standaloneSetup(new LinkController(new ShortenerService(new InMemoryLinkRepository(), settings), metrics))
                .setControllerAdvice(new ApiExceptionHandler())
                .addMappedInterceptors(new String[] {"/api/v1/links"}, new RateLimitInterceptor(limiter, metrics))
                .addFilters(new RequestIdFilter(settings))
                .build();
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void createThenRedirectThenStats() throws Exception {
        create("{\"url\":\"https://example.com/x\",\"alias\":\"launch\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/launch"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("launch"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/launch"))
                .andExpect(jsonPath("$.custom").value(true));

        mvc.perform(get("/launch").header("Referer", "https://news.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/x"))
                .andExpect(header().string("Cache-Control", "private, max-age=0"));

        mvc.perform(head("/launch")).andExpect(status().isFound()); // not counted

        mvc.perform(get("/api/v1/links/launch/stats").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.windowDays").value(7))
                .andExpect(jsonPath("$.topReferrers[0].key").value("https://news.example"));

        mvc.perform(get("/api/v1/links/launch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/x"));
    }

    @Test
    void idempotentReplayReturns200WithSameLink() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k1")
                .content("{\"url\":\"https://example.com/x\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k1")
                .content("{\"url\":\"https://example.com/x\"}")).andExpect(status().isOk());
    }

    @Test
    void validationAndParsingErrors() throws Exception {
        expectError(create("{\"url\":\"ftp://x\"}"), 400, "validation_failed");
        expectError(create("not json"), 400, "invalid_json");
        expectError(create("[1,2]"), 400, "invalid_json");
        expectError(create("{\"url\":\"https://e.com\",\"admin\":true}"), 400, "validation_failed");
        expectError(create("{\"url\":42}"), 400, "validation_failed");
        create("{\"url\":\"https://example.com/x\",\"alias\":\"taken\"}").andExpect(status().isCreated());
        expectError(create("{\"url\":\"https://example.com/y\",\"alias\":\"taken\"}"), 409, "conflict");
        expectError(mvc.perform(get("/api/v1/links/taken/stats").param("days", "abc")), 400, "validation_failed");
        expectError(mvc.perform(get("/api/v1/links/taken/stats").param("days", "0")), 400, "validation_failed");
    }

    @Test
    void protocolErrors() throws Exception {
        expectError(mvc.perform(post("/api/v1/links").contentType(MediaType.TEXT_PLAIN).content("{}")), 415, "unsupported_media_type");
        expectError(mvc.perform(get("/api/v1/links")), 405, "method_not_allowed");
        expectError(mvc.perform(get("/missing1")), 404, "not_found");
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://e.com/" + "a".repeat(20_000) + "\"}"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("payload_too_large"));
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        mvc = mvcWith(new TokenBucketRateLimiter(0.1, 1));
        create("{\"url\":\"https://e.com\"}").andExpect(status().isCreated());
        expectError(create("{\"url\":\"https://e.com\"}"), 429, "rate_limited")
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/v1/links/nope123")).andExpect(status().isNotFound()); // reads are not rate limited
        assertEquals(1.0, metrics.counter("shortener.rate_limited").count());
    }

    @Test
    void clientRequestIdIsEchoedOnlyWhenSane() throws Exception {
        mvc.perform(get("/api/v1/links/abc1234").header("X-Request-ID", "req-42"))
                .andExpect(header().string("X-Request-ID", "req-42"));
        mvc.perform(get("/api/v1/links/abc1234").header("X-Request-ID", "bad id\r\nInjected: 1"))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.not("bad id\r\nInjected: 1")))
                .andExpect(header().string("X-Request-ID", matchesPattern("[0-9a-f-]{36}")));
    }

    private static ResultActions expectError(ResultActions r, int statusCode, String code) throws Exception {
        return r.andExpect(status().is(statusCode)).andExpect(jsonPath("$.error.code").value(code));
    }
}
