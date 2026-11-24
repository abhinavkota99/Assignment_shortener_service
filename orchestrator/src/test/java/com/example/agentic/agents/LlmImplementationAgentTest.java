package com.example.agentic.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.agentic.context.ContextStore;
import com.example.agentic.engine.Json;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.PolicyFinding;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.llm.LlmClient;
import com.example.agentic.workflow.StageSpec;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LlmImplementationAgentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SERVICE = "src/main/java/com/example/shortener/core/Greeter.java";
    private static final String ORIGINAL = "package com.example.shortener.core;\n\npublic class Greeter {\n    String greet() {\n        return \"hi\";\n    }\n}\n";

    private final Catalog catalog = Catalog.load(Path.of("knowledge/catalog.json").toAbsolutePath());
    private Workspace workspace;
    private ContextStore context;
    private Scenario scenario;
    private Path runDir;
    private final List<String> prompts = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path baseline = root.resolve("baseline");
        Files.createDirectories(baseline.resolve(SERVICE).getParent());
        Files.writeString(baseline.resolve(SERVICE), ORIGINAL);
        Files.writeString(baseline.resolve("build.gradle"), "plugins { id 'java' }\n");
        runDir = root.resolve("run");
        workspace = new Workspace(runDir.resolve("workspace"), runDir.resolve("state"));
        workspace.initFrom(baseline);
        context = new ContextStore(runDir, Map.of(), System::currentTimeMillis);
        ObjectNode plan = Json.object();
        ObjectNode task = plan.putArray("tasks").addObject();
        task.put("id", "T-apikey");
        task.put("title", "API key authentication for create");
        task.put("capability", "api_key_auth");
        task.set("files", Json.tree(List.of("src/main/java/com/example/shortener/api/ApiKeyAuthenticator.java")));
        task.set("recipe", Json.object().put("type", "none"));
        context.put("plan", "planner", plan);
        context.put("design", "design", Json.object().put("ok", true));
        Path scen = root.resolve("scenario");
        Files.createDirectories(scen);
        Files.writeString(scen.resolve("scenario.json"), "{\"id\":\"s\",\"title\":\"s\",\"type\":\"greenfield\","
                + "\"requirement\":\"Only callers with an API key may create links.\"}");
        scenario = Scenario.load(scen);
    }

    private AgentContext ctx(LlmClient llm) {
        StageSpec stage = StageSpec.expandedTask(new StageSpec(), "implement:T-apikey", "T-apikey", List.of(), "test");
        Function<String, com.example.agentic.engine.StageStatus> status = id -> null;
        return new AgentContext("run-1", stage, 1, context, workspace, scenario, catalog, new PolicyEngine(), llm,
                Path.of(".").toAbsolutePath(), runDir, status);
    }

    private LlmClient replying(String... replies) {
        return new LlmClient() {
            private int i;

            @Override
            public String complete(String system, String user) {
                prompts.add(user);
                return replies[Math.min(i++, replies.length - 1)];
            }

            @Override
            public String model() {
                return "fake-model";
            }
        };
    }

    private static String reply(String opsJson) {
        return "Sure, here is the change:\n{\"rationale\":\"adds the feature\",\"ops\":" + opsJson + "}";
    }

    @Test
    public void modelSeesCurrentCodeAndItsChangeAlwaysNeedsHumanReview() throws Exception {
        String ops = "[{\"op\":\"patch\",\"path\":\"" + SERVICE + "\",\"find\":\"return \\\"hi\\\";\",\"replace\":\"return \\\"hello\\\";\"},"
                + "{\"op\":\"create\",\"path\":\"src/test/java/com/example/shortener/core/GreeterTest.java\","
                + "\"content\":\"class GreeterTest {}\\n\"}]";
        AgentResult r = new LlmImplementationAgent().execute(ctx(replying(reply(ops))));

        String prompt = prompts.get(0);
        assertTrue("prompt shows the existing file", prompt.contains(ORIGINAL));
        assertTrue("prompt names the file the task will create", prompt.contains("ApiKeyAuthenticator.java (does not exist yet)"));
        assertTrue("prompt carries acceptance criteria", prompt.contains("POST without a valid key returns 401"));

        assertTrue(workspace.read(SERVICE).contains("return \"hello\";"));
        assertTrue(workspace.exists("src/test/java/com/example/shortener/core/GreeterTest.java"));
        PolicyFinding review = r.findings().stream().filter(f -> f.ruleId().equals("AI-001")).findFirst().orElseThrow();
        assertTrue(review.requiresApproval());
        assertEquals("code-review", review.checkpoint());
        assertEquals("llm", r.artifacts().get("impl.T-apikey").path("recipe").asText());
        assertEquals("fake-model", r.artifacts().get("impl.T-apikey").path("model").asText());
        assertEquals(64, r.artifacts().get("impl.T-apikey").path("promptSha256").asText().length());
        String transcript = r.artifacts().get("impl.T-apikey").path("transcript").asText();
        assertEquals("ai/T-apikey-a1.md", transcript);
        assertTrue(Files.readString(runDir.resolve(transcript)).contains("return \\\"hello\\\";"));
    }

    @Test
    public void patchThatDoesNotMatchIsRetriedWithFeedbackAndWritesNothing() throws Exception {
        LlmImplementationAgent agent = new LlmImplementationAgent();
        String bad = "[{\"op\":\"patch\",\"path\":\"" + SERVICE + "\",\"find\":\"return \\\"bye\\\";\",\"replace\":\"x\"}]";
        String good = "[{\"op\":\"patch\",\"path\":\"" + SERVICE + "\",\"find\":\"return \\\"hi\\\";\",\"replace\":\"return \\\"ok\\\";\"}]";
        LlmClient llm = replying(reply(bad), reply(good));
        try {
            agent.execute(ctx(llm));
            fail("bad patch accepted");
        } catch (AgentException e) {
            assertEquals(AgentException.Kind.TRANSIENT, e.kind());
        }
        assertEquals(ORIGINAL, workspace.read(SERVICE));
        agent.execute(ctx(llm));
        assertTrue(prompts.get(1).contains("Your previous reply was rejected"));
        assertFalse(prompts.get(0).contains("Your previous reply was rejected"));
        assertTrue(workspace.read(SERVICE).contains("\"ok\""));
    }

    @Test
    public void hardCodedSecretIsBlockedByPolicyBeforeWriting() throws Exception {
        String ops = "[{\"op\":\"create\",\"path\":\"src/main/java/com/example/shortener/api/ApiKeyAuthenticator.java\","
                + "\"content\":\"class ApiKeyAuthenticator { String apiKey = \\\"sk-live-1234567890\\\"; }\"}]";
        AgentResult r = new LlmImplementationAgent().execute(ctx(replying(reply(ops))));
        assertTrue(PolicyEngine.anyBlocking(r.findings()));
        assertFalse(workspace.exists("src/main/java/com/example/shortener/api/ApiKeyAuthenticator.java"));
    }

    @Test
    public void withoutModelTheAgentIsUnavailableNotFailed() {
        try {
            new LlmImplementationAgent().execute(ctx(null));
            fail();
        } catch (AgentException e) {
            assertEquals(AgentException.Kind.UNAVAILABLE, e.kind());
        }
    }

    @Test
    public void rejectsRepliesOutsideTheAllowedShape() {
        String[] replies = {
            null,
            "no json",
            "{\"ops\":[]}",
            reply("[{\"op\":\"delete\",\"path\":\"src/main/java/A.java\"}]"),
            reply("[{\"op\":\"create\",\"path\":\"build.gradle\",\"content\":\"x\"}]"),
            reply("[{\"op\":\"create\",\"path\":\"src/main/resources/application.yml\",\"content\":\"x\"}]"),
            reply("[{\"op\":\"create\",\"path\":\".github/workflows/ci.yml\",\"content\":\"x\"}]"),
            reply("[{\"op\":\"create\",\"path\":\"src/main/java/../../../etc/A.java\",\"content\":\"x\"}]"),
            reply("[{\"op\":\"create\",\"path\":\"/src/main/java/A.java\",\"content\":\"x\"}]"),
            reply("[{\"op\":\"create\",\"path\":\"src/main/java/A.java\"}]"),
            reply("[{\"op\":\"patch\",\"path\":\"src/main/java/A.java\",\"find\":\"\",\"replace\":\"x\"}]"),
            reply("[" + "{\"op\":\"create\",\"path\":\"src/main/java/A.java\",\"content\":\"x\"},".repeat(15)
                    + "{\"op\":\"create\",\"path\":\"src/main/java/B.java\",\"content\":\"x\"}]"),
        };
        for (String r : replies) {
            try {
                LlmImplementationAgent.parse(r);
                fail("accepted: " + r);
            } catch (AgentException e) {
                assertEquals(AgentException.Kind.TRANSIENT, e.kind());
            }
        }
    }
}
