package com.example.agentic.agents;

import com.example.agentic.governance.PolicyFinding;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an agent proposes. Nothing here takes effect until the orchestrator accepts it. */
public final class AgentResult {

    /** A decision the agent made, for the lineage ledger. */
    public record DecisionDraft(String summary, String rationale, List<String> alternatives) { }

    /** A human checkpoint the agent asks for (e.g. "design-review" because the API contract changes). */
    public record CheckpointRequest(String checkpoint, String reason) { }

    private final String summary;
    private final Map<String, JsonNode> artifacts = new LinkedHashMap<>();
    private final List<DecisionDraft> decisions = new ArrayList<>();
    private final List<PolicyFinding> findings = new ArrayList<>();
    private final List<CheckpointRequest> checkpoints = new ArrayList<>();
    private final List<String> changedFiles = new ArrayList<>();

    public AgentResult(String summary) {
        this.summary = summary;
    }

    public AgentResult artifact(String key, JsonNode content) {
        artifacts.put(key, content);
        return this;
    }

    public AgentResult decision(String summary, String rationale, List<String> alternatives) {
        decisions.add(new DecisionDraft(summary, rationale, alternatives));
        return this;
    }

    public AgentResult finding(PolicyFinding f) {
        findings.add(f);
        return this;
    }

    public AgentResult findings(List<PolicyFinding> fs) {
        findings.addAll(fs);
        return this;
    }

    public AgentResult checkpoint(String checkpoint, String reason) {
        checkpoints.add(new CheckpointRequest(checkpoint, reason));
        return this;
    }

    public AgentResult changed(List<String> files) {
        changedFiles.addAll(files);
        return this;
    }

    public String summary() {
        return summary;
    }

    public Map<String, JsonNode> artifacts() {
        return Map.copyOf(artifacts);
    }

    public List<DecisionDraft> decisions() {
        return List.copyOf(decisions);
    }

    public List<PolicyFinding> findings() {
        return List.copyOf(findings);
    }

    public List<CheckpointRequest> checkpoints() {
        return List.copyOf(checkpoints);
    }

    public List<String> changedFiles() {
        return List.copyOf(changedFiles);
    }
}
