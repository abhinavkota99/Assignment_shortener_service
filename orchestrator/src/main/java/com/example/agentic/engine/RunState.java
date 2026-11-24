package com.example.agentic.engine;

import com.example.agentic.context.Artifact;
import com.example.agentic.context.Decision;
import com.example.agentic.workflow.WorkflowGraph;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable state of one run: everything needed to resume after a pause, crash or human decision.
 * Written atomically to {@code runs/<runId>/state.json} after every stage transition.
 */
public final class RunState {

    String runId;
    String scenarioId;
    String scenarioDir;
    String workflow;
    RunStatus status = RunStatus.RUNNING;
    long createdAtMillis;
    Long finishedAtMillis;
    /** Wall-clock time actually spent executing (excludes time paused waiting for humans). */
    long activeMillis;
    String stopReason;
    Map<String, StageRecord> stages = new LinkedHashMap<>();
    Map<String, Artifact.Ref> artifacts = new LinkedHashMap<>();
    List<Decision> decisions = new ArrayList<>();
    /** Fan-out expansions applied to the graph, so the same graph is rebuilt on resume. */
    Map<String, List<WorkflowGraph.TaskNode>> expansions = new LinkedHashMap<>();
    /** Hash of the plan each expansion was built from; a different hash means the plan changed. */
    Map<String, String> expansionHashes = new LinkedHashMap<>();
    Map<String, Integer> counters = new LinkedHashMap<>();
    int resumes;

    private static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * The directory of a run, refusing ids that are not a single plain name. A run id like
     * {@code ../../x} would otherwise make the orchestrator write, restore and roll back files outside
     * the runs directory.
     */
    public static Path dirOf(Path runsDir, String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches() || runId.contains("..")) {
            throw new IllegalArgumentException("invalid run id '" + runId
                    + "': use letters, digits, '.', '_' and '-' only (max 100 characters, no '..')");
        }
        Path dir = runsDir.resolve(runId).normalize();
        if (!dir.getParent().equals(runsDir.normalize())) {
            throw new IllegalArgumentException("invalid run id '" + runId + "'");
        }
        return dir;
    }

    public static RunState load(Path runDir) {
        return Json.read(runDir.resolve("state.json"), RunState.class);
    }

    void save(Path runDir) {
        Json.writeAtomically(runDir.resolve("state.json"), Json.pretty(Json.tree(this)));
    }

    void increment(String counter) {
        counters.merge(counter, 1, Integer::sum);
    }

    public int counter(String name) {
        return counters.getOrDefault(name, 0);
    }

    public String runId() {
        return runId;
    }

    public String scenarioId() {
        return scenarioId;
    }

    public String scenarioDir() {
        return scenarioDir;
    }

    public RunStatus status() {
        return status;
    }

    public String stopReason() {
        return stopReason;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long activeMillis() {
        return activeMillis;
    }

    public Map<String, StageRecord> stages() {
        return stages;
    }

    public StageRecord stage(String id) {
        return stages.computeIfAbsent(id, k -> new StageRecord());
    }

    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    public Map<String, Integer> counters() {
        return Map.copyOf(counters);
    }

    public int resumes() {
        return resumes;
    }
}
