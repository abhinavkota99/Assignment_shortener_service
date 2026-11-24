package com.example.shortener.core;

import com.example.shortener.config.Settings;
import java.util.Arrays;
import java.util.Locale;
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

    /** Builds a validator from a comma-separated list (e.g. {@code shortener.blocked-hosts}); empty means no denylist. */
    public static UrlValidator fromCsv(Settings settings, String csv) {
        Set<String> domains = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
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
