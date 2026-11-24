package com.example.agentic.agents;

import com.example.agentic.engine.Json;
import com.example.agentic.engine.Orchestrator;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.PolicyFinding;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Security and compliance scan of the complete code diff against the baseline (independent of the
 * per-task checks, so nothing that bypassed a task still reaches release). CRITICAL findings fail
 * the gate; HIGH findings must already have been approved at the task checkpoint that introduced them
 * and are listed for the release reviewer.
 */
public final class SecurityScanAgent implements Agent {

    @Override
    public String name() {
        return "security-scan";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws AgentException {
        ctx.input("design"); // lineage
        List<Map<String, Object>> diff = ctx.workspace().diffSummary(Orchestrator.BASELINE);
        List<PolicyEngine.FileChange> changes = new ArrayList<>();
        List<String> binaries = new ArrayList<>();
        for (Map<String, Object> row : diff) {
            String path = (String) row.get("path");
            if (!path.startsWith("src/")) {
                continue; // code only: docs are generated in a parallel branch and carry no executable risk
            }
            if (Boolean.TRUE.equals(row.get("binary"))) {
                binaries.add(path); // cannot be pattern-scanned; listed for the release reviewer
                continue;
            }
            boolean deleted = "deleted".equals(row.get("change"));
            changes.add(new PolicyEngine.FileChange(path, deleted ? "delete" : "modify",
                    deleted ? null : ctx.workspace().read(path), ((Number) row.get("addedLines")).intValue()));
        }
        List<PolicyFinding> findings = new ArrayList<>(ctx.policy().evaluateChange(changes).stream()
                // blast radius is a per-change-set control, already enforced at each task
                .filter(f -> !f.ruleId().startsWith("CHG-006")).toList());
        long critical = findings.stream().filter(f -> f.severity() == PolicyFinding.Severity.CRITICAL).count();
        long high = findings.stream().filter(f -> f.severity() == PolicyFinding.Severity.HIGH).count();
        ObjectNode report = Json.object();
        report.put("filesScanned", changes.size());
        report.put("critical", critical);
        report.put("high", high);
        report.set("binaryFilesNotScanned", Json.tree(binaries));
        report.set("findings", Json.tree(findings.stream().map(PolicyFinding::toString).toList()));
        report.set("rules", Json.tree(List.of("hard-coded secrets", "process execution", "unsafe deserialization",
                "weak crypto / randomness", "TLS bypass", "personal data", "path scope", "change-controlled files", "test deletion")));
        AgentResult r = new AgentResult(changes.size() + " changed source files scanned: " + critical + " critical, " + high + " high")
                .artifact("security_report", report);
        // Only blocking findings are raised to the orchestrator here; HIGH ones were gated per task.
        findings.stream().filter(PolicyFinding::blocking).forEach(r::finding);
        return r;
    }
}
