package com.example.shortener.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.shortener.config.Settings;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class DenylistUrlValidatorTest {

    private final Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
    private final UrlValidator validator = new DenylistUrlValidator(settings, Set.of("Evil.Example", " phish.test "));

    @Test
    public void blocksListedDomainsAndTheirSubdomains() {
        for (String url : new String[] {"https://evil.example/x", "https://a.b.EVIL.example/", "http://phish.test:8080/login",
                "https://evil.example./trailing-dot"}) {
            try {
                validator.validate(url);
                fail("expected " + url + " to be blocked");
            } catch (ServiceException e) {
                assertEquals(ServiceException.Kind.VALIDATION, e.kind());
                assertTrue(e.getMessage().contains("blocked"));
            }
        }
    }

    @Test
    public void allowsLookalikesThatAreNotSubdomains() {
        assertEquals("https://notevil.example/", validator.validate("https://notevil.example/"));
        assertEquals("https://evil.example.com/", validator.validate("https://evil.example.com/"));
    }

    @Test
    public void baseValidationStillApplies() {
        try {
            validator.validate("ftp://good.example/");
            fail();
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("http"));
        }
    }

    @Test
    public void environmentWiring() {
        UrlValidator none = DenylistUrlValidator.fromEnv(settings, Map.of());
        assertFalse(none instanceof DenylistUrlValidator);
        UrlValidator configured = DenylistUrlValidator.fromEnv(settings, Map.of("SHORTENER_BLOCKED_HOSTS", "evil.example, ,bad.test"));
        assertTrue(configured instanceof DenylistUrlValidator);
        assertEquals(Set.of("evil.example", "bad.test"), ((DenylistUrlValidator) configured).blockedDomains());
    }
}
