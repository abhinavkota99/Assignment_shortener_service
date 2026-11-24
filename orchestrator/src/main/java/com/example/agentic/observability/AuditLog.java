package com.example.agentic.observability;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Append-only, tamper-evident audit trail ({@code audit.jsonl}).
 *
 * <p>Each entry carries {@code prevHash} and its own {@code hash} = SHA-256(prevHash + canonical entry),
 * forming a hash chain: editing, removing or reordering any entry breaks verification of every later
 * entry. The chain continues across resumes of the same run.
 */
public final class AuditLog implements AutoCloseable {

    public static final String GENESIS = "0".repeat(64);

    /** Result of {@link #verify(Path)}. */
    public record Verification(boolean valid, int entries, String detail) { }

    private final Path file;
    private final String runId;
    private final LongSupplier clock;
    private final BufferedWriter writer;
    private String lastHash;
    private long seq;

    public AuditLog(Path file, String runId, LongSupplier clock) {
        this.file = file;
        this.runId = runId;
        this.clock = clock;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Verification v = verify(file);
            if (!v.valid()) {
                throw new IllegalStateException("refusing to append to a broken audit chain: " + v.detail());
            }
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            if (lines.isEmpty()) {
                lastHash = GENESIS;
                seq = 0;
            } else {
                JsonNode last = Json.MAPPER.readTree(lines.get(lines.size() - 1));
                lastHash = last.get("hash").asText();
                seq = last.get("seq").asLong();
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open audit log " + file, e);
        }
    }

    /** Records an event. {@code actor} is "orchestrator", an agent name, or a human identity. */
    public synchronized void record(String type, String stage, String actor, JsonNode data) {
        ObjectNode e = Json.object();
        e.put("seq", ++seq);
        e.put("ts", Instant.ofEpochMilli(clock.getAsLong()).toString());
        e.put("runId", runId);
        e.put("type", type);
        if (stage != null) {
            e.put("stage", stage);
        }
        e.put("actor", actor);
        e.set("data", data == null ? Json.object() : data);
        e.put("prevHash", lastHash);
        String hash = chainHash(lastHash, e);
        e.put("hash", hash);
        try {
            writer.write(Json.compact(e));
            writer.newLine();
            writer.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException("audit write failed (fail closed)", ex);
        }
        lastHash = hash;
    }

    public void record(String type, String stage, String actor, String message) {
        ObjectNode d = Json.object();
        d.put("message", message);
        record(type, stage, actor, d);
    }

    public Path file() {
        return file;
    }

    private static String chainHash(String prevHash, ObjectNode entryWithoutHash) {
        ObjectNode copy = entryWithoutHash.deepCopy();
        copy.remove("hash");
        return Json.sha256(prevHash + Json.sha256(copy));
    }

    /** Recomputes the chain end to end. */
    public static Verification verify(Path file) {
        if (!Files.exists(file)) {
            return new Verification(true, 0, "no audit log yet");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String prev = GENESIS;
            long expectedSeq = 1;
            for (String line : lines) {
                ObjectNode e = (ObjectNode) Json.MAPPER.readTree(line);
                if (e.path("seq").asLong() != expectedSeq) {
                    return new Verification(false, (int) expectedSeq - 1, "sequence gap at entry " + expectedSeq);
                }
                if (!e.path("prevHash").asText().equals(prev)) {
                    return new Verification(false, (int) expectedSeq - 1, "prevHash mismatch at entry " + expectedSeq);
                }
                String recorded = e.path("hash").asText();
                if (!chainHash(prev, e).equals(recorded)) {
                    return new Verification(false, (int) expectedSeq - 1, "hash mismatch (entry modified) at entry " + expectedSeq);
                }
                prev = recorded;
                expectedSeq++;
            }
            return new Verification(true, lines.size(), "hash chain intact");
        } catch (IOException | ClassCastException e) {
            return new Verification(false, 0, "unreadable audit log: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
