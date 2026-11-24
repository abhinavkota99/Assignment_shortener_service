package com.example.agentic.engine;

/** Outcome of a run. The AWAITING_* states are resumable; the rest are final. */
public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    /** Paused: a human must answer clarification questions. Resume with --answers. */
    AWAITING_INPUT,
    /** Paused: a human must approve or reject a checkpoint. Resume with --approvals. */
    AWAITING_APPROVAL,
    /** A stage failed after retries and fallback; workspace changes were rolled back. */
    FAILED,
    /** A human rejected a checkpoint that cannot be re-planned around (e.g. the release itself). */
    REJECTED,
    /** A guardrail or operator stopped the run; workspace was rolled back to the last safe checkpoint. */
    SAFE_STOPPED;

    public boolean resumable() {
        return this == AWAITING_INPUT || this == AWAITING_APPROVAL;
    }
}
