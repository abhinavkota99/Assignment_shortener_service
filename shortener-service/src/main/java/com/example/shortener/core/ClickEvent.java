package com.example.shortener.core;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded redirect. Deliberately stores no client IP address: analytics do not need it and it
 * would make the click log personal data (see docs/ENGINEERING_SUMMARY.md, privacy decision D-07).
 */
public record ClickEvent(String code, Instant at, String referrer, String userAgent) {

    public ClickEvent {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");
    }
}
