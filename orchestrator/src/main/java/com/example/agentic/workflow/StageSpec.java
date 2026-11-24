package com.example.agentic.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Declarative definition of one node in the SDLC workflow graph.
 *
 * <p>Loaded from {@code workflows/*.json}. Concrete nodes are also generated at runtime when a
 * {@code fanOut} placeholder is expanded from the plan (one node per implementation task).
 */
public final class StageSpec {

    /** SDLC phase, used for reporting and for policy decisions (e.g. which stages may mutate code). */
    public enum Phase { REQUIREMENTS, ANALYSIS, PLANNING, DESIGN, IMPLEMENTATION, VERIFICATION, DOCUMENTATION, RELEASE }

    /** When a human approval checkpoint applies to this stage's output. */
    public enum Approval { NONE, IF_HIGH_IMPACT, ALWAYS }

    private String id;
    private Phase phase;
    private String agent;
    private String fallbackAgent;
    private List<String> dependsOn = new ArrayList<>();
    private List<String> entryGates = new ArrayList<>();
    private List<String> exitGates = new ArrayList<>();
    private Approval approval = Approval.NONE;
    private boolean mutatesWorkspace;
    private int maxAttempts = 1;
    private long initialBackoffMillis = 200;
    private long timeoutSeconds = 300;
    /** Placeholder stage expanded at runtime into one stage per item of the named plan list (e.g. "plan.tasks"). */
    private String fanOut;
    /** For expanded stages: the plan task this stage implements. */
    private String taskId;
    private String description;

    public StageSpec() {
        // for JSON mapping
    }

    public static StageSpec expandedTask(StageSpec template, String id, String taskId, List<String> dependsOn, String description) {
        StageSpec s = new StageSpec();
        s.id = id;
        s.phase = template.phase;
        s.agent = template.agent;
        s.fallbackAgent = template.fallbackAgent;
        s.dependsOn = new ArrayList<>(dependsOn);
        s.entryGates = new ArrayList<>(template.entryGates);
        s.exitGates = new ArrayList<>(template.exitGates);
        s.approval = template.approval;
        s.mutatesWorkspace = template.mutatesWorkspace;
        s.maxAttempts = template.maxAttempts;
        s.initialBackoffMillis = template.initialBackoffMillis;
        s.timeoutSeconds = template.timeoutSeconds;
        s.taskId = taskId;
        s.description = description;
        return s;
    }

    public void validate() {
        Objects.requireNonNull(id, "stage id");
        Objects.requireNonNull(phase, "phase of " + id);
        if (agent == null && fanOut == null) {
            throw new IllegalArgumentException("stage " + id + " needs an agent");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("stage " + id + ": maxAttempts must be 1..10 (bounded retries)");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("stage " + id + ": timeoutSeconds must be 1..3600");
        }
    }

    public String id() { return id; }
    public Phase phase() { return phase; }
    public String agent() { return agent; }
    public String fallbackAgent() { return fallbackAgent; }
    public List<String> dependsOn() { return List.copyOf(dependsOn); }
    public List<String> entryGates() { return List.copyOf(entryGates); }
    public List<String> exitGates() { return List.copyOf(exitGates); }
    public Approval approval() { return approval; }
    public boolean mutatesWorkspace() { return mutatesWorkspace; }
    public int maxAttempts() { return maxAttempts; }
    public long initialBackoffMillis() { return initialBackoffMillis; }
    public long timeoutSeconds() { return timeoutSeconds; }
    public String fanOut() { return fanOut; }
    public String taskId() { return taskId; }
    public String description() { return description; }
    public boolean isFanOut() { return fanOut != null; }

    /** Original dependencies of a fan-out placeholder, before expansion rewired it into a join node. */
    private List<String> fanOutDependsOn;

    /** For a fan-out placeholder: the dependencies it had before expansion (what generated stages wait for). */
    public List<String> baseDependencies() {
        return fanOutDependsOn();
    }

    List<String> fanOutDependsOn() {
        return fanOutDependsOn == null ? List.copyOf(dependsOn) : List.copyOf(fanOutDependsOn);
    }

    /** Turns a fan-out placeholder into a join barrier over the generated stages. */
    void becomeJoinOver(List<String> generatedIds) {
        if (fanOutDependsOn == null) {
            fanOutDependsOn = new ArrayList<>(dependsOn);
        }
        dependsOn = generatedIds.isEmpty() ? new ArrayList<>(fanOutDependsOn) : new ArrayList<>(generatedIds);
    }
}
