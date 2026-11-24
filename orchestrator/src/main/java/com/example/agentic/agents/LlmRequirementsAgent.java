package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.knowledge.Catalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Model-assisted requirement interpretation. The model only classifies the requirement against
 * the catalog (which capabilities, which ambiguity patterns); its answer is treated as untrusted:
 * it must be valid JSON referencing known ids only, otherwise the stage falls back to the
 * deterministic {@link RequirementsAgent}. The structured artifact is always built by code.
 */
public final class LlmRequirementsAgent implements Agent {

    @Override
    public String name() {
        return "requirements-llm";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        if (ctx.llm() == null) {
            throw new AgentException(AgentException.Kind.UNAVAILABLE, "no LLM configured (offline mode)");
        }
        Catalog catalog = ctx.catalog();
        String system = "You classify software requirements against a fixed capability catalog. "
                + "Reply with JSON only: {\"capabilities\":[ids],\"ambiguities\":[ids],\"rationale\":\"...\"}. "
                + "Use only ids from the lists. Flag an ambiguity only if the requirement is genuinely open to the "
                + "interpretations listed for it.";
        StringBuilder user = new StringBuilder("Requirement:\n").append(ctx.scenario().requirement()).append("\n\nCapabilities:\n");
        catalog.capabilities().forEach(c -> user.append("- ").append(c.id()).append(": ").append(c.title()).append('\n'));
        user.append("\nAmbiguity patterns:\n");
        catalog.ambiguities().forEach(a -> user.append("- ").append(a.id()).append(": ").append(a.question()).append(" options=")
                .append(a.options().stream().map(Catalog.Option::label).toList()).append('\n'));

        String reply;
        try {
            reply = ctx.llm().complete(system, user.toString());
        } catch (IOException e) {
            throw new AgentException(AgentException.Kind.TRANSIENT, "LLM call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentException.Kind.TRANSIENT, "LLM call interrupted", e);
        } catch (Exception e) {
            throw new AgentException(AgentException.Kind.PERMANENT, "LLM call failed: " + e.getMessage(), e);
        }
        Proposal p = parse(reply, catalog);
        Map<String, String> matched = new LinkedHashMap<>();
        p.capabilities().forEach(c -> matched.put(c, "model:" + ctx.llm().model()));
        AgentResult r = RequirementsAgent.build(ctx, matched, p.ambiguities(), "llm:" + ctx.llm().model());
        r.decision("Model interpretation accepted after validation", p.rationale(), List.of());
        return r;
    }

    record Proposal(List<String> capabilities, List<String> ambiguities, String rationale) { }

    /** Validates the model's reply. Anything unexpected is a permanent failure (-> deterministic fallback). */
    static Proposal parse(String reply, Catalog catalog) throws AgentException {
        int start = reply == null ? -1 : reply.indexOf('{');
        int end = reply == null ? -1 : reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw AgentException.permanent("LLM reply contained no JSON object");
        }
        JsonNode n;
        try {
            n = Json.MAPPER.readTree(reply.substring(start, end + 1));
        } catch (IOException e) {
            throw AgentException.permanent("LLM reply was not valid JSON: " + e.getMessage());
        }
        Set<String> knownCaps = catalog.capabilities().stream().map(Catalog.Capability::id).collect(Collectors.toSet());
        Set<String> knownAmb = catalog.ambiguities().stream().map(Catalog.AmbiguityRule::id).collect(Collectors.toSet());
        List<String> caps = ids(n.path("capabilities"), knownCaps, "capability");
        List<String> ambs = ids(n.path("ambiguities"), knownAmb, "ambiguity");
        if (caps.isEmpty() && ambs.isEmpty()) {
            throw AgentException.permanent("LLM reply selected nothing");
        }
        return new Proposal(caps, ambs, n.path("rationale").asText(""));
    }

    private static List<String> ids(JsonNode arr, Set<String> known, String what) throws AgentException {
        if (!arr.isArray()) {
            throw AgentException.permanent("LLM reply field for " + what + " ids is not an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode x : arr) {
            if (!known.contains(x.asText())) {
                throw AgentException.permanent("LLM proposed unknown " + what + " id '" + x.asText() + "' (rejected)");
            }
            if (!out.contains(x.asText())) {
                out.add(x.asText());
            }
        }
        return out;
    }
}
