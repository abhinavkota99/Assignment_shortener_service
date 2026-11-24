package com.example.shortener.config;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable runtime configuration. Every value has a safe default so the service runs with zero
 * configuration; every value can be overridden through environment variables (12-factor).
 */
public record Settings(
        String host,
        int port,
        String baseUrl,
        String dataFile,
        int codeLength,
        int maxCodeAttempts,
        int maxUrlLength,
        int maxBodyBytes,
        double rateLimitPerSecond,
        int rateLimitBurst,
        boolean allowPrivateTargets) {

    public Settings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (codeLength < 4 || codeLength > 32) {
            throw new IllegalArgumentException("codeLength must be between 4 and 32");
        }
        if (maxCodeAttempts < 1) {
            throw new IllegalArgumentException("maxCodeAttempts must be >= 1");
        }
        if (rateLimitPerSecond <= 0 || rateLimitBurst <= 0) {
            throw new IllegalArgumentException("rate limit values must be positive");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Defaults suitable for local development and tests. {@code dataFile == null} means in-memory. */
    public static Settings defaults() {
        return new Settings("127.0.0.1", 8080, "http://127.0.0.1:8080", null, 7, 5, 2048, 16 * 1024, 5.0, 20, false);
    }

    public static Settings fromEnv() {
        return fromMap(System.getenv());
    }

    static Settings fromMap(Map<String, String> env) {
        Settings d = defaults();
        String host = env.getOrDefault("SHORTENER_HOST", d.host());
        int port = intVal(env, "SHORTENER_PORT", d.port());
        return new Settings(
                host,
                port,
                env.getOrDefault("SHORTENER_BASE_URL", "http://" + host + ":" + port),
                blankToNull(env.getOrDefault("SHORTENER_DATA_FILE", "data/links.jsonl")),
                intVal(env, "SHORTENER_CODE_LENGTH", d.codeLength()),
                intVal(env, "SHORTENER_MAX_CODE_ATTEMPTS", d.maxCodeAttempts()),
                intVal(env, "SHORTENER_MAX_URL_LENGTH", d.maxUrlLength()),
                intVal(env, "SHORTENER_MAX_BODY_BYTES", d.maxBodyBytes()),
                doubleVal(env, "SHORTENER_RATE_LIMIT_PER_SEC", d.rateLimitPerSecond()),
                intVal(env, "SHORTENER_RATE_LIMIT_BURST", d.rateLimitBurst()),
                "1".equals(env.get("SHORTENER_ALLOW_PRIVATE_TARGETS")));
    }

    public Settings withBaseUrl(String newBaseUrl) {
        return new Settings(host, port, newBaseUrl, dataFile, codeLength, maxCodeAttempts, maxUrlLength,
                maxBodyBytes, rateLimitPerSecond, rateLimitBurst, allowPrivateTargets);
    }

    public Settings withRateLimit(double perSecond, int burst) {
        return new Settings(host, port, baseUrl, dataFile, codeLength, maxCodeAttempts, maxUrlLength,
                maxBodyBytes, perSecond, burst, allowPrivateTargets);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static int intVal(Map<String, String> env, String key, int fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + raw + "'", e);
        }
    }

    private static double doubleVal(Map<String, String> env, String key, double fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + raw + "'", e);
        }
    }
}
