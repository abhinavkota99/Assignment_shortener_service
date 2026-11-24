package com.example.shortener.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.shortener.config.Settings;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.store.InMemoryLinkRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class LinkExpirationApiTest {

    private ShortenerApi api;

    @Before
    public void setUp() {
        Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
        api = new ShortenerApi(new ShortenerService(new InMemoryLinkRepository(), settings), new TokenBucketRateLimiter(100, 100));
    }

    @Test
    public void expiresAtRoundTripsThroughTheContract() {
        String expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        ApiResponse created = create("{\"url\":\"https://example.com\",\"alias\":\"promo-1\",\"expiresAt\":\"" + expiry + "\"}");
        assertEquals(201, created.status());
        assertEquals(expiry, body(created).get("expiresAt"));
        assertEquals(expiry, body(api.handle(new ApiRequest("GET", "/api/v1/links/promo-1", null, null, "c"))).get("expiresAt"));
        assertEquals(302, api.handle(new ApiRequest("GET", "/promo-1", null, null, "c")).status());
    }

    @Test
    public void linksWithoutExpiryExposeNull() {
        ApiResponse created = create("{\"url\":\"https://example.com\"}");
        assertEquals(201, created.status());
        assertNull(body(created).get("expiresAt"));
    }

    @Test
    public void invalidExpiryIsRejected() {
        assertError(create("{\"url\":\"https://example.com\",\"expiresAt\":\"tomorrow\"}"), 400, "validation_failed");
        assertError(create("{\"url\":\"https://example.com\",\"expiresAt\":\"2001-01-01T00:00:00Z\"}"), 400, "validation_failed");
        assertError(create("{\"url\":\"https://example.com\",\"expiresAt\":12345}"), 400, "validation_failed");
    }

    @Test
    public void expiredLinkReturns410() throws Exception {
        String soon = Instant.now().plus(1, ChronoUnit.SECONDS).toString();
        assertEquals(201, create("{\"url\":\"https://example.com\",\"alias\":\"flash\",\"expiresAt\":\"" + soon + "\"}").status());
        Thread.sleep(1_200);
        assertError(api.handle(new ApiRequest("GET", "/flash", null, null, "c")), 410, "gone");
        assertEquals(200, api.handle(new ApiRequest("GET", "/api/v1/links/flash/stats", null, null, "c")).status());
    }

    private ApiResponse create(String json) {
        return api.handle(new ApiRequest("POST", "/api/v1/links", Map.of("Content-Type", "application/json"),
                json.getBytes(StandardCharsets.UTF_8), "client"));
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
}
