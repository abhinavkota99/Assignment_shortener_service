package com.example.shortener.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.shortener.core.ClickEvent;
import com.example.shortener.core.Link;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JournalLinkRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Instant T = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    public void stateSurvivesRestart() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("data/links.jsonl");
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
    public void duplicateIsRejectedAndNotJournaled() throws Exception {
        Path file = tmp.newFile("links.jsonl").toPath();
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("dup1234", "https://a.example", T, false), null);
            try {
                repo.insert(new Link("dup1234", "https://b.example", T, false), null);
                fail();
            } catch (DuplicateKeyException e) {
                assertEquals(DuplicateKeyException.Field.CODE, e.field());
            }
        }
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    public void tornFinalLineIsIgnored() throws Exception {
        Path file = tmp.newFile("torn.jsonl").toPath();
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            repo.insert(new Link("ok12345", "https://a.example", T, false), null);
        }
        Files.writeString(file, "{\"v\":1,\"type\":\"link\",\"code\":\"half", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (JournalLinkRepository repo = new JournalLinkRepository(file)) {
            assertEquals(1, repo.linkCount());
        }
    }

    @Test
    public void corruptionInTheMiddleFailsLoudly() throws Exception {
        Path file = tmp.newFile("corrupt.jsonl").toPath();
        Files.writeString(file, "garbage\n{\"v\":1,\"type\":\"link\",\"code\":\"x1234\",\"targetUrl\":\"https://a\",\"createdAt\":\"2026-11-20T12:00:00Z\"}\n");
        try {
            new JournalLinkRepository(file).close();
            fail("expected startup failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("line 1"));
        }
    }

    @Test
    public void newerFormatVersionIsRefused() throws Exception {
        Path file = tmp.newFile("future.jsonl").toPath();
        Files.writeString(file, "{\"v\":99,\"type\":\"link\"}\n{\"v\":1,\"type\":\"link\",\"code\":\"x1234\",\"targetUrl\":\"https://a\",\"createdAt\":\"2026-11-20T12:00:00Z\"}\n");
        try {
            new JournalLinkRepository(file).close();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getCause().getMessage().contains("newer"));
        }
    }
}
