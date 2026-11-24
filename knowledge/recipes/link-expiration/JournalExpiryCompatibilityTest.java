package com.example.shortener.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.shortener.core.Link;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The journal format gained an optional field; existing data must keep loading unchanged. */
public class JournalExpiryCompatibilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Instant T = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    public void expiryIsPersisted() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("links.jsonl");
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("exp1234", "https://example.com", T, false, T.plusSeconds(3600)), null);
        }
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            assertEquals(T.plusSeconds(3600), repo.find("exp1234").orElseThrow().expiresAt());
        }
    }

    @Test
    public void recordsWrittenBeforeTheChangeLoadAsNeverExpiring() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("old.jsonl");
        Files.writeString(file, "{\"v\":1,\"type\":\"link\",\"code\":\"old1234\",\"targetUrl\":\"https://a.example\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"custom\":false}\n");
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            assertNull(repo.find("old1234").orElseThrow().expiresAt());
        }
    }
}
