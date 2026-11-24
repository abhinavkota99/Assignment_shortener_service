package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.engine.Orchestrator;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The only high-impact action, and it runs only after explicit human approval. It does not push or
 * deploy: it packages the verified change as a patch plus a hash manifest under {@code release/},
 * ready for a human to apply through normal change management (MR review, CI, deploy pipeline).
 * Packaging is atomic (staging directory then rename), so a failure leaves no partial release.
 */
public final class ReleaseAgent implements Agent {

    @Override
    public String name() {
        return "release";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        if (!ctx.input("release_readiness").path("ready").asBoolean()) {
            throw AgentException.permanent("release readiness is not green");
        }
        Path runDir = ctx.runDir();
        Path staging = runDir.resolve("release.staging");
        Path release = runDir.resolve("release");
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            List<Map<String, Object>> diff = ctx.workspace().diffSummary(Orchestrator.BASELINE);
            ObjectNode manifest = Json.object();
            manifest.put("runId", ctx.runId());
            manifest.put("scenario", ctx.scenario().id());
            ArrayNode files = manifest.putArray("files");
            for (Map<String, Object> row : diff) {
                ObjectNode f = files.addObject();
                f.put("path", (String) row.get("path"));
                f.put("change", (String) row.get("change"));
                if (!"deleted".equals(row.get("change"))) {
                    f.put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(ctx.workspace().readBytes((String) row.get("path")))));
                }
            }
            String patch = gitPatch(runDir);
            manifest.put("patch", patch == null ? "unavailable (git not found); apply files from the workspace" : "CHANGE.patch");
            if (patch != null) {
                Files.writeString(staging.resolve("CHANGE.patch"), patch, StandardCharsets.UTF_8);
                manifest.put("patchSha256", Json.sha256(patch));
            }
            manifest.put("apply", "cd <service module> && git apply -p1 CHANGE.patch");
            Files.writeString(staging.resolve("MANIFEST.json"), Json.pretty(manifest), StandardCharsets.UTF_8);
            deleteTree(release);
            Files.move(staging, release);
            return new AgentResult("release package with " + diff.size() + " files at release/")
                    .artifact("release", manifest);
        } catch (IOException e) {
            try {
                deleteTree(staging); // compensation: no partial release
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw AgentException.transientFailure("packaging failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AgentException.transientFailure("packaging interrupted");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unified diff baseline -> workspace with paths relative to the module root (git apply -p1). */
    static String gitPatch(Path runDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--no-color", "--binary",
                "snapshots/" + Orchestrator.BASELINE, "workspace").directory(runDir.toFile()).redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return null; // git not installed
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() > 1) { // 0 = no diff, 1 = diff, >1 = error
            throw new IOException("git diff failed: " + out);
        }
        return out.replace("a/snapshots/" + Orchestrator.BASELINE + "/", "a/").replace("b/workspace/", "b/")
                .replace("a/workspace/", "a/").replace("b/snapshots/" + Orchestrator.BASELINE + "/", "b/");
    }

    private static void deleteTree(Path p) throws IOException {
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
