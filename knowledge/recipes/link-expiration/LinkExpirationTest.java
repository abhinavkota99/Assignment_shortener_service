package com.example.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.shortener.config.Settings;
import com.example.shortener.store.InMemoryLinkRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkExpirationTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final ShortenerServiceTest.MutableClock clock = new ShortenerServiceTest.MutableClock(NOW);
    private ShortenerService service;

    @BeforeEach
    void setUp() {
        Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
        service = new ShortenerService(new InMemoryLinkRepository(), settings, new UrlValidator(settings), new CodeGenerator(7), clock);
    }

    @Test
    void redirectsUntilExpiryThenGone() {
        Instant expiry = NOW.plus(Duration.ofHours(1));
        Link link = service.create("https://example.com/sale", null, null, expiry).link();
        assertEquals(expiry, link.expiresAt());
        assertEquals("https://example.com/sale", service.resolve(link.code(), null, null, true).targetUrl());

        clock.set(expiry);
        ServiceException e = assertThrows(ServiceException.class, () -> service.resolve(link.code(), "https://ref", null, true),
                "expected GONE at the expiry instant");
        assertEquals(ServiceException.Kind.GONE, e.kind());
        assertEquals(410, e.kind().httpStatus());
        assertEquals(1, service.stats(link.code(), 30).totalClicks(), "expired visits are not counted");
        assertEquals(expiry, service.get(link.code()).expiresAt(), "metadata stays available");
    }

    @Test
    void linksWithoutExpiryNeverExpire() {
        Link link = service.create("https://example.com/", null, null).link();
        assertNull(link.expiresAt());
        clock.set(NOW.plus(Duration.ofDays(10_000)));
        assertEquals("https://example.com/", service.resolve(link.code(), null, null, true).targetUrl());
    }

    @Test
    void expiryMustBeInTheFutureAndBounded() {
        for (Instant bad : new Instant[] {NOW, NOW.minusSeconds(1), NOW.plus(ShortenerService.MAX_LIFETIME).plusSeconds(1)}) {
            ServiceException e = assertThrows(ServiceException.class, () -> service.create("https://example.com/", null, null, bad));
            assertEquals(ServiceException.Kind.VALIDATION, e.kind());
        }
        assertTrue(service.create("https://example.com/", null, null, NOW.plus(ShortenerService.MAX_LIFETIME)).created());
    }

    @Test
    void customAliasCanExpire() {
        service.create("https://example.com/", "launch-day", null, NOW.plusSeconds(60));
        clock.set(NOW.plusSeconds(61));
        ServiceException e = assertThrows(ServiceException.class, () -> service.resolve("launch-day", null, null, false));
        assertEquals(ServiceException.Kind.GONE, e.kind());
    }
}
