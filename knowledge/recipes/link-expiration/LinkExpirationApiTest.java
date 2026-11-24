package com.example.shortener.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.config.Settings;
import com.example.shortener.core.ShortenerService;
import com.example.shortener.store.InMemoryLinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LinkExpirationApiTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Settings settings = Settings.defaults().withBaseUrl("https://sho.rt");
        mvc = MockMvcBuilders
                .standaloneSetup(new LinkController(new ShortenerService(new InMemoryLinkRepository(), settings), new SimpleMeterRegistry()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void expiresAtRoundTripsThroughTheContract() throws Exception {
        String expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        create("{\"url\":\"https://example.com\",\"alias\":\"promo-1\",\"expiresAt\":\"" + expiry + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(expiry));
        mvc.perform(get("/api/v1/links/promo-1")).andExpect(jsonPath("$.expiresAt").value(expiry));
        mvc.perform(get("/promo-1")).andExpect(status().isFound());
    }

    @Test
    void linksWithoutExpiryExposeNull() throws Exception {
        create("{\"url\":\"https://example.com\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(nullValue()));
    }

    @Test
    void invalidExpiryIsRejected() throws Exception {
        for (String body : new String[] {
                "{\"url\":\"https://example.com\",\"expiresAt\":\"tomorrow\"}",
                "{\"url\":\"https://example.com\",\"expiresAt\":\"2001-01-01T00:00:00Z\"}",
                "{\"url\":\"https://example.com\",\"expiresAt\":12345}"}) {
            create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("validation_failed"));
        }
    }

    @Test
    void expiredLinkReturns410ButStatsRemain() throws Exception {
        String soon = Instant.now().plus(1, ChronoUnit.SECONDS).toString();
        create("{\"url\":\"https://example.com\",\"alias\":\"flash\",\"expiresAt\":\"" + soon + "\"}").andExpect(status().isCreated());
        Thread.sleep(1_200);
        mvc.perform(get("/flash")).andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("gone"));
        mvc.perform(get("/api/v1/links/flash/stats")).andExpect(status().isOk());
    }
}
