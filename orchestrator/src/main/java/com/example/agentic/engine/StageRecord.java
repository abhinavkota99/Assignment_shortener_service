package com.example.agentic.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable execution record for one stage. Persisted in run state after every transition. */
public final class StageRecord {

    /** A human checkpoint this stage's output is waiting on. */
    public record Checkpoint(String checkpoint, String reason) { }

    StageStatus status = StageStatus.PENDING;
    int attempts;
    /** Times this stage was re-executed because an upstream artifact changed or a human rejected it. */
    int reExecutions;
    String agentUsed;
    boolean usedFallback;
    /** Artifact key -> content hash this stage read; used to detect stale results. */
    Map<String, String> consumed = new LinkedHashMap<>();
    List<String> produced = new ArrayList<>();
    Long startedAtMillis;
    Long finishedAtMillis;
    Long firstFailureAtMillis;
    Long recoveredAtMillis;
    Long retryNotBeforeMillis;
    String lastError;
    String approval;
    /** Attempts by the currently selected agent (primary or fallback), for the bounded-retry budget. */
    int agentAttempts;
    List<Checkpoint> pendingCheckpoints = new ArrayList<>();
    List<String> gateResults = new ArrayList<>();
    List<Long> attemptDurationsMillis = new ArrayList<>();

    public StageStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public int reExecutions() {
        return reExecutions;
    }

    public boolean usedFallback() {
        return usedFallback;
    }

    public String lastError() {
        return lastError;
    }

    public String approval() {
        return approval;
    }

    public Map<String, String> consumed() {
        return Map.copyOf(consumed);
    }

    public List<String> produced() {
        return List.copyOf(produced);
    }

    public List<String> gateResults() {
        return List.copyOf(gateResults);
    }

    public List<Checkpoint> pendingCheckpoints() {
        return List.copyOf(pendingCheckpoints);
    }

    public String agentUsed() {
        return agentUsed;
    }

    public Long durationMillis() {
        return startedAtMillis == null || finishedAtMillis == null ? null : finishedAtMillis - startedAtMillis;
    }

    public Long recoveryMillis() {
        return firstFailureAtMillis == null || recoveredAtMillis == null ? null : recoveredAtMillis - firstFailureAtMillis;
    }

    /** Back to PENDING for re-execution; counters and failure history are kept for metrics. */
    void reset() {
        status = StageStatus.PENDING;
        consumed.clear();
        produced.clear();
        startedAtMillis = null;
        finishedAtMillis = null;
        retryNotBeforeMillis = null;
        lastError = null;
        approval = null;
        attempts = 0;
        agentAttempts = 0;
        usedFallback = false;
        gateResults.clear();
        pendingCheckpoints.clear();
    }
}
