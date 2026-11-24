package com.example.agentic.engine;

import com.example.agentic.agents.AgentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic chaos for demonstrating and testing recovery paths.
 * Spec: {@code <stage-prefix>:<mode>[:<times>]}, e.g. {@code implement:T-api:transient:2}.
 * <ul>
 *   <li>{@code transient}: the stage throws a retryable error the first N times</li>
 *   <li>{@code crash}: the primary agent fails permanently (exercises fallback / failure handling)</li>
 *   <li>{@code corrupt}: the stage succeeds but leaves a compile error in a file it changed
 *       (exercises verification gates and rollback)</li>
 * </ul>
 */
public final class FaultInjector {

    public enum Mode { TRANSIENT, CRASH, CORRUPT }

    private record Fault(String stagePrefix, Mode mode, int times, AtomicInteger fired) { }

    private final List<Fault> faults = new ArrayList<>();
    private final Map<String, Boolean> corrupted = new ConcurrentHashMap<>();

    public static FaultInjector none() {
        return new FaultInjector(List.of());
    }

    public FaultInjector(List<String> specs) {
        for (String spec : specs) {
            int last = spec.lastIndexOf(':');
            String head = spec;
            int times = 1;
            if (last > 0 && spec.substring(last + 1).chars().allMatch(Character::isDigit)) {
                times = Integer.parseInt(spec.substring(last + 1));
                head = spec.substring(0, last);
            }
            int modeSep = head.lastIndexOf(':');
            if (modeSep <= 0) {
                throw new IllegalArgumentException("fault spec must be <stage>:<mode>[:<times>], got " + spec);
            }
            Mode mode = Mode.valueOf(head.substring(modeSep + 1).toUpperCase(java.util.Locale.ROOT));
            faults.add(new Fault(head.substring(0, modeSep), mode, times, new AtomicInteger()));
        }
    }

    /** Called before the agent runs. */
    public void beforeExecute(String stageId, boolean fallback) throws AgentException {
        for (Fault f : faults) {
            if (!stageId.startsWith(f.stagePrefix())) {
                continue;
            }
            if (f.mode() == Mode.TRANSIENT && f.fired().get() < f.times()) {
                f.fired().incrementAndGet();
                throw AgentException.transientFailure("injected transient fault (" + f.fired().get() + "/" + f.times() + ")");
            }
            if (f.mode() == Mode.CRASH && !fallback && f.fired().get() < f.times()) {
                f.fired().incrementAndGet();
                throw AgentException.permanent("injected crash of primary agent");
            }
        }
    }

    /** Whether to corrupt this stage's output (once per stage). */
    public boolean shouldCorrupt(String stageId) {
        for (Fault f : faults) {
            if (f.mode() == Mode.CORRUPT && stageId.startsWith(f.stagePrefix()) && corrupted.putIfAbsent(stageId, true) == null) {
                return true;
            }
        }
        return false;
    }

    public boolean active() {
        return !faults.isEmpty();
    }
}
