package com.example.agentic.observability;

import com.example.agentic.context.ContextStore;
import com.example.agentic.context.Decision;
import com.example.agentic.engine.Json;
import com.example.agentic.engine.RunState;
import com.example.agentic.engine.StageRecord;
import com.example.agentic.workflow.StageSpec;
import com.example.agentic.workflow.WorkflowGraph;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Human-readable engineering summary of a run ({@code REPORT.md}), regenerated at every pause and at
 * the end: what was asked, how it was interpreted, the plan, what ran (and retried, fell back, rolled
 * back), every human decision, validation evidence, risks, metrics and the audit-chain status.
 */
public final class RunReport {

    private RunReport() {
    }

    public static void write(Path runDir, RunState st, ContextStore ctx, WorkflowGraph graph, Workspace ws, AuditLog.Verification audit) {
        StringBuilder md = new StringBuilder();
        md.append("# Run report: ").append(st.runId()).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Scenario | `").append(st.scenarioId()).append("` |\n");
        md.append("| Status | **").append(st.status()).append("**")
                .append(st.stopReason() == null ? "" : " - " + st.stopReason()).append(" |\n");
        md.append("| Started | ").append(Instant.ofEpochMilli(st.createdAtMillis())).append(" |\n");
        md.append("| Active time | ").append(st.activeMillis()).append(" ms (excludes time paused for humans) |\n");
        md.append("| Resumes | ").append(st.resumes()).append(" |\n");
        md.append("| Audit chain | ").append(audit.valid() ? "verified" : "**BROKEN**").append(", ").append(audit.entries())
                .append(" entries (").append(audit.detail()).append(") |\n\n");

        Optional<JsonNode> req = content(ctx, "requirements");
        req.ifPresent(r -> {
            md.append("## 1. Requirement understanding\n\n> ").append(r.path("source").asText()).append("\n\n");
            md.append("Interpreter: `").append(r.path("interpreter").asText()).append("` - ").append(r.path("summary").asText()).append("\n\n");
            md.append("| Capability | Source | Functional requirements |\n|---|---|---|\n");
            r.path("capabilities").forEach(c -> {
                StringBuilder frs = new StringBuilder();
                c.path("requirements").forEach(x -> frs.append(x.path("id").asText()).append(": ").append(x.path("text").asText()).append("<br>"));
                md.append("| ").append(c.path("title").asText()).append(" | ").append(c.path("source").asText()).append(" | ")
                        .append(frs).append(" |\n");
            });
            if (!r.path("ambiguities").isEmpty()) {
                md.append("\n**Ambiguities**\n\n| Id | Question | Blocking | Resolution |\n|---|---|---|---|\n");
                r.path("ambiguities").forEach(a -> md.append("| ").append(a.path("id").asText()).append(" | ")
                        .append(a.path("question").asText()).append(" | ").append(a.path("blocking").asBoolean()).append(" | ")
                        .append(a.has("resolution") ? a.path("resolution").path("option").asText() + " ("
                                + a.path("resolution").path("source").asText() + ")" : "**open**").append(" |\n"));
            }
            list(md, "Non-functional requirements", r.path("nonFunctional"), n -> n.path("id").asText() + " " + n.path("text").asText()
                    + " _(" + n.path("source").asText() + ")_");
            list(md, "Assumptions", r.path("assumptions"), JsonNode::asText);
        });

        content(ctx, "plan").ifPresent(p -> {
            md.append("\n## 2. Plan (task decomposition)\n\n| Task | Capability | Depends on | Files |\n|---|---|---|---|\n");
            p.path("tasks").forEach(t -> md.append("| `").append(t.path("id").asText()).append("` ").append(t.path("title").asText())
                    .append(" | ").append(t.path("capability").asText()).append(" | ").append(t.path("dependsOn")).append(" | ")
                    .append(t.path("files").size()).append(" |\n"));
            md.append("\nParallel waves: ").append(p.path("waves")).append("\n");
            list(md, "Reused existing capabilities", p.path("alreadySatisfied"), s -> s.path("capability").asText());
            list(md, "Excluded", p.path("excluded"), s -> s.path("capability").asText() + ": " + s.path("reason").asText());
        });

        content(ctx, "design").ifPresent(d -> {
            md.append("\n## 3. Design and impact\n\n");
            md.append("- Impacted files: ").append(d.path("impactedFiles").size()).append("\n");
            md.append("- Changed existing types: ").append(d.path("changedExistingTypes")).append("\n");
            md.append("- Ripple (re-verified) types: ").append(d.path("rippleTypes")).append("\n");
            list(md, "API changes", d.path("apiChanges"), JsonNode::asText);
            list(md, "Data changes", d.path("dataChanges"), JsonNode::asText);
            list(md, "Risks", d.path("risks"), JsonNode::asText);
        });

        md.append("\n## 4. Execution\n\n| Stage | Phase | Status | Attempts | Agent | Duration | Notes |\n|---|---|---|---|---|---|---|\n");
        for (String id : graph.topologicalOrder()) {
            StageSpec spec = graph.stage(id);
            StageRecord r = st.stages().get(id);
            if (r == null) {
                continue;
            }
            StringBuilder notes = new StringBuilder();
            if (r.usedFallback()) {
                notes.append("fallback; ");
            }
            if (r.reExecutions() > 0) {
                notes.append("re-executed x").append(r.reExecutions()).append("; ");
            }
            if (r.approval() != null) {
                notes.append("approved: ").append(r.approval()).append("; ");
            }
            if (!r.pendingCheckpoints().isEmpty()) {
                notes.append("awaiting: ").append(r.pendingCheckpoints().stream().map(StageRecord.Checkpoint::checkpoint).toList()).append("; ");
            }
            if (r.lastError() != null) {
                notes.append(r.status() == com.example.agentic.engine.StageStatus.SUCCEEDED ? "recovered from: " : "error: ")
                        .append(r.lastError().replace("|", "/")).append("; ");
            }
            md.append("| `").append(id).append("` | ").append(spec.phase()).append(" | ").append(r.status()).append(" | ")
                    .append(r.attempts()).append(" | ").append(Optional.ofNullable(r.agentUsed()).orElse("")).append(" | ")
                    .append(r.durationMillis() == null ? "" : r.durationMillis() + " ms").append(" | ").append(notes).append(" |\n");
        }

        md.append("\n## 5. Decisions (lineage)\n\n| Id | Stage | Actor | Decision | Rationale | Based on |\n|---|---|---|---|---|---|\n");
        for (Decision d : st.decisions()) {
            md.append("| ").append(d.id()).append(" | ").append(Optional.ofNullable(d.stage()).orElse("-")).append(" | ").append(d.actor())
                    .append(" | ").append(esc(d.summary())).append(" | ").append(esc(d.rationale())).append(" | ")
                    .append(d.basedOn().entrySet().stream().map(e -> e.getKey() + "#" + abbreviate(e.getValue())).toList()).append(" |\n");
        }

        md.append("\n## 6. Validation\n\n");
        content(ctx, "test_report").ifPresent(t -> md.append("- Tests: ").append(t.path("testsRun").asInt()).append(" run, ")
                .append(t.path("failures").asInt()).append(" failed, compiled=").append(t.path("compiled").asBoolean())
                .append(t.has("compileErrors") ? ", errors: " + t.path("compileErrors") : "")
                .append(t.has("failedTests") ? ", failed: " + t.path("failedTests") : "").append("\n"));
        content(ctx, "security_report").ifPresent(s -> {
            md.append("- Security scan: ").append(s.path("critical").asInt()).append(" critical, ").append(s.path("high").asInt()).append(" high\n");
            s.path("findings").forEach(f -> md.append("  - ").append(f.asText()).append('\n'));
        });
        content(ctx, "release_readiness").ifPresent(r -> {
            md.append("\n| Release check | Result | Detail |\n|---|---|---|\n");
            r.path("checks").forEach(c -> md.append("| ").append(c.path("name").asText()).append(" | ")
                    .append(c.path("passed").asBoolean() ? "PASS" : "**FAIL**").append(" | ").append(esc(c.path("detail").asText())).append(" |\n"));
            md.append("\n| Capability | Requirements | Tasks | Tests | Status |\n|---|---|---|---|---|\n");
            r.path("traceability").forEach(t -> md.append("| ").append(t.path("capability").asText()).append(" | ")
                    .append(t.path("requirements")).append(" | ").append(t.path("tasks")).append(" | ")
                    .append(t.path("tests").size()).append(" | ").append(esc(t.path("status").asText())).append(" |\n"));
        });
        if (ws.hasSnapshot("baseline")) {
            var diff = ws.diffSummary("baseline");
            md.append("\n**Workspace diff vs baseline:** ").append(diff.size()).append(" files\n\n");
            diff.stream().limit(60).forEach(row -> md.append("- ").append(row.get("change")).append(" `").append(row.get("path"))
                    .append("` (+").append(row.get("addedLines")).append(" / -").append(row.get("removedLines")).append(")\n"));
        }
        content(ctx, "release").ifPresent(r -> md.append("\n**Release package:** `release/` (").append(r.path("patch").asText()).append(")\n"));

        md.append("\n## 7. Reliability metrics\n\n```json\n").append(Json.pretty(Metrics.forRun(st).without("stages"))).append("\n```\n");
        md.append("\nFull event trail: `audit.jsonl` (hash-chained; verify with `./agentic verify-audit ").append(st.runId()).append("`).\n");
        Json.writeAtomically(runDir.resolve("REPORT.md"), md.toString());
    }

    private static Optional<JsonNode> content(ContextStore ctx, String key) {
        return ctx.get(key).map(a -> a.content());
    }

    private static void list(StringBuilder md, String title, JsonNode items, java.util.function.Function<JsonNode, String> f) {
        if (items == null || items.isEmpty()) {
            return;
        }
        md.append("\n**").append(title).append("**\n\n");
        items.forEach(i -> md.append("- ").append(f.apply(i)).append('\n'));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }

    private static String abbreviate(String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) : hash;
    }
}
