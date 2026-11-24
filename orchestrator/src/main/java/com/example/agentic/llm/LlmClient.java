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

    /**
     * The API refused the request and repeating it will not help (bad request, no credit, unknown model,
     * invalid key). Agents treat it as a permanent failure; transport errors, rate limits and server
     * overload are {@link java.io.IOException}s and are retried.
     */
    final class RejectedException extends Exception {
        private static final long serialVersionUID = 1L;

        public RejectedException(String message) {
            super(message);
        }
    }
}
