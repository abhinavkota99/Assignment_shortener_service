package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.knowledge.Catalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Architecture/design and impact analysis. Produces the contract-level view a reviewer needs:
 * API and data changes (with compatibility notes), the files the plan touches, the ripple set
 * (existing types that reference changed types and therefore need re-verification), the tests that
 * cover them, and the risks. Contract or data changes request a human design review.
 */
public final class DesignAgent implements Agent {

    @Override
    public String name() {
        return "design";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        Catalog catalog = ctx.catalog();
        JsonNode plan = ctx.input("plan");
        JsonNode index = ctx.input("codebase_index");
        ctx.input("requirements"); // lineage: the design is based on this version of the requirements

        Set<String> capabilities = new LinkedHashSet<>();
        Set<String> files = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        plan.path("tasks").forEach(t -> {
            capabilities.add(t.path("capability").asText());
            t.path("files").forEach(f -> files.add(f.asText()));
            t.path("tests").forEach(f -> tests.add(f.asText()));
        });

        ArrayNode api = Json.array();
        ArrayNode data = Json.array();
        ArrayNode risks = Json.array();
        for (String c : capabilities) {
            Catalog.Capability cap = catalog.capability(c);
            cap.api().forEach(api::add);
            cap.data().forEach(data::add);
            cap.risks().forEach(r -> risks.add(cap.id() + ": " + r));
        }

        // ripple: existing types (in the baseline) referencing a type whose file changes
        Set<String> changedTypes = new LinkedHashSet<>();
        Set<String> ripple = new LinkedHashSet<>();
        for (JsonNode t : index.path("types")) {
            if (files.contains(t.path("file").asText())) {
                changedTypes.add(t.path("name").asText());
            }
        }
        for (JsonNode t : index.path("types")) {
            if (changedTypes.contains(t.path("name").asText())) {
                t.path("referencedBy").forEach(r -> {
                    if (!changedTypes.contains(r.asText())) {
                        ripple.add(r.asText());
                    }
                });
            }
        }

        ObjectNode design = Json.object();
        design.set("capabilities", Json.tree(capabilities));
        design.set("apiChanges", api);
        design.set("dataChanges", data);
        design.set("impactedFiles", Json.tree(files));
        design.set("changedExistingTypes", Json.tree(changedTypes));
        design.set("rippleTypes", Json.tree(ripple));
        design.set("plannedTests", Json.tree(tests));
        design.put("verificationStrategy", "compile all sources, run the full unit + integration suite (not only new tests) "
                + "so regressions in ripple types are caught; security scan of the full diff");
        design.set("risks", risks);
        design.put("rollback", "all changes are made in an isolated workspace with a baseline snapshot; "
                + "release is a reviewable patch, applied by a human");

        String summary = files.size() + " files, " + api.size() + " API changes, " + data.size() + " data changes, "
                + ripple.size() + " ripple types";
        AgentResult r = new AgentResult(summary).artifact("design", design);
        if (!api.isEmpty() || !data.isEmpty()) {
            r.checkpoint("design-review", "API contract or stored-data format changes: " + api.size() + " API, " + data.size() + " data");
        }
        if (!changedTypes.isEmpty()) {
            r.decision("Impact limited to " + changedTypes + " with ripple " + ripple,
                    "type-level reference graph from codebase_index", List.of());
        }
        return r;
    }
}
