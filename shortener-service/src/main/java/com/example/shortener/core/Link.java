package com.example.shortener.core;

import java.time.Instant;
import java.util.Objects;

/** A short code mapped to a destination URL. Immutable. */
public record Link(String code, String targetUrl, Instant createdAt, boolean custom) {

    public Link {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
