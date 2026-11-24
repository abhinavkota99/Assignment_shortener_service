package com.example.shortener.core;

import java.util.List;

/** Click analytics for one link (timestamps ISO-8601 UTC) over a trailing window of {@code windowDays} days (UTC). */
public record LinkStats(
        String code,
        long totalClicks,
        String lastClickAt,
        List<Count> clicksByDay,
        List<Count> topReferrers,
        int windowDays) {

    /** A labelled count: a date (yyyy-MM-dd) or a referrer. */
    public record Count(String key, long count) { }
}
