package com.example.shortener.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-client token bucket. Each client may burst {@code burst} requests, refilled continuously at
 * {@code ratePerSecond}. In-process only: with several instances behind a load balancer the effective
 * limit is multiplied by the instance count (documented trade-off; production would use a shared store).
 */
public class TokenBucketRateLimiter {

    /** Result of an acquire attempt; {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) { }

    private record Bucket(double tokens, long lastNanos) { }

    private final double ratePerNano;
    private final int burst;
    private final LongSupplier nanoClock;
    private final int maxClients;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double ratePerSecond, int burst) {
        this(ratePerSecond, burst, System::nanoTime, 100_000);
    }

    TokenBucketRateLimiter(double ratePerSecond, int burst, LongSupplier nanoClock, int maxClients) {
        if (ratePerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("ratePerSecond and burst must be positive");
        }
        this.ratePerNano = ratePerSecond / 1_000_000_000d;
        this.burst = burst;
        this.nanoClock = nanoClock;
        this.maxClients = maxClients;
    }

    public Decision tryAcquire(String clientKey) {
        long now = nanoClock.getAsLong();
        boolean[] allowed = new boolean[1];
        Bucket updated = buckets.compute(clientKey, (k, b) -> {
            double tokens = b == null ? burst : Math.min(burst, b.tokens() + (now - b.lastNanos()) * ratePerNano);
            allowed[0] = tokens >= 1.0;
            return new Bucket(allowed[0] ? tokens - 1.0 : tokens, now);
        });
        if (buckets.size() > maxClients) {
            evictRefilled(now);
        }
        if (allowed[0]) {
            return new Decision(true, 0);
        }
        double secondsToToken = (1.0 - updated.tokens()) / (ratePerNano * 1_000_000_000d);
        return new Decision(false, Math.max(1, (long) Math.ceil(secondsToToken)));
    }

    /** Buckets that have refilled completely carry no state; dropping them bounds memory. */
    private void evictRefilled(long now) {
        buckets.entrySet().removeIf(e -> e.getValue().tokens() + (now - e.getValue().lastNanos()) * ratePerNano >= burst);
    }

    int trackedClients() {
        return buckets.size();
    }
}
