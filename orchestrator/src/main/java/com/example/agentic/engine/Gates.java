package com.example.agentic.engine;

import com.example.agentic.agents.AgentResult;
import com.example.agentic.context.ContextStore;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named entry and exit gates referenced from the workflow definition. A gate is a deterministic
 * predicate over artifacts; it cannot be overridden by an agent.
 *
 * <ul>
 *   <li>entry gates run before a stage starts (preconditions)</li>
 *   <li>exit gates run on a stage's proposed output before it is accepted (acceptance criteria)</li>
 * </ul>
 */
public final class Gates {

    public enum Verdict { PASS, FAIL, NEEDS_INPUT }

    public record Result(String gate, Verdict verdict, String detail) {
        @Override
        public String toString() {
            return gate + "=" + verdict + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    /** What a gate can see. {@code proposed} is null for entry gates. */
    public record Input(String stageId, AgentResult proposed, ContextStore context, Workspace workspace) {

        JsonNode artifact(String key) {
            if (proposed != null && proposed.artifacts().containsKey(key)) {
                return proposed.artifacts().get(key);
            }
            return context.get(key).map(a -> a.content()).orElse(null);
        }
    }

    @FunctionalInterface
    interface Gate {
        Result evaluate(String name, Input in);
    }

    private static final Map<String, Gate> GATES = Map.ofEntries(
            Map.entry("requirements-unambiguous", Gates::requirementsUnambiguous),
            Map.entry("requirements-testable", Gates::requirementsTestable),
            Map.entry("plan-traceable", Gates::planTraceable),
            Map.entry("impact-assessed", (n, in) -> present(n, in, "design", "impactedFiles")),
            Map.entry("baseline-checkpointed", (n, in) -> in.workspace().hasSnapshot(Orchestrator.BASELINE)
                    ? pass(n, "") : fail(n, "no baseline snapshot to roll back to")),
            Map.entry("change-applied", Gates::changeApplied),
            Map.entry("tests-green", Gates::testsGreen),
            Map.entry("no-test-regression", Gates::noTestRegression),
            Map.entry("no-critical-findings", Gates::noCriticalFindings),
            Map.entry("docs-generated", (n, in) -> present(n, in, "docs", "files")),
            Map.entry("release-criteria-met", Gates::releaseCriteria));

    private Gates() {
    }

    public static void requireKnown(List<String> names) {
        for (String n : names) {
            if (!GATES.containsKey(n)) {
                throw new IllegalArgumentException("unknown gate: " + n);
            }
        }
    }

    public static List<Result> evaluate(List<String> names, Input in) {
        List<Result> out = new ArrayList<>();
        for (String n : names) {
            out.add(GATES.get(n).evaluate(n, in));
        }
        return out;
    }

    private static Result requirementsUnambiguous(String n, Input in) {
        JsonNode req = in.artifact("requirements");
        if (req == null) {
            return fail(n, "no requirements artifact");
        }
        List<String> open = new ArrayList<>();
        for (JsonNode a : req.path("ambiguities")) {
            if (a.path("blocking").asBoolean() && a.path("resolution").isMissingNode()) {
                open.add(a.path("id").asText());
            }
        }
        return open.isEmpty() ? pass(n, "") : new Result(n, Verdict.NEEDS_INPUT, "unresolved blocking ambiguities: " + open);
    }

    private static Result requirementsTestable(String n, Input in) {
        JsonNode req = in.artifact("requirements");
        int fr = 0;
        for (JsonNode c : req.path("capabilities")) {
            fr += c.path("requirements").size();
            if (c.path("acceptance").isEmpty()) {
                return fail(n, "capability " + c.path("id").asText() + " has no acceptance criteria");
            }
        }
        return fr == 0 ? fail(n, "no functional requirements identified") : pass(n, fr + " functional requirements");
    }

    private static Result planTraceable(String n, Input in) {
        JsonNode req = in.artifact("requirements");
        JsonNode plan = in.artifact("plan");
        Set<String> covered = new HashSet<>();
        plan.path("tasks").forEach(t -> t.path("delivers").forEach(c -> covered.add(c.asText())));
        plan.path("alreadySatisfied").forEach(c -> covered.add(c.path("capability").asText()));
        plan.path("excluded").forEach(c -> covered.add(c.path("capability").asText()));
        List<String> missing = new ArrayList<>();
        req.path("capabilities").forEach(c -> {
            if (!covered.contains(c.path("id").asText())) {
                missing.add(c.path("id").asText());
            }
        });
        return missing.isEmpty() ? pass(n, "every capability traced to tasks, existing code or a recorded exclusion")
                : fail(n, "capabilities without a plan: " + missing);
    }

    private static Result changeApplied(String n, Input in) {
        if (in.proposed() == null) {
            return fail(n, "no proposal");
        }
        return pass(n, in.proposed().changedFiles().size() + " files changed");
    }

    private static Result testsGreen(String n, Input in) {
        JsonNode r = in.artifact("test_report");
        if (r == null) {
            return fail(n, "no test report");
        }
        if (!r.path("compiled").asBoolean()) {
            return fail(n, "compilation failed");
        }
        int run = r.path("testsRun").asInt();
        int failed = r.path("failures").asInt();
        return run > 0 && failed == 0 ? pass(n, run + " tests passed") : fail(n, failed + " of " + run + " tests failed");
    }

    private static Result noTestRegression(String n, Input in) {
        JsonNode r = in.artifact("test_report");
        JsonNode idx = in.artifact("codebase_index");
        int baseline = idx == null ? 0 : idx.path("testMethods").asInt();
        int run = r == null ? 0 : r.path("testsRun").asInt();
        return run >= baseline ? pass(n, run + " tests run, baseline " + baseline)
                : fail(n, "fewer tests than baseline (" + run + " < " + baseline + "): tests were removed or skipped");
    }

    private static Result noCriticalFindings(String n, Input in) {
        JsonNode r = in.artifact("security_report");
        if (r == null) {
            return fail(n, "no security report");
        }
        int critical = r.path("critical").asInt();
        return critical == 0 ? pass(n, r.path("high").asInt() + " high (approval-gated), 0 critical")
                : fail(n, critical + " critical findings");
    }

    private static Result releaseCriteria(String n, Input in) {
        JsonNode r = in.artifact("release_readiness");
        if (r == null) {
            return fail(n, "no readiness report");
        }
        List<String> failed = new ArrayList<>();
        r.path("checks").forEach(c -> {
            if (!c.path("passed").asBoolean()) {
                failed.add(c.path("name").asText());
            }
        });
        return failed.isEmpty() ? pass(n, r.path("checks").size() + " checks passed") : fail(n, "failed checks: " + failed);
    }

    private static Result present(String n, Input in, String artifact, String field) {
        JsonNode a = in.artifact(artifact);
        return a != null && a.has(field) ? pass(n, "") : fail(n, artifact + "." + field + " missing");
    }

    private static Result pass(String n, String detail) {
        return new Result(n, Verdict.PASS, detail);
    }

    private static Result fail(String n, String detail) {
        return new Result(n, Verdict.FAIL, detail);
    }
}
