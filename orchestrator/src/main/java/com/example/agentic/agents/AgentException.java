package com.example.agentic.agents;

/**
 * Agent failure. {@link Kind} decides the orchestrator's response:
 * TRANSIENT is retried with backoff (bounded), PERMANENT goes to the fallback agent or fails the
 * stage, UNAVAILABLE means the agent cannot run in this environment (e.g. no LLM configured) and the
 * fallback is used without counting a failure.
 */
public class AgentException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind { TRANSIENT, PERMANENT, UNAVAILABLE }

    private final Kind kind;

    public AgentException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AgentException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static AgentException permanent(String message) {
        return new AgentException(Kind.PERMANENT, message);
    }

    public static AgentException transientFailure(String message) {
        return new AgentException(Kind.TRANSIENT, message);
    }
}
