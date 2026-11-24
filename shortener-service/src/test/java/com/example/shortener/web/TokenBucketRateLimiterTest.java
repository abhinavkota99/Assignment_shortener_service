package com.example.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void allowsBurstThenLimitsThenRefills() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 3, nanos::get, 1000);
        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("a").allowed());
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(1, denied.retryAfterSeconds());
        assertTrue(limiter.tryAcquire("b").allowed(), "other clients are independent");

        nanos.addAndGet(500_000_000L); // 0.5 s at 2/s = 1 token
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test
    void evictsFullyRefilledBucketsToBoundMemory() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 1, nanos::get, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        nanos.addAndGet(5_000_000_000L);
        limiter.tryAcquire("c"); // exceeds max -> refilled a/b evicted
        assertTrue(limiter.trackedClients() <= 2);
    }
}
