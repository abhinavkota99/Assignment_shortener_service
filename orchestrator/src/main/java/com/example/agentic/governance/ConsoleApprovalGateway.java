package com.example.agentic.governance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Interactive approvals on a terminal. Falls back to another gateway first (e.g. recorded decisions),
 * and returns PENDING if no human answers, so a closed stdin can never be read as consent.
 */
public final class ConsoleApprovalGateway implements Approval.Gateway {

    private final Approval.Gateway recorded;
    private final BufferedReader in;
    private final PrintStream out;
    private final String approver;

    public ConsoleApprovalGateway(Approval.Gateway recorded, BufferedReader in, PrintStream out, String approver) {
        this.recorded = recorded;
        this.in = in;
        this.out = out;
        this.approver = approver;
    }

    @Override
    public synchronized Approval.Decision decide(Approval.Request request) {
        Approval.Decision prior = recorded.decide(request);
        if (prior.outcome() != Approval.Outcome.PENDING) {
            return prior;
        }
        out.println();
        out.println("=== APPROVAL REQUIRED: " + request.key() + " ===");
        out.println(request.summary());
        request.reasons().forEach(r -> out.println("  - " + r));
        out.print("Approve? [y]es / [n]o / [s]kip (decide later): ");
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                return Approval.Decision.pending("no interactive input available");
            }
            String answer = line.trim().toLowerCase(Locale.ROOT);
            if (answer.startsWith("y")) {
                return new Approval.Decision(Approval.Outcome.APPROVED, approver, "approved interactively", System.currentTimeMillis());
            }
            if (answer.startsWith("n")) {
                out.print("Reason for rejection: ");
                out.flush();
                String reason = in.readLine();
                return new Approval.Decision(Approval.Outcome.REJECTED, approver, reason == null ? "" : reason, System.currentTimeMillis());
            }
            return Approval.Decision.pending("deferred by operator");
        } catch (IOException e) {
            return Approval.Decision.pending("cannot read approval: " + e.getMessage());
        }
    }
}
