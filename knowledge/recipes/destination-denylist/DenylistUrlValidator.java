package com.example.shortener.core;

import com.example.shortener.config.Settings;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * URL validation plus an operator-configured denylist of destination domains. A listed domain also
 * blocks all of its subdomains ({@code evil.example} blocks {@code a.evil.example}), so attackers
 * cannot bypass it with a fresh subdomain.
 */
public class DenylistUrlValidator extends UrlValidator {

    private final Set<String> blocked;

    public DenylistUrlValidator(Settings settings, Set<String> blockedDomains) {
        super(settings);
        this.blocked = blockedDomains.stream().map(d -> normaliseHost(d.trim())).filter(d -> !d.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Reads {@code SHORTENER_BLOCKED_HOSTS} (comma-separated); an empty value means no denylist. */
    public static UrlValidator fromEnv(Settings settings, Map<String, String> env) {
        String raw = env.getOrDefault("SHORTENER_BLOCKED_HOSTS", "");
        Set<String> domains = Arrays.stream(raw.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return domains.isEmpty() ? new UrlValidator(settings) : new DenylistUrlValidator(settings, domains);
    }

    @Override
    protected void checkHost(String normalisedHost) {
        for (String domain : blocked) {
            if (normalisedHost.equals(domain) || normalisedHost.endsWith("." + domain)) {
                throw ServiceException.validation("destination domain '" + domain + "' is blocked");
            }
        }
    }

    Set<String> blockedDomains() {
        return blocked;
    }
}
