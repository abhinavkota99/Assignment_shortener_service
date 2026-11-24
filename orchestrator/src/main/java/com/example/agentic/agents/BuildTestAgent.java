package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Verification: builds the workspace exactly as a developer would, with Gradle (compile + full test
 * suite), in an isolated copy and a separate process with a hard timeout. The workspace is the
 * service module, which is a self-contained Gradle build; the agent only adds a {@code settings.gradle}
 * to the copy so Gradle treats it as a root project.
 *
 * <p>By default the build runs as a host process (secret-like environment variables removed). With
 * {@code AGENTIC_SANDBOX=docker} (or {@code auto}) it runs in a locked-down container instead, with no
 * network while tests run; see {@link DockerSandbox}.
 *
 * <p>The result is a structured test report (counts parsed from JUnit XML). Whether it is good enough
 * is decided by the stage's exit gates, not by this agent. Dependency-download problems are reported as
 * transient (retryable) failures; compile errors and failing tests are not.
 */
public final class BuildTestAgent implements Agent {

    static final long BUILD_TIMEOUT_SECONDS = 900;
    private static final Pattern COMPILE_ERROR = Pattern.compile("^(.+\\.java):(\\d+): error: (.+)$", Pattern.MULTILINE);
    private static final Pattern NETWORK_ERROR = Pattern.compile(
            "Could not resolve|Could not GET|Could not HEAD|Connection refused|UnknownHostException|Plugin \\[id: .*\\] was not found");
    private static final Pattern SECRET_ENV = Pattern.compile("(?i).*(token|secret|password|passwd|api_?key|credential).*");

    @Override
    public String name() {
        return "build-test";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        ctx.input("design"); // lineage
        Path out = ctx.runDir().resolve("build").resolve(ctx.stage().id().replace(':', '_') + "-a" + ctx.attempt());
        Path project = out.resolve("project");
        Path log = out.resolve("gradle.log");
        ObjectNode report = Json.object();
        long started = System.nanoTime();
        try {
            deleteRecursively(out);
            Files.createDirectories(project);
            int files = 0;
            for (String f : ctx.workspace().files()) {
                Path target = project.resolve(f);
                Files.createDirectories(target.getParent());
                Files.copy(ctx.workspace().root().resolve(f), target);
                files++;
            }
            if (!Files.exists(project.resolve("build.gradle"))) {
                throw AgentException.permanent("workspace has no build.gradle; nothing to build");
            }
            Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'shortener-service'\n", StandardCharsets.UTF_8);
            report.put("sourceFiles", files);

            DockerSandbox.Mode mode;
            try {
                mode = DockerSandbox.mode(System.getenv("AGENTIC_SANDBOX"));
            } catch (IllegalArgumentException e) {
                throw AgentException.permanent(e.getMessage());
            }
            boolean docker = switch (mode) {
                case PROCESS -> false;
                case AUTO -> DockerSandbox.available();
                case DOCKER -> {
                    if (!DockerSandbox.available()) {
                        // isolation was required: never fall back to a weaker sandbox silently
                        throw AgentException.permanent("AGENTIC_SANDBOX=docker but no Docker daemon is reachable");
                    }
                    yield true;
                }
            };
            report.put("sandbox", docker ? "docker" : "process");
            Path usedLog = log;
            int exit;
            if (docker) {
                DockerSandbox sb = new DockerSandbox(DockerSandbox.Config.fromEnv(System.getenv(), project));
                report.set("isolation", Json.tree(sb.describe()));
                DockerSandbox.Exec ex = sandboxed(ctx, sb, project, out, report);
                exit = ex.exitCode();
                usedLog = ex.log();
            } else {
                exit = hostProcess(ctx, project, log);
            }
            String text = Files.readString(usedLog, StandardCharsets.UTF_8);
            report.put("log", ctx.runDir().relativize(usedLog).toString());
            report.put("gradleExitCode", exit);

            Path results = project.resolve("build/test-results/test");
            boolean haveResults = Files.isDirectory(results);
            if (exit != 0 && !haveResults) {
                if (NETWORK_ERROR.matcher(text).find()) {
                    throw AgentException.transientFailure("dependency resolution failed (network?); see " + report.path("log").asText());
                }
                if (docker && !text.contains("BUILD FAILED") && !COMPILE_ERROR.matcher(text).find()) {
                    throw AgentException.transientFailure("sandbox container failed to start (exit " + exit + "); see "
                            + report.path("log").asText());
                }
                report.put("compiled", false);
                ArrayNode errors = report.putArray("compileErrors");
                Matcher m = COMPILE_ERROR.matcher(text);
                java.util.Set<String> seen = new java.util.LinkedHashSet<>(); // Gradle repeats errors in its summary
                while (m.find() && seen.size() < 20) {
                    seen.add(relative(project, m.group(1).trim()) + ":" + m.group(2) + ": " + m.group(3));
                }
                seen.forEach(errors::add);
                if (errors.isEmpty()) {
                    errors.add("build failed before tests ran; see " + report.path("log").asText());
                }
                report.put("testsRun", 0);
                report.put("failures", 0);
                return new AgentResult("build failed (" + errors.size() + " errors)").artifact("test_report", report);
            }
            report.put("compiled", true);
            readJUnitXml(results, report);
        } catch (IOException e) {
            throw AgentException.transientFailure("build I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AgentException.transientFailure("build interrupted");
        }
        report.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        String summary = report.path("testsRun").asInt() + " tests, " + report.path("failures").asInt() + " failures";
        return new AgentResult(summary).artifact("test_report", report);
    }

    /** Plain host process on the isolated copy: secret-like variables are stripped from its environment. */
    private static int hostProcess(AgentContext ctx, Path project, Path log) throws IOException, InterruptedException, AgentException {
        List<String> cmd = new ArrayList<>(gradleCommand(ctx.repoRoot()));
        cmd.addAll(List.of("-p", project.toString(), "test", "--console=plain", "--stacktrace"));
        String extra = System.getenv("AGENTIC_WORKSPACE_GRADLE_ARGS");
        if (extra != null && !extra.isBlank()) {
            cmd.addAll(List.of(extra.trim().split("\\s+")));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // The code under test must not see the operator's credentials.
        pb.environment().keySet().removeIf(k -> SECRET_ENV.matcher(k).matches());
        Process p = pb.start();
        if (!p.waitFor(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw AgentException.transientFailure("workspace build exceeded " + BUILD_TIMEOUT_SECONDS + " s and was killed");
        }
        return p.exitValue();
    }

    /** Docker: online prepare phase, then the offline test phase (see {@link DockerSandbox}). */
    private static DockerSandbox.Exec sandboxed(AgentContext ctx, DockerSandbox sb, Path project, Path out, ObjectNode report)
            throws IOException, InterruptedException, AgentException {
        DockerSandbox.installWrapper(ctx.repoRoot(), project);
        Files.createDirectories(sb.config().cache());
        String stage = ctx.stage().id();
        String prepName = DockerSandbox.containerName(ctx.runId(), stage, ctx.attempt(), "prepare");
        DockerSandbox.Exec prep = sb.run(sb.prepareCommand(prepName, project), prepName, out.resolve("prepare.log"),
                BUILD_TIMEOUT_SECONDS);
        report.put("prepareExitCode", prep.exitCode());
        if (prep.exitCode() != 0) {
            return prep; // dependency or compile problem: classified by the caller from this log
        }
        Path dist = DockerSandbox.distribution(sb.config().cache(), project.resolve("gradle/wrapper/gradle-wrapper.properties"))
                .orElseThrow(() -> AgentException.permanent("prepare phase did not leave a Gradle distribution in "
                        + sb.config().cache()));
        String launcher = DockerSandbox.launcherJar(dist)
                .orElseThrow(() -> AgentException.permanent("no Gradle launcher jar in " + dist));
        Files.createDirectories(sb.config().cache().resolve("caches"));
        String testName = DockerSandbox.containerName(ctx.runId(), stage, ctx.attempt(), "test");
        return sb.run(sb.testCommand(testName, project, dist, launcher), testName, out.resolve("gradle.log"), BUILD_TIMEOUT_SECONDS);
    }

    private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*>");
    private static final Pattern FAILED_CASE = Pattern.compile(
            "<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\"[^>]*>\\s*<(failure|error)\\b");

    /** Aggregates Gradle's JUnit XML reports (works for JUnit 4 and JUnit Platform alike). */
    static void readJUnitXml(Path dir, ObjectNode report) throws IOException {
        int tests = 0;
        int skipped = 0;
        int failed = 0;
        ArrayNode classes = report.putArray("testClasses");
        ArrayNode failedTests = Json.array();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path xml : s.filter(x -> x.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                    String text = Files.readString(xml, StandardCharsets.UTF_8);
                    Matcher suite = SUITE.matcher(text);
                    if (!suite.find()) {
                        continue;
                    }
                    String header = suite.group();
                    tests += attr(header, "tests");
                    skipped += attr(header, "skipped");
                    failed += attr(header, "failures") + attr(header, "errors");
                    classes.add(xml.getFileName().toString().replaceFirst("^TEST-", "").replaceFirst("\\.xml$", ""));
                    Matcher f = FAILED_CASE.matcher(text);
                    while (f.find() && failedTests.size() < 20) {
                        failedTests.add(f.group(2) + "." + f.group(1));
                    }
                }
            }
        }
        report.put("testsRun", tests - skipped);
        report.put("skipped", skipped);
        report.put("failures", failed);
        if (!failedTests.isEmpty()) {
            report.set("failedTests", failedTests);
        }
    }

    private static int attr(String header, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(header);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** The repository's Gradle wrapper when present (pinned Gradle version), else a Gradle on PATH. */
    static List<String> gradleCommand(Path repoRoot) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = repoRoot.resolve(windows ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(wrapper)) {
            return List.of(wrapper.toAbsolutePath().toString());
        }
        return List.of(windows ? "gradle.bat" : "gradle");
    }

    private static String relative(Path project, String file) {
        if (file.startsWith("/work/")) { // compiler output from inside the sandbox container
            return file.substring("/work/".length());
        }
        Path p = Path.of(file);
        return p.startsWith(project) ? project.relativize(p).toString().replace(File.separatorChar, '/') : p.getFileName().toString();
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }

    /** Visible for tests. */
    static Map<String, String> secretFree(Map<String, String> env) {
        Map<String, String> copy = new java.util.HashMap<>(env);
        copy.keySet().removeIf(k -> SECRET_ENV.matcher(k).matches());
        return copy;
    }
}
