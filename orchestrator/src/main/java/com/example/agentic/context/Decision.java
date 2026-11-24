package com.example.agentic.context;

import java.util.List;
import java.util.Map;

/**
 * One entry of decision lineage: what was decided, by whom (agent or human), why, which
 * alternatives were rejected, and exactly which artifact versions (by hash) it was based on.
 */
public record Decision(
        String id,
        String stage,
        String actor,
        String summary,
        String rationale,
        List<String> alternatives,
        Map<String, String> basedOn,
        long atMillis) {

    public Decision {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        basedOn = basedOn == null ? Map.of() : Map.copyOf(basedOn);
    }
}
