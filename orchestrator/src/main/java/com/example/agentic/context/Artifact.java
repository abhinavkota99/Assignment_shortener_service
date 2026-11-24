package com.example.agentic.context;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An immutable, versioned output of a stage (or a human input). The content hash is what downstream
 * stages record as "consumed", which is how the orchestrator detects that an upstream output changed.
 */
public record Artifact(String key, int version, String producer, String sha256, long createdAtMillis, JsonNode content) {

    /** Lightweight index entry persisted in run state (content lives in its own file). */
    public record Ref(String key, int version, String producer, String sha256, long createdAtMillis) { }

    public Ref ref() {
        return new Ref(key, version, producer, sha256, createdAtMillis);
    }
}
