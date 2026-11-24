package com.example.agentic.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuildTestAgentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aggregatesGradleJUnitXmlReports() throws Exception {
        Path dir = tmp.newFolder("test-results").toPath();
        Files.writeString(dir.resolve("TEST-com.example.ATest.xml"),
                "<?xml version=\"1.0\"?>\n<testsuite name=\"com.example.ATest\" tests=\"3\" skipped=\"1\" failures=\"0\" errors=\"0\">\n"
                        + "<testcase name=\"ok\" classname=\"com.example.ATest\" time=\"0.1\"/>\n</testsuite>\n");
        Files.writeString(dir.resolve("TEST-com.example.BTest.xml"),
                "<?xml version=\"1.0\"?>\n<testsuite name=\"com.example.BTest\" tests=\"2\" skipped=\"0\" failures=\"1\" errors=\"0\">\n"
                        + "<testcase name=\"broken()\" classname=\"com.example.BTest\" time=\"0.2\">\n  <failure message=\"x\">boom</failure>\n</testcase>\n</testsuite>\n");
        ObjectNode report = Json.object();
        BuildTestAgent.readJUnitXml(dir, report);
        assertEquals(4, report.path("testsRun").asInt());
        assertEquals(1, report.path("skipped").asInt());
        assertEquals(1, report.path("failures").asInt());
        assertEquals("com.example.BTest.broken()", report.path("failedTests").get(0).asText());
        assertEquals(2, report.path("testClasses").size());
    }

    @Test
    public void codeUnderTestDoesNotSeeCredentials() {
        Map<String, String> env = BuildTestAgent.secretFree(Map.of("PATH", "/bin", "ANTHROPIC_API_KEY", "x", "GITHUB_TOKEN", "y",
                "DB_PASSWORD", "z", "JAVA_HOME", "/jdk"));
        assertEquals(Map.of("PATH", "/bin", "JAVA_HOME", "/jdk"), env);
    }

    @Test
    public void prefersTheRepositoryGradleWrapper() throws Exception {
        Path repo = tmp.newFolder("repo").toPath();
        assertFalse(BuildTestAgent.gradleCommand(repo).get(0).endsWith("gradlew"));
        Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\n");
        assertTrue(BuildTestAgent.gradleCommand(repo).get(0).endsWith("gradlew"));
    }
}
