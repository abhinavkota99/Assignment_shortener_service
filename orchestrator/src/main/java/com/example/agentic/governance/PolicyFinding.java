package com.example.agentic.governance;

/**
 * A guardrail result. Severity drives the control response:
 * <ul>
 *   <li>INFO/WARN: recorded in the audit trail and report only</li>
 *   <li>HIGH: allowed only after a human approves the named {@code checkpoint}</li>
 *   <li>CRITICAL: never allowed; the run safe-stops and rolls back</li>
 * </ul>
 */
public record PolicyFinding(String ruleId, Severity severity, String checkpoint, String location, String message) {

    public enum Severity { INFO, WARN, HIGH, CRITICAL }

    public boolean blocking() {
        return severity == Severity.CRITICAL;
    }

    public boolean requiresApproval() {
        return severity == Severity.HIGH;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + ruleId + " @ " + location + ": " + message;
    }
}
