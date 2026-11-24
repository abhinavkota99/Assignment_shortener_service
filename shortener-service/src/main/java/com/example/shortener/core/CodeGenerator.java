package com.example.shortener.core;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Generates random base62 short codes and validates user-chosen aliases. */
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{2,31}$");
    /** Aliases must never shadow the service's own routes. */
    static final Set<String> RESERVED = Set.of("api", "actuator", "healthz", "readyz", "metrics", "admin", "static", "error", "favicon.ico", "v3", "swagger-ui");

    private final SecureRandom random;
    private final int length;

    public CodeGenerator(int length) {
        this(length, new SecureRandom());
    }

    CodeGenerator(int length, SecureRandom random) {
        if (length < 4) {
            throw new IllegalArgumentException("code length must be >= 4");
        }
        this.length = length;
        this.random = random;
    }

    /**
     * Random, non-sequential code from a CSPRNG so codes cannot be enumerated. At length 7 the space is
     * 62^7 (~3.5e12); collisions are possible and are handled by the caller retrying.
     */
    public String next() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Returns a human-readable problem description, or {@code null} if the alias is acceptable. */
    public static String aliasProblem(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            return "alias must be 3-32 characters of [A-Za-z0-9_-] and start with a letter or digit";
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            return "alias '" + alias + "' is reserved";
        }
        return null;
    }
}
