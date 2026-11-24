package com.example.agentic.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.agentic.agents.AgentContext;
import com.example.agentic.agents.AgentException;
import com.example.agentic.agents.AgentRegistry;
import com.example.agentic.agents.AgentRegistry.SimpleAgent;
import com.example.agentic.agents.AgentResult;
import com.example.agentic.governance.Approval;
import com.example.agentic.governance.FileApprovalGateway;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.PolicyFinding;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.observability.AuditLog;
import com.example.agentic.workspace.FileOp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Engine behaviour with stub agents: control flow, recovery and governance, independent of the SDLC agents. */
public class OrchestratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private Path runs;
    private Scenario scenario;
    private Catalog catalog;
    /** Per-test behaviour overrides for the stub agents. */
    private final Map<String, SimpleAgent.Body> behaviour = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        root = tmp.getRoot().toPath();
        runs = root.resolve("runs");
        Path scen = root.resolve("scenario");
        Files.createDirectories(scen);
        Files.writeString(scen.resolve("scenario.json"), "{\"id\":\"t\",\"title\":\"t\",\"type\":\"greenfield\","
                + "\"requirement\":\"test\",\"workflow\":\"/workflows/test-engine.json\"}");
        scenario = Scenario.load(scen);
        Path cat = root.resolve("catalog.json");
        Files.writeString(cat, "{\"capabilities\":[],\"tasks\":[],\"ambiguities\":[],\"qualifiers\":[]}");
        catalog = Catalog.load(cat);
    }

    private AgentRegistry agents() {
        AgentRegistry r = new AgentRegistry();
        for (String name : List.of("start", "start-fallback", "left", "right", "change", "verify")) {
            r.register(new SimpleAgent(name, ctx -> behaviour.getOrDefault(name, OrchestratorTest::defaultBehaviour).run(ctx)));
        }
        return r;
    }

    private static AgentResult defaultBehaviour(AgentContext ctx) throws AgentException {
        String id = ctx.stage().id();
        switch (id) {
            case "start" -> {
                return new AgentResult("start").artifact("s", Json.object().put("answers",
                        ctx.optionalInput(Orchestrator.CLARIFICATIONS).map(n -> n.path("answers").toString()).orElse("none")));
            }
            case "left", "right" -> {
                ctx.input("s");
                return new AgentResult(id).artifact(id, Json.object().put("ok", true));
            }
            case "change" -> {
                ctx.input("left");
                ctx.input("right");
                List<String> changed = ctx.workspace().apply(id, List.of(FileOp.create("src/main/java/Out.java", "class Out {}")));
                return new AgentResult("changed").artifact("impl", Json.object().put("n", changed.size())).changed(changed);
            }
            default -> {
                ctx.input("impl");
                return new AgentResult("verified").artifact("verdict", Json.object().put("ok", true));
            }
        }
    }

    private Orchestrator orchestrator(Approval.Gateway gateway, FaultInjector faults) {
        Orchestrator.Options opts = new Orchestrator.Options(4, 50, TimeUnit.MINUTES.toMillis(5), 3, 50, 5);
        return new Orchestrator(root, runs, catalog, agents(), new PolicyEngine(), gateway, null, faults, opts,
                System::currentTimeMillis, null);
    }

    private Orchestrator orchestrator() {
        return orchestrator(new FileApprovalGateway(null), FaultInjector.none());
    }

    private Path approvals(String json) throws Exception {
        Path f = Files.createTempFile(root, "approvals", ".json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    public void independentBranchesRunInParallelAndJoin() {
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        SimpleAgent.Body rendezvous = ctx -> {
            try {
                bothRunning.await(5, TimeUnit.SECONDS); // only succeeds if left and right run concurrently
            } catch (Exception e) {
                throw AgentException.permanent("branches did not run in parallel: " + e);
            }
            return defaultBehaviour(ctx);
        };
        behaviour.put("left", rendezvous);
        behaviour.put("right", rendezvous);
        RunState st = orchestrator().start(scenario, "par");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertEquals(Map.of("left", st.stage("change").consumed().get("left"), "right", st.stage("change").consumed().get("right")),
                st.stage("change").consumed());
        assertTrue(AuditLog.verify(runs.resolve("par/audit.jsonl")).valid());
        assertTrue(Files.exists(runs.resolve("par/REPORT.md")));
    }

    @Test
    public void transientFailuresAreRetriedWithBackoffThenRecover() {
        behaviour.put("change", ctx -> {
            if (calls.incrementAndGet() <= 2) {
                throw AgentException.transientFailure("flaky dependency");
            }
            return defaultBehaviour(ctx);
        });
        RunState st = orchestrator().start(scenario, "retry");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertEquals(3, st.stage("change").attempts());
        assertEquals(2, st.counter("retries"));
        assertNotNull("MTTR is measured", st.stage("change").recoveryMillis());
    }

    @Test
    public void retriesAreBoundedThenTheRunFailsAndRollsBack() {
        behaviour.put("change", ctx -> {
            ctx.workspace().apply("change", List.of(FileOp.create("src/main/java/Partial.java", "class Partial {}")));
            throw AgentException.transientFailure("always failing");
        });
        RunState st = orchestrator().start(scenario, "bounded");
        assertEquals(RunStatus.FAILED, st.status());
        assertEquals(3, st.stage("change").attempts());
        assertEquals(StageStatus.SKIPPED, st.stage("verify").status());
        assertFalse("partial change undone", Files.exists(runs.resolve("bounded/workspace/src/main/java/Partial.java")));
    }

    @Test
    public void permanentFailureUsesFallbackAgent() {
        behaviour.put("start", ctx -> {
            throw AgentException.permanent("primary broken");
        });
        behaviour.put("start-fallback", ctx -> new AgentResult("fallback").artifact("s", Json.object().put("by", "fallback")));
        RunState st = orchestrator().start(scenario, "fallback");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertTrue(st.stage("start").usedFallback());
        assertEquals(1, st.counter("fallbacks"));
    }

    @Test
    public void downstreamFailureRollsBackCommittedWorkspaceChanges() {
        behaviour.put("verify", ctx -> {
            throw AgentException.permanent("verification failed");
        });
        RunState st = orchestrator().start(scenario, "rollback");
        assertEquals(RunStatus.FAILED, st.status());
        assertEquals(StageStatus.ROLLED_BACK, st.stage("change").status());
        assertEquals(1, st.counter("rollbacks"));
        assertFalse(Files.exists(runs.resolve("rollback/workspace/src/main/java/Out.java")));
    }

    @Test
    public void criticalPolicyFindingSafeStopsTheRun() {
        behaviour.put("change", ctx -> defaultBehaviour(ctx).finding(new PolicyFinding("SEC-001-hardcoded-secret",
                PolicyFinding.Severity.CRITICAL, null, "Out.java:1", "secret")));
        RunState st = orchestrator().start(scenario, "critical");
        assertEquals(RunStatus.SAFE_STOPPED, st.status());
        assertTrue(st.stopReason().contains("critical policy violation"));
        assertFalse(Files.exists(runs.resolve("critical/workspace/src/main/java/Out.java")));
        assertEquals(StageStatus.SKIPPED, st.stage("verify").status());
    }

    @Test
    public void highFindingPausesForApprovalAndResumes() throws Exception {
        behaviour.put("change", ctx -> defaultBehaviour(ctx).finding(new PolicyFinding("SEC-002-command-execution",
                PolicyFinding.Severity.HIGH, "security-review", "Out.java:1", "spawns processes")));
        RunState paused = orchestrator().start(scenario, "approve");
        assertEquals(RunStatus.AWAITING_APPROVAL, paused.status());
        assertEquals(StageStatus.PENDING, paused.stage("verify").status());
        assertTrue(Files.exists(runs.resolve("approve/APPROVALS-PENDING.md")));
        assertTrue("change stays applied while awaiting review", Files.exists(runs.resolve("approve/workspace/src/main/java/Out.java")));

        Path decisions = approvals("{\"decisions\":[{\"checkpoint\":\"change/security-review\",\"decision\":\"approve\","
                + "\"approver\":\"sec.lead\",\"comment\":\"needed for build tooling\"}]}");
        RunState done = orchestrator(new FileApprovalGateway(decisions), FaultInjector.none()).resume("approve", null);
        assertEquals(RunStatus.SUCCEEDED, done.status());
        assertTrue(done.decisions().stream().anyMatch(d -> d.actor().equals("human:sec.lead")));
        assertEquals(1, done.resumes());
    }

    @Test
    public void rejectionOfANonAdaptableStageRejectsTheRunAndRevertsIt() throws Exception {
        behaviour.put("change", ctx -> defaultBehaviour(ctx).finding(new PolicyFinding("CMP-001-personal-data",
                PolicyFinding.Severity.HIGH, "privacy-review", "Out.java:1", "stores IPs")));
        Path decisions = approvals("{\"decisions\":[{\"checkpoint\":\"*/privacy-review\",\"decision\":\"reject\","
                + "\"approver\":\"dpo\",\"comment\":\"no IP storage\"}]}");
        RunState st = orchestrator(new FileApprovalGateway(decisions), FaultInjector.none()).start(scenario, "reject");
        assertEquals(RunStatus.REJECTED, st.status());
        assertFalse(Files.exists(runs.resolve("reject/workspace/src/main/java/Out.java")));
    }

    @Test
    public void changedUpstreamInputInvalidatesAndReExecutesDownstream() throws Exception {
        behaviour.put("change", ctx -> defaultBehaviour(ctx).checkpoint("design-review", "contract change"));
        RunState paused = orchestrator().start(scenario, "replan");
        assertEquals(RunStatus.AWAITING_APPROVAL, paused.status());
        String before = paused.stage("left").consumed().get("s");

        // A human supplies new input: everything that depended on the old version must be redone.
        Path answers = Files.createTempFile(root, "answers", ".json");
        Files.writeString(answers, "{\"answeredBy\":\"po\",\"answers\":{\"X\":\"y\"}}");
        Path decisions = approvals("{\"decisions\":[{\"checkpoint\":\"change/design-review\",\"decision\":\"approve\",\"approver\":\"lead\"}]}");
        RunState done = orchestrator(new FileApprovalGateway(decisions), FaultInjector.none()).resume("replan", answers);
        assertEquals(RunStatus.SUCCEEDED, done.status());
        assertTrue(done.stage("start").reExecutions() >= 1);
        assertTrue(done.stage("left").reExecutions() >= 1);
        assertTrue(done.stage("change").reExecutions() >= 1);
        assertFalse(before.equals(done.stage("left").consumed().get("s")));
        assertTrue("mutated workspace was reset before re-execution", done.counter("rollbacks") >= 1);
    }

    @Test
    public void crashedRunIsRecoveredFromPersistedUndoLogs() throws Exception {
        // Simulate a process that died mid-way through 'change': state says RUNNING, a partial write exists.
        behaviour.put("change", ctx -> defaultBehaviour(ctx).checkpoint("design-review", "pause here"));
        orchestrator().start(scenario, "crash");
        Path runDir = runs.resolve("crash");
        com.fasterxml.jackson.databind.node.ObjectNode state = (com.fasterxml.jackson.databind.node.ObjectNode) Json.read(runDir.resolve("state.json"));
        state.put("status", "RUNNING");
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("stages").path("change")).put("status", "RUNNING");
        Files.writeString(runDir.resolve("state.json"), Json.pretty(state));
        Files.writeString(runDir.resolve("undo/change.json"), "{\"src/main/java/Half.java\":null}");
        Files.writeString(runDir.resolve("workspace/src/main/java/Half.java"), "class Half {");

        try {
            orchestrator().resume("crash", null);
            org.junit.Assert.fail("a RUNNING run must not be resumed without --recover");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("--recover"));
        }
        Path decisions = approvals("{\"decisions\":[{\"checkpoint\":\"change/design-review\",\"decision\":\"approve\",\"approver\":\"lead\"}]}");
        RunState done = orchestrator(new FileApprovalGateway(decisions), FaultInjector.none()).resume("crash", null, true);
        assertEquals(RunStatus.SUCCEEDED, done.status());
        assertFalse("interrupted partial write undone", Files.exists(runDir.resolve("workspace/src/main/java/Half.java")));
    }

    @Test
    public void killSwitchStopsSafely() {
        behaviour.put("left", ctx -> {
            try {
                Files.writeString(ctx.runDir().resolve("STOP"), "operator");
            } catch (java.io.IOException e) {
                throw AgentException.permanent(e.toString());
            }
            return defaultBehaviour(ctx);
        });
        RunState st = orchestrator().start(scenario, "kill");
        assertEquals(RunStatus.SAFE_STOPPED, st.status());
        assertTrue(st.stopReason().contains("kill switch"));
        assertEquals(StageStatus.SKIPPED, st.stage("verify").status());
    }

    @Test
    public void stagesWithoutRetryBudgetFailOnTransientErrors() {
        RunState st = orchestrator(new FileApprovalGateway(null), new FaultInjector(List.of("left:transient:1"))).start(scenario, "noretry");
        assertEquals(RunStatus.FAILED, st.status());
        assertEquals(1, st.stage("left").attempts());
    }

    @Test
    public void injectedTransientFaultsAreRecoveredByRetry() {
        RunState st = orchestrator(new FileApprovalGateway(null), new FaultInjector(List.of("change:transient:1"))).start(scenario, "inject");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertEquals(2, st.stage("change").attempts());
    }
}
