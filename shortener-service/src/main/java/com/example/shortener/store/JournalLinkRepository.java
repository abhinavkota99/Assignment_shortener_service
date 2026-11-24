package com.example.shortener.store;

import com.example.shortener.core.ClickEvent;
import com.example.shortener.core.Link;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Durable repository: an append-only JSON-lines journal replayed into memory at startup.
 *
 * <p>Every event is written (and flushed to the OS) before the in-memory state changes, so an
 * acknowledged write survives a process crash. A torn final line from a crash mid-write is
 * skipped on replay; corruption anywhere else fails startup loudly instead of losing data silently.
 * Each record carries a format version ({@code v}) so the schema can evolve with replay-time
 * upgrades instead of rewriting the file.
 */
public final class JournalLinkRepository extends InMemoryLinkRepository {

    static final int FORMAT_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(JournalLinkRepository.class);

    private final ObjectMapper json = JsonMapper.builder().build(); // Jackson 3: immutable, thread-safe
    private final Path file;
    private final BufferedWriter writer;
    private volatile boolean healthy = true;

    public JournalLinkRepository(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            replay();
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open journal " + file, e);
        }
    }

    private void replay() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                apply(json.readTree(line));
            } catch (RuntimeException e) { // Jackson 3 parse errors (JacksonException) are unchecked
                if (i == lines.size() - 1) {
                    LOG.warn("ignoring torn final journal record at line {}", i + 1);
                    continue;
                }
                throw new IllegalStateException("corrupt journal " + file + " at line " + (i + 1), e);
            }
        }
    }

    private void apply(JsonNode n) {
        int version = n.path("v").asInt(1);
        if (version > FORMAT_VERSION) {
            throw new IllegalStateException("journal record version " + version + " is newer than supported " + FORMAT_VERSION);
        }
        switch (n.path("type").asString()) {
            case "link" -> applyInsert(
                    new Link(n.get("code").asString(), n.get("targetUrl").asString(),
                            Instant.parse(n.get("createdAt").asString()), n.path("custom").asBoolean()),
                    textOrNull(n, "idempotencyKey"));
            case "click" -> applyClick(new ClickEvent(n.get("code").asString(), Instant.parse(n.get("at").asString()),
                    textOrNull(n, "referrer"), textOrNull(n, "userAgent")));
            default -> throw new IllegalStateException("unknown journal record type: " + n.path("type"));
        }
    }

    @Override
    protected void beforeInsert(Link link, String idempotencyKey) {
        ObjectNode n = record("link");
        n.put("code", link.code()).put("targetUrl", link.targetUrl())
                .put("createdAt", link.createdAt().toString()).put("custom", link.custom());
        if (idempotencyKey != null) {
            n.put("idempotencyKey", idempotencyKey);
        }
        append(n);
    }

    @Override
    protected void beforeClick(ClickEvent click) {
        ObjectNode n = record("click");
        n.put("code", click.code()).put("at", click.at().toString());
        if (click.referrer() != null) {
            n.put("referrer", click.referrer());
        }
        if (click.userAgent() != null) {
            n.put("userAgent", click.userAgent());
        }
        append(n);
    }

    private ObjectNode record(String type) {
        ObjectNode n = json.createObjectNode();
        n.put("v", FORMAT_VERSION).put("type", type);
        return n;
    }

    private void append(ObjectNode n) {
        try {
            writer.write(json.writeValueAsString(n)); // JacksonException is unchecked in Jackson 3
            writer.newLine();
            writer.flush();
            healthy = true;
        } catch (IOException e) {
            healthy = false; // surfaces through /readyz so the instance is taken out of rotation
            throw new UncheckedIOException("journal write failed", e);
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    @Override
    public boolean healthy() {
        return healthy;
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
