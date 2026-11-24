package com.example.shortener.web;

/** Thrown by {@link RateLimitInterceptor}; mapped to 429 with Retry-After. */
public class RateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("rate limited");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
