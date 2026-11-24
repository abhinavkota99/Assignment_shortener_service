package com.example.shortener.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void normalisesBaseUrlAndBlankDataFile() {
        Settings s = new Settings("https://sho.rt/", "  ", 7, 5, 2048, 16384, 2.5, 10, false);
        assertEquals("https://sho.rt", s.baseUrl());
        assertNull(s.dataFile(), "blank data file means in-memory storage");
        assertEquals(2.5, s.rateLimitPerSecond());
    }

    @Test
    void failsFastOnInvalidValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Settings("http://x", null, 2, 5, 2048, 16384, 5, 20, false));
        assertTrue(e.getMessage().contains("code-length"));
        assertThrows(IllegalArgumentException.class, () -> new Settings("http://x", null, 7, 0, 2048, 16384, 5, 20, false));
        assertThrows(IllegalArgumentException.class, () -> new Settings("http://x", null, 7, 5, 2048, 16384, 0, 20, false));
    }

    @Test
    void copyHelpers() {
        Settings s = Settings.defaults().withBaseUrl("https://a.b").withRateLimit(1, 2).withPrivateTargetsAllowed();
        assertEquals("https://a.b", s.baseUrl());
        assertEquals(2, s.rateLimitBurst());
        assertTrue(s.allowPrivateTargets());
    }
}
