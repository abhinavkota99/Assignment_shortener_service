package com.example.shortener.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed, validated configuration bound from {@code shortener.*} properties (application.yml, environment
 * variables such as {@code SHORTENER_BASE_URL}, or command-line arguments). Invalid values fail startup
 * instead of surfacing later as runtime errors.
 *
 * @param dataFile journal file for durable storage; blank means in-memory only
 */
@ConfigurationProperties("shortener")
public record Settings(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("data/links.jsonl") String dataFile,
        @DefaultValue("7") int codeLength,
        @DefaultValue("5") int maxCodeAttempts,
        @DefaultValue("2048") int maxUrlLength,
        @DefaultValue("16384") int maxBodyBytes,
        @DefaultValue("5.0") double rateLimitPerSecond,
        @DefaultValue("20") int rateLimitBurst,
        @DefaultValue("false") boolean allowPrivateTargets) {

    public Settings {
        Objects.requireNonNull(baseUrl, "shortener.base-url");
        if (codeLength < 4 || codeLength > 32) {
            throw new IllegalArgumentException("shortener.code-length must be between 4 and 32");
        }
        if (maxCodeAttempts < 1) {
            throw new IllegalArgumentException("shortener.max-code-attempts must be >= 1");
        }
        if (maxUrlLength < 32 || maxBodyBytes < 256) {
            throw new IllegalArgumentException("shortener.max-url-length >= 32 and shortener.max-body-bytes >= 256 are required");
        }
        if (rateLimitPerSecond <= 0 || rateLimitBurst <= 0) {
            throw new IllegalArgumentException("shortener rate limit values must be positive");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        dataFile = dataFile == null || dataFile.isBlank() ? null : dataFile;
    }

    /** Defaults for tests: in-memory storage, generous limits. */
    public static Settings defaults() {
        return new Settings("http://localhost:8080", null, 7, 5, 2048, 16 * 1024, 5.0, 20, false);
    }

    public Settings withBaseUrl(String newBaseUrl) {
        return new Settings(newBaseUrl, dataFile, codeLength, maxCodeAttempts, maxUrlLength, maxBodyBytes,
                rateLimitPerSecond, rateLimitBurst, allowPrivateTargets);
    }

    public Settings withRateLimit(double perSecond, int burst) {
        return new Settings(baseUrl, dataFile, codeLength, maxCodeAttempts, maxUrlLength, maxBodyBytes,
                perSecond, burst, allowPrivateTargets);
    }

    public Settings withPrivateTargetsAllowed() {
        return new Settings(baseUrl, dataFile, codeLength, maxCodeAttempts, maxUrlLength, maxBodyBytes,
                rateLimitPerSecond, rateLimitBurst, true);
    }
}
