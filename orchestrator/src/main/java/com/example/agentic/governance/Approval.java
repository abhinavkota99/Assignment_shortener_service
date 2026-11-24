package com.example.agentic.governance;

import java.util.List;

/** Types for human approval checkpoints. */
public final class Approval {

    private Approval() {
    }

    /** What a human is asked to approve, with the evidence (artifact hashes) the decision is based on. */
    public record Request(String runId, String stageId, String checkpoint, String summary, List<String> reasons,
                          List<String> evidence) {

        public String key() {
            return stageId + "/" + checkpoint;
        }
    }

    public enum Outcome { APPROVED, REJECTED, PENDING }

    public record Decision(Outcome outcome, String approver, String comment, long atMillis) {

        public static Decision pending(String why) {
            return new Decision(Outcome.PENDING, null, why, System.currentTimeMillis());
        }
    }

    /** Source of human decisions. Implementations must never approve on their own. */
    public interface Gateway {
        Decision decide(Request request);
    }
}
