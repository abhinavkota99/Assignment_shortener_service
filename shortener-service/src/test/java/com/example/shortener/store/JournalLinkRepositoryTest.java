package com.example.shortener.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.shortener.core.ClickEvent;
import com.example.shortener.core.Link;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalLinkRepositoryTest {

    private static final Instant T = Instant.parse("2026-11-20T12:00:00Z");

    @TempDir
    Path tmp;

    @Test
    void stateSurvivesRestart() {
        Path file = tmp.resolve("data/links.jsonl");
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("abc1234", "https://example.com", T, false), "idem-1");
            repo.recordClick(new ClickEvent("abc1234", T, "https://ref", "UA"));
            repo.recordClick(new ClickEvent("abc1234", T, null, null));
        }
        try (JournalLinkRepository reopened = new JournalLinkRepository(file)) {
            assertEquals("https://example.com", reopened.find("abc1234").orElseThrow().targetUrl());
            assertEquals("abc1234", reopened.findByIdempotencyKey("idem-1").orElseThrow().code());
            assertEquals(2, reopened.clicks("abc1234").size());
            assertEquals("https://ref", reopened.clicks("abc1234").get(0).referrer());
        }
    }

    @Test
    void duplicateIsRejectedAndNotJournaled() throws Exception {
        Path file = tmp.resolve("links.jsonl");
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("dup1234", "https://a.example", T, false), null);
            DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                    () -> repo.insert(new Link("dup1234", "https://b.example", T, false), null));
            assertEquals(DuplicateKeyException.Field.CODE, e.field());
        }
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void tornFinalLineIsIgnored() throws Exception {
        Path file = tmp.resolve("torn.jsonl");
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("ok12345", "https://a.example", T, false), null);
        }
        Files.writeString(file, "{\"v\":1,\"type\":\"link\",\"code\":\"half", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            assertEquals(1, repo.linkCount());
        }
    }

    @Test
    void corruptionInTheMiddleFailsLoudly() throws Exception {
        Path file = tmp.resolve("corrupt.jsonl");
        Files.writeString(file, "garbage\n{\"v\":1,\"type\":\"link\",\"code\":\"x1234\",\"targetUrl\":\"https://a\",\"createdAt\":\"2026-11-20T12:00:00Z\"}\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JournalLinkRepository(file).close());
        assertTrue(e.getMessage().contains("line 1"));
    }

    @Test
    void newerFormatVersionIsRefused() throws Exception {
        Path file = tmp.resolve("future.jsonl");
        Files.writeString(file, "{\"v\":99,\"type\":\"link\"}\n{\"v\":1,\"type\":\"link\",\"code\":\"x1234\",\"targetUrl\":\"https://a\",\"createdAt\":\"2026-11-20T12:00:00Z\"}\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JournalLinkRepository(file).close());
        assertTrue(e.getCause().getMessage().contains("newer"));
    }
}
