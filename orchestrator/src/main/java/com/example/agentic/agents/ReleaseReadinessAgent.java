package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.engine.Orchestrator;
import com.example.agentic.engine.StageStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The synchronisation point after the parallel verification branches. Assembles the evidence a
 * release approver needs (check list, requirement-to-test traceability, diff, open risks); the
 * release-criteria exit gate fails the run if any check fails.
 */
public final class ReleaseReadinessAgent implements Agent {

    @Override
    public String name() {
        return "release-readiness";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        JsonNode req = ctx.input("requirements");
        JsonNode plan = ctx.input("plan");
        JsonNode design = ctx.input("design");
        JsonNode tests = ctx.input("test_report");
        JsonNode security = ctx.input("security_report");
        JsonNode docs = ctx.input("docs");
        JsonNode index = ctx.input("codebase_index");

        ObjectNode out = Json.object();
        ArrayNode checks = out.putArray("checks");

        List<String> notDone = new ArrayList<>();
        plan.path("tasks").forEach(t -> {
            if (ctx.statusOf("implement:" + t.path("id").asText()) != StageStatus.SUCCEEDED) {
                notDone.add(t.path("id").asText());
            }
        });
        check(checks, "all planned tasks implemented", notDone.isEmpty(),
                notDone.isEmpty() ? plan.path("tasks").size() + " tasks" : "not done: " + notDone);
        check(checks, "build compiles", tests.path("compiled").asBoolean(), "");
        int run = tests.path("testsRun").asInt();
        check(checks, "all tests pass", run > 0 && tests.path("failures").asInt() == 0, run + " run, " + tests.path("failures").asInt() + " failed");
        int baseline = index.path("testMethods").asInt();
        check(checks, "no test regression", run >= baseline, run + " >= baseline " + baseline);
        check(checks, "no critical security findings", security.path("critical").asInt() == 0,
                security.path("high").asInt() + " high findings, each approved at its task checkpoint");
        check(checks, "documentation generated", docs.path("files").size() > 0, docs.path("files").toString());

        ArrayNode trace = out.putArray("traceability");
        List<String> untraced = new ArrayList<>();
        for (JsonNode c : req.path("capabilities")) {
            String cap = c.path("id").asText();
            ObjectNode row = trace.addObject();
            row.put("capability", cap);
            c.path("requirements").forEach(r -> row.withArray("requirements").add(r.path("id").asText()));
            plan.path("tasks").forEach(t -> {
                boolean delivers = false;
                for (JsonNode c2 : t.path("delivers")) {
                    delivers |= c2.asText().equals(cap);
                }
                if (delivers) {
                    row.withArray("tasks").add(t.path("id").asText());
                    t.path("tests").forEach(x -> row.withArray("tests").add(x.asText()));
                    t.path("tests").forEach(x -> {
                        if (!ctx.workspace().exists(x.asText())) {
                            untraced.add(cap + ": missing test " + x.asText());
                        }
                    });
                }
            });
            plan.path("alreadySatisfied").forEach(s -> {
                if (s.path("capability").asText().equals(cap)) {
                    row.put("status", "existing capability (regression-tested by full suite)");
                }
            });
            plan.path("excluded").forEach(x -> {
                if (x.path("capability").asText().equals(cap)) {
                    row.put("status", "excluded: " + x.path("reason").asText());
                }
            });
            if (!row.has("status")) {
                row.put("status", row.has("tasks") ? "implemented" : "UNTRACED");
                if (!row.has("tasks")) {
                    untraced.add(cap);
                }
            }
        }
        check(checks, "every requirement traced to code and tests", untraced.isEmpty(),
                untraced.isEmpty() ? trace.size() + " capabilities" : untraced.toString());

        List<?> diff = ctx.workspace().diffSummary(Orchestrator.BASELINE);
        out.set("diff", Json.tree(diff));
        out.set("risks", design.path("risks"));
        out.set("assumptions", req.path("assumptions"));
        boolean ready = true;
        for (JsonNode c : checks) {
            ready &= c.path("passed").asBoolean();
        }
        out.put("ready", ready);
        return new AgentResult((ready ? "READY" : "NOT READY") + ": " + checks.size() + " checks, " + diff.size() + " files changed")
                .artifact("release_readiness", out);
    }

    private static void check(ArrayNode checks, String name, boolean passed, String detail) {
        ObjectNode c = checks.addObject();
        c.put("name", name);
        c.put("passed", passed);
        c.put("detail", detail);
    }
}
