package com.example.agentic.observability;

import com.example.agentic.engine.Json;
import com.example.agentic.engine.RunState;
import com.example.agentic.engine.RunStatus;
import com.example.agentic.engine.StageRecord;
import com.example.agentic.engine.StageStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reliability metrics, per run and aggregated across runs ({@code runs/metrics-history.jsonl}).
 *
 * <ul>
 *   <li>success rate: finished runs that SUCCEEDED (paused runs are excluded until they finish)</li>
 *   <li>retry / fallback / rollback frequency: per run and per stage execution</li>
 *   <li>MTTR: mean time from a stage's first failure to its successful recovery (retry or fallback)</li>
 *   <li>end-to-end latency: active execution time (excludes time paused waiting for humans)</li>
 * </ul>
 */
public final class Metrics {

    private Metrics() {
    }

    public static ObjectNode forRun(RunState state) {
        ObjectNode m = Json.object();
        int executed = 0;
        int failedStages = 0;
        List<Long> recoveries = new ArrayList<>();
        ObjectNode stages = Json.object();
        for (var e : state.stages().entrySet()) {
            StageRecord r = e.getValue();
            if (r.attempts() > 0) {
                executed++;
            }
            if (r.status() == StageStatus.FAILED || r.status() == StageStatus.ROLLED_BACK) {
                failedStages++;
            }
            if (r.recoveryMillis() != null) {
                recoveries.add(r.recoveryMillis());
            }
            ObjectNode s = stages.putObject(e.getKey());
            s.put("status", r.status().name());
            s.put("attempts", r.attempts());
            s.put("reExecutions", r.reExecutions());
            s.put("fallback", r.usedFallback());
            if (r.durationMillis() != null) {
                s.put("durationMs", r.durationMillis());
            }
        }
        m.put("runId", state.runId());
        m.put("scenario", state.scenarioId());
        m.put("status", state.status().name());
        m.put("finished", state.status() != RunStatus.RUNNING && !state.status().resumable());
        m.put("endToEndActiveMs", state.activeMillis());
        m.put("stagesExecuted", executed);
        m.put("stagesFailed", failedStages);
        m.put("retries", state.counter("retries"));
        m.put("fallbacks", state.counter("fallbacks"));
        m.put("primaryUnavailable", state.counter("primaryUnavailable"));
        m.put("rollbacks", state.counter("rollbacks"));
        m.put("reExecutions", state.counter("reExecutions"));
        m.put("approvalsRequested", state.counter("approvalsRequested"));
        m.put("approvalsRejected", state.counter("approvalsRejected"));
        m.put("policyFindings", state.counter("policyFindings"));
        m.put("resumes", state.resumes());
        m.put("recoveries", recoveries.size());
        if (!recoveries.isEmpty()) {
            m.put("mttrMs", recoveries.stream().mapToLong(Long::longValue).sum() / recoveries.size());
        }
        m.set("stages", stages);
        return m;
    }

    public static void appendHistory(Path runsDir, ObjectNode runMetrics) {
        try {
            Files.createDirectories(runsDir);
            Files.writeString(runsDir.resolve("metrics-history.jsonl"), Json.compact(runMetrics) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Aggregates the latest snapshot of every run in the history file. */
    public static ObjectNode aggregate(Path runsDir) {
        Path f = runsDir.resolve("metrics-history.jsonl");
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        if (Files.exists(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        JsonNode n = Json.MAPPER.readTree(line);
                        latest.put(n.path("runId").asText(), n);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        int finished = 0;
        int succeeded = 0;
        int paused = 0;
        long retries = 0;
        long rollbacks = 0;
        long fallbacks = 0;
        long stageExecutions = 0;
        List<Long> mttrs = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        for (JsonNode n : latest.values()) {
            String status = n.path("status").asText();
            if (status.startsWith("AWAITING")) {
                paused++;
                continue;
            }
            finished++;
            if (status.equals("SUCCEEDED")) {
                succeeded++;
                latencies.add(n.path("endToEndActiveMs").asLong());
            }
            retries += n.path("retries").asLong();
            rollbacks += n.path("rollbacks").asLong();
            fallbacks += n.path("fallbacks").asLong();
            stageExecutions += n.path("stagesExecuted").asLong();
            if (n.has("mttrMs")) {
                mttrs.add(n.path("mttrMs").asLong());
            }
        }
        ObjectNode a = Json.object();
        a.put("runs", latest.size());
        a.put("finishedRuns", finished);
        a.put("pausedRuns", paused);
        a.put("successRate", finished == 0 ? 0.0 : round((double) succeeded / finished));
        a.put("retriesPerRun", finished == 0 ? 0.0 : round((double) retries / finished));
        a.put("retryRatePerStage", stageExecutions == 0 ? 0.0 : round((double) retries / stageExecutions));
        a.put("rollbackFrequency", finished == 0 ? 0.0 : round((double) rollbacks / finished));
        a.put("fallbacksPerRun", finished == 0 ? 0.0 : round((double) fallbacks / finished));
        a.put("mttrMs", mttrs.isEmpty() ? 0 : mttrs.stream().mapToLong(Long::longValue).sum() / mttrs.size());
        a.put("e2eLatencyP50Ms", percentile(latencies, 50));
        a.put("e2eLatencyP95Ms", percentile(latencies, 95));
        return a;
    }

    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
