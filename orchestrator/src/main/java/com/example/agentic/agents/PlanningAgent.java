package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.governance.PolicyFinding;
import com.example.agentic.knowledge.Catalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decomposes the normalised requirements into implementation tasks with explicit dependencies.
 *
 * <ul>
 *   <li>capabilities the code already provides are reused, not rebuilt (brownfield)</li>
 *   <li>capabilities a human rejected are excluded and the exclusion is recorded (re-planning)</li>
 *   <li>tasks pull in their prerequisite tasks; tasks that touch the same file are serialised so
 *       parallel execution can never race on a file</li>
 *   <li>the plan is grouped into waves (tasks in a wave are independent and run in parallel)</li>
 *   <li>plan-level policy runs here, so compliance issues surface before any code is written</li>
 * </ul>
 */
public final class PlanningAgent implements Agent {

    private static final Pattern TASK_REF = Pattern.compile("plan task (T-[\\w-]+)");

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        Catalog catalog = ctx.catalog();
        JsonNode req = ctx.input("requirements");
        JsonNode index = ctx.input("codebase_index");
        JsonNode constraints = ctx.optionalInput("human.constraints").orElse(Json.object());

        Set<String> present = new HashSet<>();
        index.path("presentCapabilities").forEach(c -> present.add(c.asText()));
        Map<String, String> excludedBy = new LinkedHashMap<>();
        for (JsonNode r : constraints.path("rejections")) {
            if (!"plan".equals(r.path("stage").asText())) {
                continue;
            }
            var m = TASK_REF.matcher(r.path("subjects").asText());
            while (m.find()) {
                excludedBy.put(catalog.task(m.group(1)).capability(),
                        r.path("checkpoint").asText() + " rejected by " + r.path("approver").asText() + ": " + r.path("comment").asText());
            }
        }

        AgentResult notes = new AgentResult("decisions collected while planning");
        ObjectNode plan = Json.object();
        ArrayNode satisfied = plan.putArray("alreadySatisfied");
        ArrayNode excluded = plan.putArray("excluded");
        Set<String> taskIds = new LinkedHashSet<>();
        Map<String, Set<String>> delivers = new LinkedHashMap<>(); // task -> requested capabilities it contributes to
        for (JsonNode c : req.path("capabilities")) {
            String cap = c.path("id").asText();
            if (present.contains(cap)) {
                ObjectNode s = satisfied.addObject();
                s.put("capability", cap);
                s.put("evidence", "existing code provides it (codebase_index)");
                notes.decision("Reuse existing capability '" + cap + "'", "codebase analysis found its evidence; rebuilding would add risk", List.of("re-implement"));
            } else if (excludedBy.containsKey(cap)) {
                ObjectNode x = excluded.addObject();
                x.put("capability", cap);
                x.put("reason", excludedBy.get(cap));
                notes.decision("Excluded capability '" + cap + "' from the plan", excludedBy.get(cap), List.of("keep and escalate"));
            } else {
                for (String t : catalog.capability(cap).tasks()) {
                    addWithPrerequisites(catalog, t, present, taskIds);
                    delivers.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(cap);
                }
            }
        }

        // order tasks: catalog order is a valid topological order of declared dependencies
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (String t : taskIds) {
            Set<String> d = new LinkedHashSet<>();
            for (String dep : catalog.task(t).dependsOn()) {
                if (taskIds.contains(dep)) {
                    d.add(dep);
                }
            }
            deps.put(t, d);
        }
        serialiseSharedFiles(catalog, deps, notes);

        ArrayNode tasks = plan.putArray("tasks");
        List<String> noRecipe = new ArrayList<>();
        for (var e : deps.entrySet()) {
            Catalog.Task t = catalog.task(e.getKey());
            ObjectNode n = tasks.addObject();
            n.put("id", t.id());
            n.put("title", t.title());
            n.put("capability", t.capability());
            Set<String> d = new LinkedHashSet<>(List.of(t.capability()));
            d.addAll(delivers.getOrDefault(t.id(), Set.of()));
            n.set("delivers", Json.tree(d));
            n.set("dependsOn", Json.tree(e.getValue()));
            n.set("files", Json.tree(t.files()));
            n.set("tests", Json.tree(t.tests()));
            n.set("tags", Json.tree(t.tags()));
            n.set("recipe", Json.tree(t.recipe()));
            if ("none".equals(t.recipe().type())) {
                noRecipe.add(t.id());
            }
        }
        List<List<String>> waves = waves(deps);
        plan.set("waves", Json.tree(waves));
        plan.put("criticalPathLength", waves.size());
        plan.set("noApprovedRecipe", Json.tree(noRecipe));

        List<PolicyFinding> findings = ctx.policy().evaluatePlan(plan);
        String summary = taskIds.size() + " tasks in " + waves.size() + " waves; reused " + satisfied.size()
                + ", excluded " + excluded.size() + (findings.isEmpty() ? "" : "; " + findings.size() + " policy findings");
        AgentResult out = new AgentResult(summary).artifact("plan", plan).findings(findings);
        notes.decisions().forEach(d -> out.decision(d.summary(), d.rationale(), d.alternatives()));
        out.decision("Decomposed into " + taskIds.size() + " tasks / " + waves.size() + " parallel waves", "waves=" + waves,
                List.of("single sequential change"));
        if (!noRecipe.isEmpty()) {
            out.decision("Tasks without an approved recipe: " + noRecipe,
                    "they cannot be executed autonomously in offline mode and will fail at implementation unless excluded", List.of());
        }
        return out;
    }

    /** Adds a task after its prerequisites; prerequisites of capabilities the code already has are skipped. */
    private static void addWithPrerequisites(Catalog catalog, String taskId, Set<String> present, Set<String> out) {
        if (out.contains(taskId)) {
            return;
        }
        for (String d : catalog.task(taskId).dependsOn()) {
            if (!present.contains(catalog.task(d).capability())) {
                addWithPrerequisites(catalog, d, present, out);
            }
        }
        out.add(taskId);
    }

    /** If two tasks own the same file and neither depends on the other, the later one waits. */
    private static void serialiseSharedFiles(Catalog catalog, Map<String, Set<String>> deps, AgentResult notes) {
        List<String> order = new ArrayList<>(deps.keySet());
        for (int i = 0; i < order.size(); i++) {
            for (int j = i + 1; j < order.size(); j++) {
                String a = order.get(i);
                String b = order.get(j);
                Set<String> shared = new HashSet<>(catalog.task(a).files());
                shared.retainAll(catalog.task(b).files());
                if (!shared.isEmpty() && !reaches(deps, b, a) && !reaches(deps, a, b)) {
                    deps.get(b).add(a);
                    notes.decision("Serialised " + b + " after " + a, "both modify " + shared + "; parallel edits could conflict",
                            List.of("run in parallel"));
                }
            }
        }
    }

    private static boolean reaches(Map<String, Set<String>> deps, String from, String to) {
        for (String d : deps.getOrDefault(from, Set.of())) {
            if (d.equals(to) || reaches(deps, d, to)) {
                return true;
            }
        }
        return false;
    }

    /** Groups tasks into dependency levels: every task in wave n depends only on waves < n. */
    static List<List<String>> waves(Map<String, Set<String>> deps) {
        Map<String, Integer> level = new LinkedHashMap<>();
        for (String t : deps.keySet()) {
            levelOf(t, deps, level);
        }
        List<List<String>> waves = new ArrayList<>();
        level.forEach((t, l) -> {
            while (waves.size() <= l) {
                waves.add(new ArrayList<>());
            }
            waves.get(l).add(t);
        });
        return waves;
    }

    private static int levelOf(String t, Map<String, Set<String>> deps, Map<String, Integer> memo) {
        Integer known = memo.get(t);
        if (known != null) {
            return known;
        }
        int l = 0;
        for (String d : deps.getOrDefault(t, Set.of())) {
            l = Math.max(l, levelOf(d, deps, memo) + 1);
        }
        memo.put(t, l);
        return l;
    }
}
