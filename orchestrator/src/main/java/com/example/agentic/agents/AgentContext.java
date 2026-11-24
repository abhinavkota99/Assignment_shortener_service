package com.example.agentic.agents;

import com.example.agentic.context.ContextStore;
import com.example.agentic.engine.StageStatus;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.llm.LlmClient;
import com.example.agentic.workflow.StageSpec;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An agent's view of the run. Every artifact read goes through {@link #input(String)} so the
 * orchestrator knows exactly which upstream versions a result depends on (decision lineage) and can
 * re-execute the stage when any of them changes.
 */
public final class AgentContext {

    /** Recorded for optional inputs that did not exist when read; appearing later counts as a change. */
    public static final String ABSENT = "absent";

    private final String runId;
    private final StageSpec stage;
    private final int attempt;
    private final ContextStore context;
    private final Workspace workspace;
    private final Scenario scenario;
    private final Catalog catalog;
    private final PolicyEngine policy;
    private final LlmClient llm;
    private final Path repoRoot;
    private final Path runDir;
    private final Function<String, StageStatus> stageStatus;
    private final Map<String, String> consumed = new LinkedHashMap<>();

    public AgentContext(String runId, StageSpec stage, int attempt, ContextStore context, Workspace workspace, Scenario scenario,
                        Catalog catalog, PolicyEngine policy, LlmClient llm, Path repoRoot, Path runDir,
                        Function<String, StageStatus> stageStatus) {
        this.runId = runId;
        this.stage = stage;
        this.attempt = attempt;
        this.context = context;
        this.workspace = workspace;
        this.scenario = scenario;
        this.catalog = catalog;
        this.policy = policy;
        this.llm = llm;
        this.repoRoot = repoRoot;
        this.runDir = runDir;
        this.stageStatus = stageStatus;
    }

    /** Required upstream artifact. */
    public JsonNode input(String key) throws AgentException {
        Optional<JsonNode> v = optionalInput(key);
        if (v.isEmpty()) {
            throw AgentException.permanent("required input artifact '" + key + "' is missing");
        }
        return v.get();
    }

    /** Optional upstream artifact (e.g. human clarifications). Absence is recorded too. */
    public synchronized Optional<JsonNode> optionalInput(String key) {
        Optional<String> hash = context.hash(key);
        consumed.put(key, hash.orElse(ABSENT));
        return hash.isPresent() ? Optional.of(context.content(key)) : Optional.empty();
    }

    public synchronized Map<String, String> consumed() {
        return Map.copyOf(consumed);
    }

    public String runId() { return runId; }
    public StageSpec stage() { return stage; }
    public int attempt() { return attempt; }
    public Workspace workspace() { return workspace; }
    public Scenario scenario() { return scenario; }
    public Catalog catalog() { return catalog; }
    public PolicyEngine policy() { return policy; }
    public LlmClient llm() { return llm; }
    public Path repoRoot() { return repoRoot; }
    public Path runDir() { return runDir; }

    public StageStatus statusOf(String stageId) {
        return stageStatus.apply(stageId);
    }
}
