package com.example.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.shortener.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(Settings.defaults().withBaseUrl("https://sho.rt"));

    @Test
    void acceptsAndNormalisesSchemeAndHostOnly() {
        assertEquals("https://example.com/Path?Q=1#Frag", validator.validate("  HTTPS://Example.COM/Path?Q=1#Frag "));
        assertEquals("http://example.com:8080/a%20b", validator.validate("http://example.com:8080/a%20b"));
        assertEquals("https://xn--bcher-kva.example/", validator.validate("https://bücher.example/"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ftp://example.com            | only http and https",
            "javascript:alert(1)          | only http and https",
            "https://user:pass@example.com | credentials",
            "https://exa mple.com         | whitespace",
            "https://                     | malformed"})
    void rejectsBadInput(String url, String fragment) {
        assertRejected(url, fragment);
    }

    @Test
    void rejectsMissingAndOversizedUrls() {
        assertRejected(null, "required");
        assertRejected("   ", "required");
        assertRejected("https://example.com/" + "a".repeat(2100), "exceeds");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/x", "http://127.0.0.1/", "http://10.1.2.3/", "http://192.168.0.1",
            "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://[fd00::1]/", "http://db.internal/"})
    void rejectsPrivateAndLoopbackTargets(String url) {
        assertRejected(url, "private");
    }

    @Test
    void rejectsRedirectLoopToItself() {
        assertRejected("https://sho.rt/abc1234", "redirect loop");
    }

    @Test
    void privateTargetsAllowedWhenConfigured() {
        assertEquals("http://10.0.0.1/", new UrlValidator(Settings.defaults().withPrivateTargetsAllowed()).validate("http://10.0.0.1/"));
    }

    private void assertRejected(String url, String fragment) {
        ServiceException e = assertThrows(ServiceException.class, () -> validator.validate(url), () -> "expected rejection of " + url);
        assertEquals(ServiceException.Kind.VALIDATION, e.kind());
        assertTrue(e.getMessage().contains(fragment), () -> url + " -> " + e.getMessage());
    }
}
