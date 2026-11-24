package com.example.agentic.engine;

/** Lifecycle of a stage within a run. */
public enum StageStatus {
    PENDING,
    RUNNING,
    /** Waiting out a backoff before the next bounded retry. */
    RETRY_WAIT,
    /** Output produced but gated on a human decision. */
    AWAITING_APPROVAL,
    /** Exit gate needs human input (e.g. clarification of an ambiguous requirement). */
    AWAITING_INPUT,
    SUCCEEDED,
    FAILED,
    /** Not run because an upstream stage failed or the run stopped. */
    SKIPPED,
    /** Its changes were reverted after a failure. */
    ROLLED_BACK;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == ROLLED_BACK;
    }
}
