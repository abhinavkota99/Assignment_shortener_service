package com.example.shortener.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import org.junit.Test;

public class SettingsTest {

    @Test
    public void defaultsFromEmptyEnvironment() {
        Settings s = Settings.fromMap(Map.of());
        assertEquals(8080, s.port());
        assertEquals("http://127.0.0.1:8080", s.baseUrl());
        assertEquals("data/links.jsonl", s.dataFile());
    }

    @Test
    public void overridesAndNormalisation() {
        Settings s = Settings.fromMap(Map.of("SHORTENER_PORT", "9000", "SHORTENER_BASE_URL", "https://sho.rt/",
                "SHORTENER_DATA_FILE", "", "SHORTENER_RATE_LIMIT_PER_SEC", "2.5"));
        assertEquals(9000, s.port());
        assertEquals("https://sho.rt", s.baseUrl());
        assertNull("blank data file means in-memory", s.dataFile());
        assertEquals(2.5, s.rateLimitPerSecond(), 0.0);
    }

    @Test
    public void failsFastOnBadValues() {
        try {
            Settings.fromMap(Map.of("SHORTENER_PORT", "eighty"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("SHORTENER_PORT"));
        }
        try {
            Settings.fromMap(Map.of("SHORTENER_CODE_LENGTH", "2"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("codeLength"));
        }
    }
}
