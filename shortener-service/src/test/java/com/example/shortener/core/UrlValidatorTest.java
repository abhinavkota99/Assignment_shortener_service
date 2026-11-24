package com.example.shortener.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.shortener.config.Settings;
import org.junit.Test;

public class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(Settings.defaults().withBaseUrl("https://sho.rt"));

    @Test
    public void acceptsAndNormalisesSchemeAndHostOnly() {
        assertEquals("https://example.com/Path?Q=1#Frag", validator.validate("  HTTPS://Example.COM/Path?Q=1#Frag "));
        assertEquals("http://example.com:8080/a%20b", validator.validate("http://example.com:8080/a%20b"));
        assertEquals("https://xn--bcher-kva.example/", validator.validate("https://bücher.example/"));
    }

    @Test
    public void rejectsBadInput() {
        rejects(null, "required");
        rejects("   ", "required");
        rejects("ftp://example.com", "only http and https");
        rejects("javascript:alert(1)", "only http and https");
        rejects("https://user:pass@example.com", "credentials");
        rejects("https://exa mple.com", "whitespace");
        rejects("https://", "malformed");
        rejects("https://example.com/" + "a".repeat(2100), "exceeds");
    }

    @Test
    public void rejectsPrivateAndLoopbackTargets() {
        for (String url : new String[] {
                "http://localhost/x", "http://127.0.0.1/", "http://10.1.2.3/", "http://192.168.0.1",
                "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://[fd00::1]/", "http://db.internal/"}) {
            rejects(url, "private");
        }
    }

    @Test
    public void rejectsRedirectLoopToItself() {
        rejects("https://sho.rt/abc1234", "redirect loop");
    }

    @Test
    public void privateTargetsAllowedWhenConfigured() {
        Settings s = new Settings("127.0.0.1", 8080, "http://127.0.0.1:8080", null, 7, 5, 2048, 1024, 5, 20, true);
        assertEquals("http://10.0.0.1/", new UrlValidator(s).validate("http://10.0.0.1/"));
    }

    private void rejects(String url, String messageFragment) {
        try {
            validator.validate(url);
            fail("expected rejection of " + url);
        } catch (ServiceException e) {
            assertEquals(ServiceException.Kind.VALIDATION, e.kind());
            assertTrue(url + " -> " + e.getMessage(), e.getMessage().contains(messageFragment));
        }
    }
}
