package com.example.agentic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.agentic.agents.AgentRegistry;
import com.example.agentic.context.Decision;
import com.example.agentic.engine.FaultInjector;
import com.example.agentic.engine.Json;
import com.example.agentic.engine.Orchestrator;
import com.example.agentic.engine.RunState;
import com.example.agentic.engine.RunStatus;
import com.example.agentic.engine.StageStatus;
import com.example.agentic.governance.FileApprovalGateway;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.llm.LlmClient;
import com.example.agentic.observability.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Runs the three shipped scenarios end to end against the real knowledge base and service code
 * (each run compiles and tests the generated workspace in a separate JVM). This doubles as drift
 * detection: if the service code changes so that a recipe no longer applies, this test fails.
 */
public class ScenarioEndToEndTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Path repo = Path.of("").toAbsolutePath();

    private Orchestrator orchestrator(String approvals, LlmClient llm, List<String> faults) {
        return new Orchestrator(repo, tmp.getRoot().toPath(), Catalog.load(repo.resolve("knowledge/catalog.json")),
                AgentRegistry.standard(), new PolicyEngine(), new FileApprovalGateway(approvals == null ? null : repo.resolve(approvals)),
                llm, new FaultInjector(faults), Orchestrator.Options.defaults(), System::currentTimeMillis, null);
    }

    private JsonNode artifact(String runId, String key) throws Exception {
        try (var files = Files.list(tmp.getRoot().toPath().resolve(runId).resolve("artifacts"))) {
            Path latest = files.filter(p -> p.getFileName().toString().startsWith(key + "@v"))
                    .max((a, b) -> Integer.compare(version(a), version(b))).orElseThrow();
            return Json.read(latest).path("content");
        }
    }

    private static int version(Path p) {
        String n = p.getFileName().toString();
        return Integer.parseInt(n.substring(n.indexOf("@v") + 2, n.length() - ".json".length()));
    }

    @Test
    public void greenfieldBuildsTheServiceFromScratch() throws Exception {
        RunState st = orchestrator("scenarios/greenfield/approvals.json", null, List.of())
                .start(Scenario.load(repo.resolve("scenarios/greenfield")), "gf");
        assertEquals(st.stopReason(), RunStatus.SUCCEEDED, st.status());
        JsonNode tests = artifact("gf", "test_report");
        assertTrue(tests.path("testsRun").asInt() >= 30);
        assertEquals(0, tests.path("failures").asInt());
        assertEquals(11, artifact("gf", "plan").path("tasks").size());
        assertTrue("the build file is change-controlled", st.stage("implement:T-build").approval().contains("change-control"));
        assertTrue("privacy review of transient IP use was required", st.stage("implement:T-api").approval().contains("privacy-review"));
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("gf/release/CHANGE.patch")));
        assertTrue(AuditLog.verify(tmp.getRoot().toPath().resolve("gf/audit.jsonl")).valid());
    }

    @Test
    public void brownfieldAddsExpirationWithoutRegressions() throws Exception {
        RunState st = orchestrator("scenarios/brownfield/approvals.json", null, List.of("implement:T-expiry-api:transient:1"))
                .start(Scenario.load(repo.resolve("scenarios/brownfield")), "bf");
        assertEquals(st.stopReason(), RunStatus.SUCCEEDED, st.status());
        JsonNode plan = artifact("bf", "plan");
        assertEquals(4, plan.path("tasks").size());
        assertTrue(plan.path("alreadySatisfied").size() >= 3);
        JsonNode design = artifact("bf", "design");
        assertTrue(design.path("rippleTypes").toString().contains("ShortenerConfiguration"));
        JsonNode tests = artifact("bf", "test_report");
        assertTrue("existing suite plus new tests", tests.path("testsRun").asInt() > artifact("bf", "codebase_index").path("testMethods").asInt());
        assertEquals(1, st.counter("retries"));
        String patch = Files.readString(tmp.getRoot().toPath().resolve("bf/release/CHANGE.patch"));
        assertTrue(patch.contains("+++ b/src/main/java/com/example/shortener/core/Link.java"));
        assertFalse("agents never touch the repository itself",
                Files.readString(repo.resolve("shortener-service/src/main/java/com/example/shortener/core/Link.java")).contains("expiresAt"));
    }

    @Test
    public void ambiguousRequirementPausesThenReplansAroundAPrivacyRejection() throws Exception {
        Scenario s = Scenario.load(repo.resolve("scenarios/ambiguous"));
        RunState first = orchestrator(null, null, List.of()).start(s, "amb");
        assertEquals(RunStatus.AWAITING_INPUT, first.status());
        assertEquals(StageStatus.AWAITING_INPUT, first.stage("requirements").status());
        assertEquals("nothing is built before the ambiguity is resolved", 0, first.stage("plan").attempts());
        assertTrue(Files.readString(tmp.getRoot().toPath().resolve("amb/CLARIFICATIONS.md")).contains("AMB-safety"));

        RunState done = orchestrator("scenarios/ambiguous/approvals.json", null, List.of())
                .resume("amb", repo.resolve("scenarios/ambiguous/answers.json"));
        assertEquals(done.stopReason(), RunStatus.SUCCEEDED, done.status());
        JsonNode plan = artifact("amb", "plan");
        assertEquals("geo_analytics", plan.path("excluded").get(0).path("capability").asText());
        assertEquals(2, plan.path("tasks").size());
        assertEquals(1, done.counter("approvalsRejected"));
        assertTrue(done.stage("plan").reExecutions() >= 1);
        List<String> actors = done.decisions().stream().map(Decision::actor).toList();
        assertTrue(actors.contains("human:product-owner.maria"));
        assertTrue(actors.contains("human:dpo.office"));
    }

    @Test
    public void invalidModelOutputFallsBackToDeterministicRequirements() throws Exception {
        LlmClient hallucinating = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return "{\"capabilities\":[\"teleportation\"],\"ambiguities\":[]}";
            }

            @Override
            public String model() {
                return "fake";
            }
        };
        RunState st = orchestrator("scenarios/brownfield/approvals.json", hallucinating, List.of())
                .start(Scenario.load(repo.resolve("scenarios/brownfield")), "llm-bad");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertTrue(st.stage("requirements").usedFallback());
        assertTrue(st.stage("requirements").lastError() == null || st.stage("requirements").lastError().contains("unknown capability"));
        assertEquals("rules", artifact("llm-bad", "requirements").path("interpreter").asText());
    }

    @Test
    public void validModelOutputIsUsedAfterValidation() throws Exception {
        LlmClient good = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return "Sure: {\"capabilities\":[\"link_expiration\",\"redirect\"],\"ambiguities\":[],\"rationale\":\"expiry feature\"}";
            }

            @Override
            public String model() {
                return "fake-model";
            }
        };
        RunState st = orchestrator("scenarios/brownfield/approvals.json", good, List.of())
                .start(Scenario.load(repo.resolve("scenarios/brownfield")), "llm-good");
        assertEquals(RunStatus.SUCCEEDED, st.status());
        assertFalse(st.stage("requirements").usedFallback());
        assertEquals("llm:fake-model", artifact("llm-good", "requirements").path("interpreter").asText());
    }
}
