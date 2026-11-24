package com.example.agentic.governance;

import com.example.agentic.engine.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Reads recorded human decisions from a JSON file, the non-interactive path used in CI and for
 * asynchronous review (a reviewer edits the file, the run is resumed).
 *
 * <pre>
 * { "decisions": [
 *     { "checkpoint": "design/design-review", "decision": "approve", "approver": "jane.doe", "comment": "..." },
 *     { "checkpoint": "*&#47;privacy-review", "decision": "reject",  "approver": "dpo.team",  "comment": "..." } ] }
 * </pre>
 * A checkpoint key is {@code <stageId>/<checkpoint>}; {@code *} matches any stage. Exact keys win.
 * Agents and blank identities are refused as approvers (separation of duties).
 */
public final class FileApprovalGateway implements Approval.Gateway {

    private static final Set<String> FORBIDDEN_APPROVERS = Set.of("", "agent", "orchestrator", "system", "auto");

    private final Path file;

    public FileApprovalGateway(Path file) {
        this.file = file;
    }

    @Override
    public Approval.Decision decide(Approval.Request request) {
        if (file == null || !Files.exists(file)) {
            return Approval.Decision.pending("no approvals file supplied");
        }
        JsonNode root = Json.read(file);
        JsonNode match = null;
        for (JsonNode d : root.path("decisions")) {
            String key = d.path("checkpoint").asText();
            if (key.equals(request.key())) {
                match = d;
                break;
            }
            if (match == null && key.equals("*/" + request.checkpoint())) {
                match = d;
            }
        }
        if (match == null) {
            return Approval.Decision.pending("no decision recorded for " + request.key());
        }
        String approver = match.path("approver").asText("").trim();
        if (FORBIDDEN_APPROVERS.contains(approver.toLowerCase(Locale.ROOT))) {
            return Approval.Decision.pending("decision for " + request.key() + " ignored: approver must be a named human");
        }
        String verdict = match.path("decision").asText("").toLowerCase(Locale.ROOT);
        Approval.Outcome outcome = switch (verdict) {
            case "approve", "approved" -> Approval.Outcome.APPROVED;
            case "reject", "rejected" -> Approval.Outcome.REJECTED;
            default -> Approval.Outcome.PENDING;
        };
        return new Approval.Decision(outcome, approver, match.path("comment").asText(""), System.currentTimeMillis());
    }
}
