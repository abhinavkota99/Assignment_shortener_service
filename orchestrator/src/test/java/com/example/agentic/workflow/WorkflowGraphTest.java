package com.example.agentic.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.agentic.engine.Json;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class WorkflowGraphTest {

    private static WorkflowGraph graph(String stagesJson) throws Exception {
        return WorkflowGraph.fromJson(Json.MAPPER.readTree("{\"name\":\"t\",\"stages\":" + stagesJson + "}"));
    }

    @Test
    public void topologicalOrderAndDownstream() throws Exception {
        WorkflowGraph g = graph("[{\"id\":\"a\",\"phase\":\"ANALYSIS\",\"agent\":\"x\"},"
                + "{\"id\":\"b\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"a\"]},"
                + "{\"id\":\"c\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"a\"]},"
                + "{\"id\":\"d\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"b\",\"c\"]}]");
        assertEquals(List.of("a", "b", "c", "d"), g.topologicalOrder());
        assertEquals(Set.of("b", "c", "d"), g.downstreamOf("a"));
        assertEquals(Set.of("d"), g.downstreamOf("c"));
    }

    @Test
    public void rejectsCyclesUnknownDepsAndUnboundedRetries() throws Exception {
        expectFailure("[{\"id\":\"a\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"b\"]},"
                + "{\"id\":\"b\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"a\"]}]", "cycle");
        expectFailure("[{\"id\":\"a\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"dependsOn\":[\"zzz\"]}]", "unknown stage");
        expectFailure("[{\"id\":\"a\",\"phase\":\"ANALYSIS\",\"agent\":\"x\",\"maxAttempts\":50}]", "bounded");
    }

    @Test
    public void fanOutExpandsReExpandsAndKeepsJoinSemantics() throws Exception {
        WorkflowGraph g = graph("[{\"id\":\"plan\",\"phase\":\"PLANNING\",\"agent\":\"x\"},"
                + "{\"id\":\"impl\",\"phase\":\"IMPLEMENTATION\",\"agent\":\"y\",\"fanOut\":\"plan.tasks\",\"dependsOn\":[\"plan\"]},"
                + "{\"id\":\"verify\",\"phase\":\"VERIFICATION\",\"agent\":\"x\",\"dependsOn\":[\"impl\"]}]");
        List<String> ids = g.expand("impl", List.of(
                new WorkflowGraph.TaskNode("A", List.of(), "a"),
                new WorkflowGraph.TaskNode("B", List.of("A"), "b"),
                new WorkflowGraph.TaskNode("C", List.of(), "c")));
        assertEquals(List.of("impl:A", "impl:B", "impl:C"), ids);
        assertEquals(List.of("plan", "impl:A"), g.stage("impl:B").dependsOn());
        assertEquals(ids, g.stage("impl").dependsOn()); // placeholder is now the join barrier
        assertEquals(List.of("plan"), g.stage("impl").baseDependencies());
        assertTrue(g.downstreamOf("impl:A").containsAll(List.of("impl:B", "impl", "verify")));

        // re-plan: old generated stages are replaced
        g.expand("impl", List.of(new WorkflowGraph.TaskNode("Z", List.of(), "z")));
        assertEquals(List.of("impl:Z"), g.generatedBy("impl"));
        assertTrue(!g.contains("impl:A"));
        assertEquals(List.of("impl:Z"), g.stage("impl").dependsOn());
    }

    @Test
    public void cyclicPlanIsRejectedWithoutCorruptingTheGraph() throws Exception {
        WorkflowGraph g = graph("[{\"id\":\"plan\",\"phase\":\"PLANNING\",\"agent\":\"x\"},"
                + "{\"id\":\"impl\",\"phase\":\"IMPLEMENTATION\",\"agent\":\"y\",\"fanOut\":\"plan.tasks\",\"dependsOn\":[\"plan\"]}]");
        g.expand("impl", List.of(new WorkflowGraph.TaskNode("A", List.of(), "a")));
        try {
            g.expand("impl", List.of(new WorkflowGraph.TaskNode("X", List.of("Y"), ""), new WorkflowGraph.TaskNode("Y", List.of("X"), "")));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("cycle"));
        }
        assertEquals(List.of("impl:A"), g.generatedBy("impl"));
        assertTrue(g.contains("impl:A"));
    }

    private static void expectFailure(String json, String fragment) throws Exception {
        try {
            graph(json);
            fail("expected failure containing " + fragment);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
