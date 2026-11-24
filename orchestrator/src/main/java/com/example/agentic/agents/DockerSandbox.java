package com.example.agentic.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the workspace build inside a locked-down Docker container instead of a host process.
 *
 * <p>Two phases, so that code written by agents never runs with network access:
 * <ol>
 *   <li><b>prepare</b> (network on): the Gradle wrapper downloads Gradle and the build resolves its
 *       dependencies and compiles ({@code testClasses}). This executes the build script, which agents
 *       can only change under the change-control policy (human approval), and compiles, but does not
 *       run, application code. Results go to a host cache directory shared between runs.</li>
 *   <li><b>test</b> (network off): {@code --offline test}. The dependency cache is mounted
 *       <em>read-only</em> ({@code GRADLE_RO_DEP_CACHE}) and Gradle's own home is a throw-away tmpfs, so
 *       code under test can neither reach the network nor tamper with what later runs will use.</li>
 * </ol>
 * Both phases run with a read-only root filesystem, all Linux capabilities dropped,
 * {@code no-new-privileges}, memory/CPU/process limits, as the invoking user, and with no environment
 * variables from the host apart from the few set here (so no credentials leak in). Gradle is started with
 * {@code java -jar}, so the image needs nothing but a JDK: no shell scripts run in the container.
 *
 * <p>Mode is chosen by {@code AGENTIC_SANDBOX}: {@code process} (default, isolated copy in a host
 * process), {@code docker} (required: fail if Docker is unavailable, never silently weaken isolation)
 * or {@code auto} (Docker when available, else process).
 */
final class DockerSandbox {

    enum Mode { PROCESS, DOCKER, AUTO }

    static final String DEFAULT_IMAGE = "eclipse-temurin:17-jdk";

    /** Settings, all overridable through environment variables. */
    record Config(String image, Path cache, String memory, String cpus, String pids, String user) {

        /** Values end up as separate {@code docker run} arguments: reject anything that could be read as an option. */
        Config {
            check("AGENTIC_SANDBOX_IMAGE", image, "[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");
            check("AGENTIC_SANDBOX_MEMORY", memory, "[0-9]{1,6}[bkmgBKMG]?");
            check("AGENTIC_SANDBOX_CPUS", cpus, "[0-9]{1,3}(\\.[0-9]{1,2})?");
            check("AGENTIC_SANDBOX_PIDS", pids, "[0-9]{1,6}");
            if (user != null) {
                check("sandbox user", user, "[0-9]{1,10}:[0-9]{1,10}");
            }
            if (cache.toString().contains(":")) {
                throw new IllegalArgumentException("AGENTIC_SANDBOX_CACHE must not contain ':' (it is used in a volume mount)");
            }
        }

        private static void check(String name, String value, String pattern) {
            if (value == null || !value.matches(pattern)) {
                throw new IllegalArgumentException(name + " has an invalid value: '" + value + "'");
            }
        }

        static Config fromEnv(Map<String, String> env, Path project) {
            String home = System.getProperty("user.home");
            return new Config(
                    env.getOrDefault("AGENTIC_SANDBOX_IMAGE", DEFAULT_IMAGE),
                    Path.of(env.getOrDefault("AGENTIC_SANDBOX_CACHE", Path.of(home, ".agentic", "sandbox-gradle").toString()))
                            .toAbsolutePath().normalize(),
                    env.getOrDefault("AGENTIC_SANDBOX_MEMORY", "3g"),
                    env.getOrDefault("AGENTIC_SANDBOX_CPUS", "2"),
                    env.getOrDefault("AGENTIC_SANDBOX_PIDS", "1024"),
                    hostUser(project).orElse(null));
        }
    }

    /** Exit code and log of one container run. */
    record Exec(int exitCode, Path log) { }

    private final Config config;

    DockerSandbox(Config config) {
        this.config = config;
    }

    Config config() {
        return config;
    }

    static Mode mode(String value) {
        if (value == null || value.isBlank()) {
            return Mode.PROCESS;
        }
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AGENTIC_SANDBOX must be process, docker or auto (was '" + value + "')");
        }
    }

    /** True when a Docker daemon answers within a few seconds. */
    static boolean available() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** What the report and audit trail record about the isolation that was applied. */
    List<String> describe() {
        return List.of("image=" + config.image(), "read-only root filesystem", "cap-drop=ALL", "no-new-privileges",
                "memory=" + config.memory(), "cpus=" + config.cpus(), "pids-limit=" + config.pids(),
                "user=" + (config.user() == null ? "image default" : config.user()), "host environment not passed in",
                "prepare phase: network on (dependencies, compile)", "test phase: --network none, read-only dependency cache");
    }

    /** Copies the repository's Gradle wrapper (jar + properties) into the project copy. */
    static void installWrapper(Path repoRoot, Path project) throws IOException {
        Path src = repoRoot.resolve("gradle/wrapper");
        Path dst = project.resolve("gradle/wrapper");
        Files.createDirectories(dst);
        for (String f : List.of("gradle-wrapper.jar", "gradle-wrapper.properties")) {
            if (!Files.isRegularFile(src.resolve(f))) {
                throw new IOException("Gradle wrapper file missing: " + src.resolve(f));
            }
            Files.copy(src.resolve(f), dst.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Phase 1: online, writable shared cache, Gradle started through the wrapper (downloads Gradle if needed). */
    List<String> prepareCommand(String name, Path project) {
        List<String> cmd = base(name, project);
        cmd.addAll(List.of("-v", mount(config.cache(), "/gradle-home", false), "-e", "GRADLE_USER_HOME=/gradle-home",
                config.image(), "java", "-Xmx1g", "-cp", "/work/gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain"));
        cmd.addAll(gradleArgs(List.of("testClasses")));
        return cmd;
    }

    /** Phase 2: no network, dependency cache and Gradle distribution read-only, Gradle home on tmpfs. */
    List<String> testCommand(String name, Path project, Path distribution, String launcherJar) {
        List<String> cmd = base(name, project);
        cmd.addAll(List.of("--network", "none",
                "-v", mount(config.cache().resolve("caches"), "/gradle-ro", true),
                "-v", mount(distribution, "/opt/gradle", true),
                "-e", "GRADLE_USER_HOME=/tmp/gradle-home", "-e", "GRADLE_RO_DEP_CACHE=/gradle-ro",
                config.image(), "java", "-Xmx1g", "-jar", "/opt/gradle/lib/" + launcherJar));
        cmd.addAll(gradleArgs(List.of("--offline", "test")));
        return cmd;
    }

    private List<String> base(String name, Path project) {
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                "--read-only", "--tmpfs", "/tmp:rw,exec,nosuid,size=2g",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", config.memory(), "--cpus", config.cpus(), "--pids-limit", config.pids()));
        if (config.user() != null) {
            cmd.addAll(List.of("--user", config.user()));
        }
        cmd.addAll(List.of("-e", "HOME=/tmp", "-v", mount(project, "/work", false), "-w", "/work"));
        return cmd;
    }

    private static List<String> gradleArgs(List<String> tasks) {
        List<String> a = new ArrayList<>(List.of("--no-daemon", "--console=plain", "--stacktrace", "-p", "/work"));
        a.addAll(tasks);
        return a;
    }

    private static String mount(Path host, String container, boolean readOnly) {
        return host.toAbsolutePath() + ":" + container + (readOnly ? ":ro" : "");
    }

    /**
     * Locates the unpacked Gradle distribution the wrapper installed into the cache during the prepare
     * phase ({@code wrapper/dists/<name>/<hash>/<gradle-x.y>}), so the test phase can run it read-only.
     */
    static Optional<Path> distribution(Path cache, Path wrapperProperties) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(wrapperProperties)) {
            p.load(in);
        }
        String url = p.getProperty("distributionUrl", "");
        String zip = url.substring(url.lastIndexOf('/') + 1);
        if (!zip.endsWith(".zip")) {
            return Optional.empty();
        }
        Path dists = cache.resolve("wrapper/dists").resolve(zip.substring(0, zip.length() - 4));
        if (!Files.isDirectory(dists)) {
            return Optional.empty();
        }
        try (Stream<Path> hashes = Files.list(dists)) {
            for (Path h : hashes.sorted().toList()) {
                try (Stream<Path> inner = Files.list(h)) {
                    Optional<Path> home = inner.filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().startsWith("gradle-") && Files.isDirectory(d.resolve("lib")))
                            .findFirst();
                    if (home.isPresent()) {
                        return home;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The Gradle CLI entry jar inside a distribution ({@code lib/gradle-gradle-cli-main-<version>.jar} or similar). */
    static Optional<String> launcherJar(Path distribution) throws IOException {
        try (Stream<Path> s = Files.list(distribution.resolve("lib"))) {
            List<String> names = s.map(x -> x.getFileName().toString()).sorted().toList();
            return names.stream().filter(n -> n.startsWith("gradle-gradle-cli-main-")).findFirst()
                    .or(() -> names.stream().filter(n -> n.startsWith("gradle-launcher-")).findFirst());
        }
    }

    /** Runs one container with a hard timeout; on timeout the container itself is removed, not just the client. */
    Exec run(List<String> cmd, String name, Path log, long timeoutSeconds) throws IOException, InterruptedException, AgentException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        Process p = pb.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(30, TimeUnit.SECONDS);
            throw AgentException.transientFailure("sandboxed build exceeded " + timeoutSeconds + " s; container " + name + " removed");
        }
        return new Exec(p.exitValue(), log);
    }

    /** uid:gid owning the project copy, so files written in the container stay owned by the operator (POSIX only). */
    static Optional<String> hostUser(Path project) {
        try {
            Object uid = Files.getAttribute(project, "unix:uid");
            Object gid = Files.getAttribute(project, "unix:gid");
            return Optional.of(uid + ":" + gid);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    static String containerName(String runId, String stageId, int attempt, String phase) {
        String raw = "agentic-" + runId + "-" + stageId + "-a" + attempt + "-" + phase;
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    }

    static String read(Path log) throws IOException {
        return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }
}
