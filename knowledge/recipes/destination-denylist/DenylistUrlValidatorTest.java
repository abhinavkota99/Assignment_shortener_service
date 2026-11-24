package com.example.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.shortener.config.Settings;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DenylistUrlValidatorTest {

    private final Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
    private final UrlValidator validator = new DenylistUrlValidator(settings, Set.of("Evil.Example", " phish.test "));

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/x", "https://a.b.EVIL.example/", "http://phish.test:8080/login",
            "https://evil.example./trailing-dot"})
    void blocksListedDomainsAndTheirSubdomains(String url) {
        ServiceException e = assertThrows(ServiceException.class, () -> validator.validate(url));
        assertEquals(ServiceException.Kind.VALIDATION, e.kind());
        assertTrue(e.getMessage().contains("blocked"));
    }

    @Test
    void allowsLookalikesThatAreNotSubdomains() {
        assertEquals("https://notevil.example/", validator.validate("https://notevil.example/"));
        assertEquals("https://evil.example.com/", validator.validate("https://evil.example.com/"));
    }

    @Test
    void baseValidationStillApplies() {
        ServiceException e = assertThrows(ServiceException.class, () -> validator.validate("ftp://good.example/"));
        assertTrue(e.getMessage().contains("http"));
    }

    @Test
    void configurationWiring() {
        assertFalse(DenylistUrlValidator.fromCsv(settings, "") instanceof DenylistUrlValidator);
        assertFalse(DenylistUrlValidator.fromCsv(settings, null) instanceof DenylistUrlValidator);
        UrlValidator configured = DenylistUrlValidator.fromCsv(settings, "evil.example, ,bad.test");
        assertInstanceOf(DenylistUrlValidator.class, configured);
        assertEquals(Set.of("evil.example", "bad.test"), ((DenylistUrlValidator) configured).blockedDomains());
    }
}
