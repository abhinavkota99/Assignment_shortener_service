package com.example.agentic.workflow;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SDLC dependency graph (a DAG). Parallelism is implicit: every stage whose dependencies have
 * succeeded is runnable, so independent branches run concurrently and a stage with several
 * dependencies is a synchronisation (join) point.
 *
 * <p>The graph is mutable in one controlled way: a fan-out placeholder can be (re)expanded from the
 * current plan, which is how the orchestrator re-plans when an upstream artifact changes.
 */
public final class WorkflowGraph {

    /** A generated node request: plan task id, the task ids it depends on, and a description. */
    public record TaskNode(String taskId, List<String> dependsOnTasks, String description) { }

    private final String name;
    private final Map<String, StageSpec> stages = new LinkedHashMap<>();
    private final Map<String, List<String>> generated = new HashMap<>();

    private WorkflowGraph(String name, Collection<StageSpec> specs) {
        this.name = name;
        for (StageSpec s : specs) {
            s.validate();
            if (stages.put(s.id(), s) != null) {
                throw new IllegalArgumentException("duplicate stage id: " + s.id());
            }
        }
        validateGraph();
    }

    public static WorkflowGraph fromJson(JsonNode root) {
        List<StageSpec> specs = new ArrayList<>();
        for (JsonNode n : root.withArray("stages")) {
            specs.add(Json.convert(n, StageSpec.class));
        }
        return new WorkflowGraph(root.path("name").asText("workflow"), specs);
    }

    public static WorkflowGraph fromResource(String resource) {
        try (InputStream in = WorkflowGraph.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("workflow not found: " + resource);
            }
            return fromJson(Json.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String name() {
        return name;
    }

    public StageSpec stage(String id) {
        StageSpec s = stages.get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown stage: " + id);
        }
        return s;
    }

    public boolean contains(String id) {
        return stages.containsKey(id);
    }

    public Collection<StageSpec> stages() {
        return List.copyOf(stages.values());
    }

    /** Stage ids in a valid execution order (Kahn's algorithm; ties keep definition order). */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (StageSpec s : stages.values()) {
            indegree.putIfAbsent(s.id(), 0);
            for (String d : s.dependsOn()) {
                indegree.merge(s.id(), 1, Integer::sum);
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(s.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dep : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(dep, -1, Integer::sum) == 0) {
                    ready.add(dep);
                }
            }
        }
        if (order.size() != stages.size()) {
            Set<String> cyclic = new LinkedHashSet<>(stages.keySet());
            order.forEach(cyclic::remove);
            throw new IllegalStateException("workflow has a dependency cycle among: " + cyclic);
        }
        return order;
    }

    /** All stages that transitively depend on {@code id} (not including it). */
    public Set<String> downstreamOf(String id) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            String cur = todo.poll();
            for (StageSpec s : stages.values()) {
                if (s.dependsOn().contains(cur) && out.add(s.id())) {
                    todo.add(s.id());
                }
            }
        }
        return out;
    }

    /**
     * Expands (or re-expands) a fan-out placeholder into one stage per task. Generated stages depend on
     * the placeholder's original dependencies plus their task dependencies; the placeholder becomes the
     * join barrier that downstream stages already depend on. Returns the generated stage ids.
     */
    public List<String> expand(String placeholderId, List<TaskNode> tasks) {
        StageSpec placeholder = stage(placeholderId);
        if (!placeholder.isFanOut()) {
            throw new IllegalArgumentException(placeholderId + " is not a fan-out stage");
        }
        checkTasksAcyclic(tasks); // validate before mutating so a bad plan cannot corrupt the graph
        List<String> previous = generated.getOrDefault(placeholderId, List.of());
        previous.forEach(stages::remove);

        Map<String, String> idByTask = new LinkedHashMap<>();
        for (TaskNode t : tasks) {
            idByTask.put(t.taskId(), placeholderId + ":" + t.taskId());
        }
        List<String> baseDeps = placeholder.fanOutDependsOn();
        List<String> ids = new ArrayList<>();
        for (TaskNode t : tasks) {
            List<String> deps = new ArrayList<>(baseDeps);
            for (String dt : t.dependsOnTasks()) {
                String depId = idByTask.get(dt);
                if (depId == null) {
                    throw new IllegalArgumentException("task " + t.taskId() + " depends on unknown task " + dt);
                }
                deps.add(depId);
            }
            String id = idByTask.get(t.taskId());
            if (stages.containsKey(id)) {
                throw new IllegalArgumentException("generated stage id collides with existing stage: " + id);
            }
            stages.put(id, StageSpec.expandedTask(placeholder, id, t.taskId(), deps, t.description()));
            ids.add(id);
        }
        placeholder.becomeJoinOver(ids);
        generated.put(placeholderId, List.copyOf(ids));
        validateGraph();
        return ids;
    }

    public List<String> generatedBy(String placeholderId) {
        return generated.getOrDefault(placeholderId, List.of());
    }

    static void checkTasksAcyclic(List<TaskNode> tasks) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (TaskNode t : tasks) {
            if (deps.put(t.taskId(), t.dependsOnTasks()) != null) {
                throw new IllegalArgumentException("duplicate task id in plan: " + t.taskId());
            }
        }
        Map<String, Integer> state = new HashMap<>(); // 1 = visiting, 2 = done
        for (String id : deps.keySet()) {
            visit(id, deps, state, new ArrayDeque<>());
        }
    }

    private static void visit(String id, Map<String, List<String>> deps, Map<String, Integer> state, Deque<String> path) {
        Integer s = state.get(id);
        if (s != null && s == 2) {
            return;
        }
        path.push(id);
        if (s != null) {
            throw new IllegalArgumentException("plan has a task dependency cycle: " + path);
        }
        state.put(id, 1);
        for (String d : deps.getOrDefault(id, List.of())) {
            if (!deps.containsKey(d)) {
                throw new IllegalArgumentException("task " + id + " depends on unknown task " + d);
            }
            visit(d, deps, state, path);
        }
        state.put(id, 2);
        path.pop();
    }

    private void validateGraph() {
        for (StageSpec s : stages.values()) {
            for (String d : s.dependsOn()) {
                if (!stages.containsKey(d)) {
                    throw new IllegalArgumentException("stage " + s.id() + " depends on unknown stage " + d);
                }
            }
        }
        topologicalOrder(); // throws on cycles
    }
}
