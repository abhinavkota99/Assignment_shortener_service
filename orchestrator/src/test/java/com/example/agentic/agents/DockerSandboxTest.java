package com.example.agentic.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DockerSandboxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DockerSandbox sandbox() {
        return new DockerSandbox(new DockerSandbox.Config("img:1", Path.of("/cache"), "3g", "2", "512", "1000:1000"));
    }

    private static String joined(List<String> cmd) {
        return String.join(" ", cmd);
    }

    @Test
    public void bothPhasesAreLockedDownAndGetNoHostEnvironment() {
        DockerSandbox sb = sandbox();
        for (List<String> cmd : List.of(sb.prepareCommand("n1", Path.of("/p")),
                sb.testCommand("n2", Path.of("/p"), Path.of("/cache/wrapper/dists/g/h/gradle-8"), "gradle-cli.jar"))) {
            String c = joined(cmd);
            assertTrue(c, c.contains("--read-only"));
            assertTrue(c, c.contains("--cap-drop ALL"));
            assertTrue(c, c.contains("--security-opt no-new-privileges"));
            assertTrue(c, c.contains("--memory 3g") && c.contains("--cpus 2") && c.contains("--pids-limit 512"));
            assertTrue(c, c.contains("--user 1000:1000"));
            assertTrue(c, c.contains("-v /p:/work "));
            assertFalse("never passes the host environment through", cmd.contains("--env-file") || c.matches(".*-e [A-Z_]+ .*"));
            assertFalse("no shell in the container", c.contains(" sh ") || c.contains("gradlew"));
        }
    }

    @Test
    public void onlyThePreparePhaseHasNetworkAndAWritableCache() {
        DockerSandbox sb = sandbox();
        String prep = joined(sb.prepareCommand("n1", Path.of("/p")));
        String test = joined(sb.testCommand("n2", Path.of("/p"), Path.of("/dist"), "gradle-cli.jar"));
        assertFalse(prep.contains("--network none"));
        assertTrue(prep.contains("-v /cache:/gradle-home "));
        assertTrue(prep.endsWith("testClasses"));

        assertTrue(test.contains("--network none"));
        assertTrue(test.contains("-v /cache/caches:/gradle-ro:ro"));
        assertTrue(test.contains("-v /dist:/opt/gradle:ro"));
        assertTrue(test.contains("GRADLE_RO_DEP_CACHE=/gradle-ro"));
        assertTrue(test.contains("GRADLE_USER_HOME=/tmp/gradle-home"));
        assertTrue(test.endsWith("--offline test"));
    }

    @Test
    public void modeDefaultsToProcessAndRejectsTypos() {
        assertEquals(DockerSandbox.Mode.PROCESS, DockerSandbox.mode(null));
        assertEquals(DockerSandbox.Mode.PROCESS, DockerSandbox.mode(" "));
        assertEquals(DockerSandbox.Mode.DOCKER, DockerSandbox.mode("Docker"));
        assertEquals(DockerSandbox.Mode.AUTO, DockerSandbox.mode("auto"));
        try {
            DockerSandbox.mode("dokcer");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("process, docker or auto"));
        }
    }

    @Test
    public void configComesFromEnvironmentWithSafeDefaults() throws Exception {
        DockerSandbox.Config c = DockerSandbox.Config.fromEnv(Map.of("AGENTIC_SANDBOX_IMAGE", "custom:jdk"), tmp.getRoot().toPath());
        assertEquals("custom:jdk", c.image());
        assertEquals("3g", c.memory());
        assertTrue(c.cache().isAbsolute());
        assertEquals(DockerSandbox.DEFAULT_IMAGE, DockerSandbox.Config.fromEnv(Map.of(), tmp.getRoot().toPath()).image());
    }

    @Test
    public void findsTheDistributionTheWrapperInstalled() throws Exception {
        Path cache = tmp.newFolder("cache").toPath();
        Path props = tmp.getRoot().toPath().resolve("gradle-wrapper.properties");
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n");
        assertTrue(DockerSandbox.distribution(cache, props).isEmpty());

        Path home = cache.resolve("wrapper/dists/gradle-8.14.3-bin/abc123/gradle-8.14.3");
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("lib/gradle-gradle-cli-main-8.14.3.jar"), "");
        Files.writeString(home.resolve("lib/gradle-core-8.14.3.jar"), "");
        assertEquals(home, DockerSandbox.distribution(cache, props).orElseThrow());
        assertEquals("gradle-gradle-cli-main-8.14.3.jar", DockerSandbox.launcherJar(home).orElseThrow());
    }

    @Test
    public void containerNamesAreValidDockerNames() {
        assertEquals("agentic-run-1-implement-t-apikey-a2-test",
                DockerSandbox.containerName("Run 1", "implement:T-apikey", 2, "test"));
    }
}
