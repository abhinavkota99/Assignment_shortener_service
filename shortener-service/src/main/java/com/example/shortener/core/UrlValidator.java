package com.example.shortener.core;

import com.example.shortener.config.Settings;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates destination URLs.
 *
 * <p>A shortener is an open redirect by design, so the risks are abuse (phishing disguises, redirect
 * loops) and sending users to internal hosts. The service never fetches the target, and this class
 * never performs DNS lookups: only IP literals and well-known local names are checked, which keeps
 * validation fast and side-effect free.
 */
public class UrlValidator {

    private static final Pattern AUTHORITY = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/?#]*)");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Settings settings;
    private final String ownHost;

    public UrlValidator(Settings settings) {
        this.settings = settings;
        this.ownHost = hostOf(settings.baseUrl());
    }

    /** Returns the normalised URL or throws a VALIDATION {@link ServiceException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ServiceException.validation("url is required and must be a non-empty string");
        }
        String url = raw.strip();
        if (url.length() > settings.maxUrlLength()) {
            throw ServiceException.validation("url exceeds " + settings.maxUrlLength() + " characters");
        }
        if (url.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20 || c == 0x7f)) {
            throw ServiceException.validation("url must not contain whitespace or control characters");
        }
        URI uri;
        try {
            uri = new URI(idnAuthority(url));
        } catch (URISyntaxException e) {
            throw ServiceException.validation("url is malformed: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ServiceException.validation("only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw ServiceException.validation("credentials in URLs are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw ServiceException.validation("url must include a valid host");
        }
        host = normaliseHost(host);
        if (!settings.allowPrivateTargets()) {
            if (isPrivate(host)) {
                throw ServiceException.validation("private, loopback and link-local targets are not allowed");
            }
            if (host.equals(ownHost)) {
                throw ServiceException.validation("url must not point back at this shortener (redirect loop)");
            }
        }
        checkHost(host);
        // Normalise scheme and host casing only; path, query and fragment are kept byte-for-byte
        // (already validated by the URI parser above, so they are not re-encoded).
        return scheme + "://"
                + (host.contains(":") ? "[" + host + "]" : host)
                + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
    }

    /** java.net.URI only understands ASCII hosts: convert an internationalised host to punycode first. */
    static String idnAuthority(String url) {
        Matcher m = AUTHORITY.matcher(url);
        if (!m.find() || m.group(2).chars().allMatch(c -> c < 0x80)) {
            return url;
        }
        String authority = m.group(2);
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = authority.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        String port = colon >= 0 ? hostPort.substring(colon) : "";
        try {
            host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw ServiceException.validation("url host is not a valid domain name");
        }
        return m.group(1) + userInfo + host + port + url.substring(m.end());
    }

    /** Extension point for additional host policy (subclasses may throw VALIDATION errors). */
    protected void checkHost(String normalisedHost) {
        // no additional policy by default
    }

    static String normaliseHost(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        h = h.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            return IDN.toASCII(h);
        } catch (IllegalArgumentException e) {
            throw ServiceException.validation("url host is not a valid domain name");
        }
    }

    static boolean isPrivate(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return true;
        }
        if (!host.contains(":") && !IPV4_LITERAL.matcher(host).matches()) {
            return false; // a DNS name: we do not resolve it (see class comment)
        }
        try {
            InetAddress addr = InetAddress.getByName(host); // literal: no DNS lookup is performed
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalV6(addr);
        } catch (UnknownHostException e) {
            throw ServiceException.validation("url host is not a valid IP address");
        }
    }

    private static boolean isUniqueLocalV6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc; // fc00::/7
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : normaliseHost(h);
        } catch (URISyntaxException | ServiceException e) {
            return "";
        }
    }
}
