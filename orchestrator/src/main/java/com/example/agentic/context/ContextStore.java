package com.example.agentic.context;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Cross-stage shared context. Every write creates a new immutable version on disk
 * ({@code artifacts/<key>@v<n>.json}); only the index of latest versions is kept in run state, so
 * the full history of every artifact is preserved for audit and replay.
 */
public final class ContextStore {

    private final Path dir;
    private final LongSupplier clock;
    private final Map<String, Artifact.Ref> index = new ConcurrentHashMap<>();
    private final Map<String, Artifact> cache = new ConcurrentHashMap<>();

    public ContextStore(Path runDir, Map<String, Artifact.Ref> existingIndex, LongSupplier clock) {
        this.dir = runDir.resolve("artifacts");
        this.clock = clock;
        if (existingIndex != null) {
            index.putAll(existingIndex);
        }
    }

    /** Stores a new version unless the content is identical to the latest one (idempotent writes). */
    public synchronized Artifact put(String key, String producer, JsonNode content) {
        String hash = Json.sha256(content);
        Artifact.Ref latest = index.get(key);
        if (latest != null && latest.sha256().equals(hash)) {
            return get(key).orElseThrow();
        }
        int version = latest == null ? 1 : latest.version() + 1;
        Artifact a = new Artifact(key, version, producer, hash, clock.getAsLong(), content.deepCopy());
        Json.writeAtomically(file(key, version), Json.pretty(Json.tree(a)));
        index.put(key, a.ref());
        cache.put(key, a);
        return a;
    }

    public Optional<Artifact> get(String key) {
        Artifact.Ref ref = index.get(key);
        if (ref == null) {
            return Optional.empty();
        }
        Artifact cached = cache.get(key);
        if (cached != null && cached.version() == ref.version()) {
            return Optional.of(cached);
        }
        Path f = file(key, ref.version());
        if (!Files.exists(f)) {
            throw new IllegalStateException("artifact file missing for " + key + "@v" + ref.version());
        }
        Artifact a = Json.convert(Json.read(f), Artifact.class);
        if (!Json.sha256(a.content()).equals(ref.sha256())) {
            throw new IllegalStateException("artifact " + key + "@v" + ref.version() + " failed integrity check");
        }
        cache.put(key, a);
        return Optional.of(a);
    }

    public JsonNode content(String key) {
        return get(key).map(Artifact::content)
                .orElseThrow(() -> new IllegalStateException("required artifact not available: " + key));
    }

    public Optional<String> hash(String key) {
        return Optional.ofNullable(index.get(key)).map(Artifact.Ref::sha256);
    }

    public synchronized void remove(String key) {
        index.remove(key);
        cache.remove(key);
    }

    public Map<String, Artifact.Ref> index() {
        return new LinkedHashMap<>(index);
    }

    private Path file(String key, int version) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + "@v" + version + ".json");
    }
}
