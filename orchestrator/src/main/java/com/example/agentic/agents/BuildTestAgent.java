package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Verification: compiles every main and test source in the workspace (Java 17 target) and runs
 * the complete JUnit suite in a separate JVM with a hard timeout, so a hanging or crashing test can
 * never take down the orchestrator. Output is a structured test report; whether it is good enough
 * is decided by the stage's exit gates, not by this agent.
 */
public final class BuildTestAgent implements Agent {

    private static final Pattern OK = Pattern.compile("^OK \\((\\d+) tests?\\)", Pattern.MULTILINE);
    private static final Pattern FAILED = Pattern.compile("^Tests run: (\\d+),\\s+Failures: (\\d+)", Pattern.MULTILINE);
    private static final Pattern FAILURE_LINE = Pattern.compile("^\\d+\\) (.+)$", Pattern.MULTILINE);
    static final long TEST_TIMEOUT_SECONDS = 300;

    @Override
    public String name() {
        return "build-test";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        ctx.input("design"); // lineage
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw AgentException.permanent("a JDK (not a JRE) is required to compile the workspace");
        }
        Path ws = ctx.workspace().root();
        List<File> sources = new ArrayList<>();
        List<String> testClasses = new ArrayList<>();
        for (String f : ctx.workspace().files()) {
            if (!f.endsWith(".java")) {
                continue;
            }
            sources.add(ws.resolve(f).toFile());
            if (f.startsWith("src/test/java/") && f.endsWith("Test.java")) {
                testClasses.add(f.substring("src/test/java/".length(), f.length() - ".java".length()).replace('/', '.'));
            }
        }
        Path out = ctx.runDir().resolve("build").resolve(ctx.stage().id().replace(':', '_') + "-a" + ctx.attempt());
        Path classes = out.resolve("classes");
        String libs = libraryClasspath();
        ObjectNode report = Json.object();
        report.put("sourceFiles", sources.size());
        long started = System.nanoTime();
        try {
            deleteRecursively(out);
            Files.createDirectories(classes);
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            boolean compiled;
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> options = List.of("--release", "17", "-encoding", "UTF-8", "-proc:none", "-nowarn",
                        "-cp", libs, "-d", classes.toString());
                compiled = sources.isEmpty() || javac.getTask(new StringWriter(), fm, diags, options, null,
                        fm.getJavaFileObjectsFromFiles(sources)).call();
            }
            report.put("compiled", compiled);
            if (!compiled) {
                ArrayNode errors = report.putArray("compileErrors");
                diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(20)
                        .forEach(d -> errors.add(relative(ws, d) + ": " + d.getMessage(Locale.ROOT)));
                report.put("testsRun", 0);
                report.put("failures", 0);
                return new AgentResult("compilation failed (" + errors.size() + " errors)").artifact("test_report", report);
            }
            if (testClasses.isEmpty()) {
                report.put("testsRun", 0);
                report.put("failures", 0);
                return new AgentResult("compiled; no tests found").artifact("test_report", report);
            }
            runTests(ctx, report, classes, libs, testClasses, out);
        } catch (IOException e) {
            throw AgentException.transientFailure("build I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AgentException.transientFailure("build interrupted");
        }
        report.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        report.set("testClasses", Json.tree(testClasses));
        String summary = report.path("testsRun").asInt() + " tests, " + report.path("failures").asInt() + " failures";
        return new AgentResult(summary).artifact("test_report", report);
    }

    private static void runTests(AgentContext ctx, ObjectNode report, Path classes, String libs, List<String> testClasses, Path out)
            throws IOException, InterruptedException, AgentException {
        Path log = out.resolve("junit.log");
        List<String> cmd = new ArrayList<>(List.of(javaBinary(), "-Xmx512m", "-cp", classes + File.pathSeparator + libs,
                "org.junit.runner.JUnitCore"));
        cmd.addAll(testClasses);
        // Fixed, minimal environment for the test JVM: no credentials leak into the code under test.
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(out.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().keySet().removeIf(k -> !k.equals("PATH") && !k.equals("HOME") && !k.equals("TMPDIR") && !k.equals("LANG"));
        Process p = pb.start();
        if (!p.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw AgentException.transientFailure("test JVM exceeded " + TEST_TIMEOUT_SECONDS + " s and was killed");
        }
        String text = Files.readString(log, StandardCharsets.UTF_8);
        report.put("log", ctx.runDir().relativize(log).toString());
        Matcher ok = OK.matcher(text);
        Matcher failed = FAILED.matcher(text);
        if (ok.find()) {
            report.put("testsRun", Integer.parseInt(ok.group(1)));
            report.put("failures", 0);
        } else if (failed.find()) {
            report.put("testsRun", Integer.parseInt(failed.group(1)));
            report.put("failures", Integer.parseInt(failed.group(2)));
            ArrayNode f = report.putArray("failedTests");
            Matcher fl = FAILURE_LINE.matcher(text);
            while (fl.find() && f.size() < 20) {
                f.add(fl.group(1));
            }
        } else {
            throw AgentException.transientFailure("could not parse JUnit output (exit " + p.exitValue() + "); see " + log);
        }
    }

    /** Jars the workspace code compiles and tests against: the same pinned versions the orchestrator ships with. */
    static String libraryClasspath() {
        List<String> keep = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName() == null ? "" : Path.of(entry).getFileName().toString();
            if (name.endsWith(".jar") && (name.startsWith("jackson-") || name.startsWith("junit-") || name.startsWith("hamcrest-"))) {
                keep.add(entry);
            }
        }
        return String.join(File.pathSeparator, keep);
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static String relative(Path ws, Diagnostic<? extends JavaFileObject> d) {
        if (d.getSource() == null) {
            return "?";
        }
        Path p = Path.of(d.getSource().toUri());
        return (p.startsWith(ws) ? ws.relativize(p).toString() : p.getFileName().toString()) + ":" + d.getLineNumber();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
