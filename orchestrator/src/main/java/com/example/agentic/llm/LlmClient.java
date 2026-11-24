package com.example.agentic.llm;

/**
 * Seam for model-backed reasoning. Agents treat model output as untrusted proposals: it is parsed,
 * validated against the catalog and either accepted or discarded in favour of the deterministic
 * fallback agent. A {@code null} client means "no model configured" (the default, offline mode).
 */
public interface LlmClient {

    /** Returns the model's text reply. Throws on transport or API errors. */
    String complete(String system, String user) throws Exception;

    /** As {@link #complete(String, String)} with a larger reply budget (e.g. for code generation). */
    default String complete(String system, String user, int maxTokens) throws Exception {
        return complete(system, user);
    }

    String model();
}
