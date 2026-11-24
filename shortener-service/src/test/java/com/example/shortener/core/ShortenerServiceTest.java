package com.example.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.shortener.config.Settings;
import com.example.shortener.store.InMemoryLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShortenerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
    private InMemoryLinkRepository repo;
    private ShortenerService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryLinkRepository();
        service = new ShortenerService(repo, settings, new UrlValidator(settings), new CodeGenerator(7), clock);
    }

    @Test
    void createsAndResolvesLink() {
        ShortenerService.CreateResult r = service.create("https://example.com/a", null, null);
        assertTrue(r.created());
        assertEquals(7, r.link().code().length());
        assertEquals(NOW, r.link().createdAt());
        assertEquals("https://sho.rt/" + r.link().code(), service.shortUrl(r.link().code()));
        assertEquals("https://example.com/a", service.resolve(r.link().code(), null, null, true).targetUrl());
    }

    @Test
    void customAliasConflictIs409() {
        service.create("https://example.com/a", "promo", null);
        ServiceException e = assertThrows(ServiceException.class, () -> service.create("https://example.com/b", "promo", null));
        assertEquals(ServiceException.Kind.CONFLICT, e.kind());
    }

    @Test
    void idempotencyKeyReplaysOriginalLink() {
        ShortenerService.CreateResult first = service.create("https://example.com/a", null, "key-1");
        ShortenerService.CreateResult second = service.create("https://example.com/a", null, "key-1");
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.link(), second.link());
        assertEquals(1, repo.linkCount());
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateExactlyOneLink() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ShortenerService.CreateResult>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> service.create("https://example.com/race", null, "same-key"));
            }
            List<Future<ShortenerService.CreateResult>> results = pool.invokeAll(calls);
            String code = results.get(0).get().link().code();
            int created = 0;
            for (Future<ShortenerService.CreateResult> f : results) {
                assertEquals(code, f.get().link().code());
                created += f.get().created() ? 1 : 0;
            }
            assertEquals(1, created);
            assertEquals(1, repo.linkCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retriesOnCodeCollisionThenFailsSafely() {
        AtomicInteger calls = new AtomicInteger();
        CodeGenerator colliding = new CodeGenerator(7) {
            @Override
            public String next() {
                return calls.getAndIncrement() < 2 ? "AAAAAAA" : "BBBBBBB";
            }
        };
        ShortenerService s = new ShortenerService(repo, settings, new UrlValidator(settings), colliding, clock);
        assertEquals("AAAAAAA", s.create("https://example.com/1", null, null).link().code());
        assertEquals("BBBBBBB", s.create("https://example.com/2", null, null).link().code()); // retried once

        CodeGenerator alwaysSame = new CodeGenerator(7) {
            @Override
            public String next() {
                return "AAAAAAA";
            }
        };
        ShortenerService stuck = new ShortenerService(repo, settings, new UrlValidator(settings), alwaysSame, clock);
        ServiceException e = assertThrows(ServiceException.class, () -> stuck.create("https://example.com/3", null, null));
        assertEquals(ServiceException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    void resolveWithoutRecordDoesNotCountClick() {
        String code = service.create("https://example.com/a", null, null).link().code();
        service.resolve(code, null, null, false);
        assertEquals(0, service.stats(code, 30).totalClicks());
    }

    @Test
    void statsAggregateByDayAndReferrer() {
        String code = service.create("https://example.com/a", null, null).link().code();
        clock.set(NOW.minus(40, ChronoUnit.DAYS));
        service.resolve(code, "https://old.example", null, true); // outside the 30-day per-day window
        clock.set(NOW.minus(1, ChronoUnit.DAYS));
        service.resolve(code, "https://news.example", "UA", true);
        clock.set(NOW);
        service.resolve(code, "https://news.example", "UA", true);
        service.resolve(code, null, "UA", true);

        LinkStats stats = service.stats(code, 30);
        assertEquals(4, stats.totalClicks());
        assertEquals(NOW.toString(), stats.lastClickAt());
        assertEquals(List.of(new LinkStats.Count("2026-11-19", 1), new LinkStats.Count("2026-11-20", 2)), stats.clicksByDay());
        assertEquals(new LinkStats.Count("https://news.example", 2), stats.topReferrers().get(0));
        assertTrue(stats.topReferrers().contains(new LinkStats.Count("(direct)", 1)));
    }

    @Test
    void statsValidation() {
        String code = service.create("https://example.com/a", null, null).link().code();
        assertNull(service.stats(code, 1).lastClickAt());
        for (int bad : new int[] {0, 366}) {
            ServiceException e = assertThrows(ServiceException.class, () -> service.stats(code, bad));
            assertEquals(ServiceException.Kind.VALIDATION, e.kind());
        }
    }

    @Test
    void unknownCodeIsNotFound() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.resolve("nope123", null, null, true));
        assertEquals(ServiceException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    void headerValuesAreClipped() {
        assertNull(ShortenerService.clip(""));
        assertEquals(ShortenerService.MAX_HEADER_VALUE_LENGTH, ShortenerService.clip("x".repeat(5000)).length());
        assertEquals("short", ShortenerService.clip("short"));
    }

    /** Test clock that can be moved. Shared with other tests in this package. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
