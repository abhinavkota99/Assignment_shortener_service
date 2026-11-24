package com.example.shortener.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.shortener.config.Settings;
import com.example.shortener.core.LinkStats;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.store.InMemoryLinkRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ShortenerApiTest {

    private ShortenerApi api;

    @Before
    public void setUp() {
        Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
        api = new ShortenerApi(new ShortenerService(new InMemoryLinkRepository(), settings), new TokenBucketRateLimiter(100, 100));
    }

    @Test
    public void createThenRedirectThenStats() {
        ApiResponse created = post("{\"url\":\"https://example.com/x\",\"alias\":\"launch\"}", Map.of());
        assertEquals(201, created.status());
        assertEquals("/api/v1/links/launch", created.headers().get("Location"));
        assertEquals("https://sho.rt/launch", body(created).get("shortUrl"));

        ApiResponse redirect = api.handle(new ApiRequest("GET", "/launch", Map.of("Referer", "https://news.example"), null, "c"));
        assertEquals(302, redirect.status());
        assertEquals("https://example.com/x", redirect.headers().get("Location"));
        assertNull(redirect.body());

        assertEquals(302, api.handle(new ApiRequest("HEAD", "/launch", null, null, "c")).status());

        ApiResponse stats = api.handle(new ApiRequest("GET", "/api/v1/links/launch/stats?days=7", null, null, "c"));
        assertEquals(200, stats.status());
        LinkStats s = (LinkStats) stats.body();
        assertEquals("HEAD must not be counted", 1, s.totalClicks());
        assertEquals(7, s.windowDays());

        ApiResponse meta = api.handle(new ApiRequest("GET", "/api/v1/links/launch", null, null, "c"));
        assertEquals(200, meta.status());
        assertEquals(true, body(meta).get("custom"));
    }

    @Test
    public void idempotentReplayReturns200() {
        assertEquals(201, post("{\"url\":\"https://example.com/x\"}", Map.of("Idempotency-Key", "k1")).status());
        assertEquals(200, post("{\"url\":\"https://example.com/x\"}", Map.of("Idempotency-Key", "k1")).status());
    }

    @Test
    public void errorMapping() {
        assertError(post("{\"url\":\"ftp://x\"}", Map.of()), 400, "validation_failed");
        assertError(post("not json", Map.of()), 400, "invalid_json");
        assertError(post("[1,2]", Map.of()), 400, "invalid_json");
        assertError(post("{\"url\":\"https://e.com\",\"admin\":true}", Map.of()), 400, "validation_failed");
        assertError(post("{\"url\":42}", Map.of()), 400, "validation_failed");
        assertError(api.handle(new ApiRequest("POST", "/api/v1/links", Map.of("Content-Type", "text/plain"), bytes("{}"), "c")), 415, "unsupported_media_type");
        assertError(api.handle(new ApiRequest("GET", "/api/v1/links", null, null, "c")), 405, "method_not_allowed");
        assertError(api.handle(new ApiRequest("GET", "/missing1", null, null, "c")), 404, "not_found");
        assertError(api.handle(new ApiRequest("GET", "/a/b/c", null, null, "c")), 404, "not_found");
        assertError(api.handle(new ApiRequest("DELETE", "/abc", null, null, "c")), 405, "method_not_allowed");
        post("{\"url\":\"https://example.com/x\",\"alias\":\"taken\"}", Map.of());
        assertError(post("{\"url\":\"https://example.com/y\",\"alias\":\"taken\"}", Map.of()), 409, "conflict");
        assertError(api.handle(new ApiRequest("GET", "/api/v1/links/taken/stats?days=abc", null, null, "c")), 400, "validation_failed");
    }

    @Test
    public void rateLimitReturns429WithRetryAfter() {
        Settings settings = Settings.defaults();
        ShortenerApi limited = new ShortenerApi(new ShortenerService(new InMemoryLinkRepository(), settings), new TokenBucketRateLimiter(0.1, 1));
        ApiRequest req = new ApiRequest("POST", "/api/v1/links", Map.of("Content-Type", "application/json"), bytes("{\"url\":\"https://e.com\"}"), "same-client");
        assertEquals(201, limited.handle(req).status());
        ApiResponse denied = limited.handle(req);
        assertError(denied, 429, "rate_limited");
        assertTrue(Long.parseLong(denied.headers().get("Retry-After")) >= 1);
        assertEquals(Long.valueOf(1), limited.metrics().get("rate_limited_total"));
    }

    @Test
    public void healthAndMetrics() {
        assertEquals(200, api.handle(new ApiRequest("GET", "/healthz", null, null, "c")).status());
        assertEquals(200, api.handle(new ApiRequest("GET", "/readyz", null, null, "c")).status());
        api.handle(new ApiRequest("GET", "/nope-nope", null, null, "c"));
        @SuppressWarnings("unchecked")
        Map<String, Long> m = (Map<String, Long>) api.handle(new ApiRequest("GET", "/metrics", null, null, "c")).body();
        assertTrue(m.get("http_requests_total") >= 3);
        assertEquals(Long.valueOf(1), m.get("http_4xx"));
    }

    private ApiResponse post(String json, Map<String, String> extraHeaders) {
        java.util.HashMap<String, String> h = new java.util.HashMap<>(extraHeaders);
        h.put("Content-Type", "application/json; charset=utf-8");
        return api.handle(new ApiRequest("POST", "/api/v1/links", h, bytes(json), "client"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ApiResponse r) {
        return (Map<String, Object>) r.body();
    }

    @SuppressWarnings("unchecked")
    private static void assertError(ApiResponse r, int status, String code) {
        assertEquals(status, r.status());
        assertEquals(code, ((Map<String, Object>) body(r).get("error")).get("code"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
