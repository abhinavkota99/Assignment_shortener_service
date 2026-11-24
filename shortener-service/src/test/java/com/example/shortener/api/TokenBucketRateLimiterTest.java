package com.example.shortener.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    @Test
    public void allowsBurstThenLimitsThenRefills() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 3, nanos::get, 1000);
        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("a").allowed());
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(1, denied.retryAfterSeconds());
        assertTrue("other clients are independent", limiter.tryAcquire("b").allowed());

        nanos.addAndGet(500_000_000L); // 0.5s at 2/s = 1 token
        assertTrue(limiter.tryAcquire("a").allowed());
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test
    public void evictsFullyRefilledBucketsToBoundMemory() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 1, nanos::get, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        nanos.addAndGet(5_000_000_000L);
        limiter.tryAcquire("c"); // exceeds max -> refilled a/b evicted
        assertTrue(limiter.trackedClients() <= 2);
    }
}
