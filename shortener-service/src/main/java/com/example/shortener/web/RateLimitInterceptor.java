package com.example.shortener.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client token bucket on link creation (the abuse-prone write path). Redirects are not limited:
 * they are the product's hot path and are safe to serve.
 *
 * <p>The client address is used only in memory as the bucket key; it is never stored or logged.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketRateLimiter limiter;
    private final MeterRegistry metrics;

    public RateLimitInterceptor(TokenBucketRateLimiter limiter, MeterRegistry metrics) {
        this.limiter = limiter;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String clientIp = request.getRemoteAddr();
        TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(clientIp);
        if (!decision.allowed()) {
            metrics.counter("shortener.rate_limited").increment();
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
        return true;
    }
}
