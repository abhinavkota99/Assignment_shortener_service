package com.example.agentic.engine;

import com.example.agentic.agents.Agent;
import com.example.agentic.agents.AgentContext;
import com.example.agentic.agents.AgentException;
import com.example.agentic.agents.AgentRegistry;
import com.example.agentic.agents.AgentResult;
import com.example.agentic.context.Artifact;
import com.example.agentic.context.ContextStore;
import com.example.agentic.context.Decision;
import com.example.agentic.governance.Approval;
import com.example.agentic.governance.PolicyEngine;
import com.example.agentic.governance.PolicyFinding;
import com.example.agentic.knowledge.Catalog;
import com.example.agentic.knowledge.Scenario;
import com.example.agentic.llm.LlmClient;
import com.example.agentic.observability.AuditLog;
import com.example.agentic.observability.Metrics;
import com.example.agentic.observability.RunReport;
import com.example.agentic.workflow.StageSpec;
import com.example.agentic.workflow.WorkflowGraph;
import com.example.agentic.workspace.FileOp;
import com.example.agentic.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Governed, stateful executor of the SDLC workflow graph.
 *
 * <p>A single scheduler thread owns all run state; agents execute on a bounded worker pool and only
 * return proposals. Each loop iteration:
 * <ol>
 *   <li>checks safe-stop conditions (kill switch, attempt and time budgets)</li>
 *   <li>invalidates any finished stage whose consumed inputs changed, and everything downstream of it
 *       (dynamic re-planning; mutated workspace state is rolled back first)</li>
 *   <li>expands fan-out stages from the current plan (one stage per task, task dependencies preserved)</li>
 *   <li>re-asks pending human checkpoints once per session</li>
 *   <li>launches every ready stage (dependencies succeeded, entry gates and pre-approvals passed) up to
 *       the parallelism limit, so independent branches run concurrently and multi-dependency stages
 *       synchronise them</li>
 *   <li>accepts or rejects one completed result: retry (bounded, exponential backoff), fallback agent,
 *       exit gates, policy guardrails, human checkpoints, commit to the context store</li>
 * </ol>
 * Runs pause (and can be resumed) when they need human input or approval; they fail or safe-stop with
 * a rollback of the workspace to the baseline checkpoint. Every transition is persisted and audited.
 */
public final class Orchestrator {

    public static final String BASELINE = "baseline";
    static final String CLARIFICATIONS = "human.clarifications";
    static final String CONSTRAINTS = "human.constraints";
    /** Phases whose agents can adapt to a human rejection by re-planning (others fail the run). */
    private static final Set<StageSpec.Phase> ADAPTABLE = EnumSet.of(
            StageSpec.Phase.REQUIREMENTS, StageSpec.Phase.PLANNING, StageSpec.Phase.DESIGN);

    /** Execution limits. Bounded everything: parallelism, retries, re-plans, attempts and active time. */
    public record Options(int maxParallel, int maxTotalAttempts, long maxActiveMillis, int maxReExecutions,
                          long maxBackoffMillis, long pollMillis) {
        public static Options defaults() {
            return new Options(4, 80, TimeUnit.MINUTES.toMillis(20), 3, 5_000, 50);
        }
    }

    private final Path repoRoot;
    private final Path runsDir;
    private final Catalog catalog;
    private final AgentRegistry agents;
    private final PolicyEngine policy;
    private final Approval.Gateway approvals;
    private final LlmClient llm;
    private final FaultInjector faults;
    private final Options options;
    private final LongSupplier clock;
    private final PrintStream console;

    public Orchestrator(Path repoRoot, Path runsDir, Catalog catalog, AgentRegistry agents, PolicyEngine policy,
                        Approval.Gateway approvals, LlmClient llm, FaultInjector faults, Options options,
                        LongSupplier clock, PrintStream console) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.runsDir = runsDir.toAbsolutePath().normalize();
        this.catalog = catalog;
        this.agents = agents;
        this.policy = policy;
        this.approvals = approvals;
        this.llm = llm;
        this.faults = faults;
        this.options = options;
        this.clock = clock;
        this.console = console;
    }

    // == public API ===============================================================================

    public RunState start(Scenario scenario, String runId) {
        Path runDir = runsDir.resolve(runId);
        if (Files.exists(runDir.resolve("state.json"))) {
            throw new IllegalStateException("run " + runId + " already exists; use resume");
        }
        WorkflowGraph graph = WorkflowGraph.fromResource(scenario.workflow());
        graph.stages().forEach(s -> {
            Gates.requireKnown(s.entryGates());
            Gates.requireKnown(s.exitGates());
            if (!s.isFanOut()) {
                agents.get(s.agent());
            }
        });
        RunState state = new RunState();
        state.runId = runId;
        state.scenarioId = scenario.id();
        state.scenarioDir = scenario.dir().toString();
        state.workflow = scenario.workflow();
        state.createdAtMillis = clock.getAsLong();
        graph.stages().forEach(s -> state.stage(s.id()));

        Workspace ws = new Workspace(runDir.resolve("workspace"), runDir);
        if (scenario.greenfield()) {
            ws.initEmpty();
        } else {
            Path baseline = repoRoot.resolve(scenario.baseline()).normalize();
            if (!baseline.startsWith(repoRoot) || !Files.isDirectory(baseline)) {
                throw new IllegalArgumentException("baseline is not a directory inside the repository: " + scenario.baseline());
            }
            ws.initFrom(baseline);
        }
        ws.snapshot(BASELINE);
        state.save(runDir);

        Session s = new Session(runDir, state, graph, scenario, ws);
        s.audit.record("RUN_STARTED", null, "orchestrator", runInfo(scenario, graph));
        return execute(s);
    }

    /** Resumes a paused run, optionally supplying answers to clarification questions. */
    public RunState resume(String runId, Path answersFile) {
        return resume(runId, answersFile, false);
    }

    /**
     * Resumes a paused run. With {@code recoverCrashed}, also resumes a run whose process died while it
     * was RUNNING (the operator asserts no other process owns it): in-flight stages are undone from their
     * persisted undo logs and rescheduled.
     */
    public RunState resume(String runId, Path answersFile, boolean recoverCrashed) {
        Path runDir = runsDir.resolve(runId);
        RunState state = RunState.load(runDir);
        boolean crashed = state.status == RunStatus.RUNNING;
        if (!state.status.resumable() && !(crashed && recoverCrashed)) {
            throw new IllegalStateException("run " + runId + " is " + state.status + " and cannot be resumed"
                    + (crashed ? " (use --recover if its process is no longer running)" : ""));
        }
        Scenario scenario = Scenario.load(Path.of(state.scenarioDir));
        WorkflowGraph graph = WorkflowGraph.fromResource(state.workflow);
        state.expansions.forEach((placeholder, tasks) -> graph.expand(placeholder, tasks));
        Workspace ws = new Workspace(runDir.resolve("workspace"), runDir);
        state.status = RunStatus.RUNNING;
        state.resumes++;
        Session s = new Session(runDir, state, graph, scenario, ws);
        s.audit.record("RUN_RESUMED", null, "orchestrator", "resume #" + state.resumes);
        if (crashed) {
            for (String id : graph.topologicalOrder()) {
                StageRecord rec = state.stage(id);
                if (rec.status == StageStatus.RUNNING || rec.status == StageStatus.RETRY_WAIT) {
                    if (graph.stage(id).mutatesWorkspace()) {
                        ws.undo(id); // persisted before every write, so this is exact even after a crash
                    }
                    rec.status = StageStatus.PENDING;
                    s.audit.record("CRASH_RECOVERY", id, "orchestrator", "interrupted attempt undone and rescheduled");
                }
            }
        }
        if (answersFile != null) {
            JsonNode answers = Json.read(answersFile);
            String by = answers.path("answeredBy").asText("").trim();
            if (by.isEmpty()) {
                throw new IllegalArgumentException("answers file must name who answered (answeredBy)");
            }
            Artifact a = s.context.put(CLARIFICATIONS, "human:" + by, answers);
            s.audit.record("HUMAN_INPUT", null, by, artifactEvent(a));
            decide(s, null, "human:" + by, "Clarified ambiguous requirements: " + Json.compact(answers.path("answers")),
                    answers.path("notes").asText("answers supplied by stakeholder"), List.of(), Map.of(CLARIFICATIONS, a.sha256()));
        }
        return execute(s);
    }

    // == run loop ================================================================================

    private RunState execute(Session s) {
        long activeStart = clock.getAsLong();
        ExecutorService pool = Executors.newFixedThreadPool(options.maxParallel(), r -> {
            Thread t = new Thread(r, "agent-worker");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Outcome> completions = new ExecutorCompletionService<>(pool);
        try {
            while (true) {
                if (s.terminal == null) {
                    checkSafeStop(s, activeStart);
                }
                if (s.terminal == null) {
                    invalidateStale(s);
                    askPendingApprovals(s);
                    expandFanOuts(s);
                    for (String id : readyStages(s)) {
                        if (s.inflight.size() >= options.maxParallel() || s.terminal != null) {
                            break;
                        }
                        launch(s, id, completions);
                    }
                }
                if (s.inflight.isEmpty() && (s.terminal != null || !hasPendingWork(s))) {
                    break;
                }
                Future<Outcome> done = completions.poll(options.pollMillis(), TimeUnit.MILLISECONDS);
                if (done != null) {
                    handle(s, done);
                }
                checkTimeouts(s);
            }
            finish(s);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.terminal = RunStatus.SAFE_STOPPED;
            s.reason = "orchestrator interrupted";
            finish(s);
        } catch (RuntimeException e) {
            // Fail closed: any orchestrator fault (e.g. audit write failure) stops the run safely.
            s.terminal = RunStatus.SAFE_STOPPED;
            s.reason = "orchestrator error: " + e;
            try {
                finish(s);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        } finally {
            pool.shutdownNow();
            s.state.activeMillis += clock.getAsLong() - activeStart;
            s.state.save(s.runDir);
            ObjectNode metrics = Metrics.forRun(s.state);
            Metrics.appendHistory(runsDir, metrics);
            Json.writeAtomically(s.runDir.resolve("metrics.json"), Json.pretty(metrics));
            s.audit.record(s.state.status.resumable() ? "RUN_PAUSED" : "RUN_FINISHED", null, "orchestrator", metrics);
            s.audit.close();
            RunReport.write(s.runDir, s.state, s.context, s.graph, s.workspace, AuditLog.verify(s.runDir.resolve("audit.jsonl")));
            log("run %s -> %s%s", s.state.runId, s.state.status, s.state.stopReason == null ? "" : " (" + s.state.stopReason + ")");
        }
        return s.state;
    }

    private boolean hasPendingWork(Session s) {
        for (String id : s.graph.topologicalOrder()) {
            StageStatus st = s.state.stage(id).status;
            if (st == StageStatus.RETRY_WAIT) {
                return true;
            }
        }
        return !readyStagesPeek(s).isEmpty();
    }

    // == scheduling ==============================================================================

    private List<String> readyStages(Session s) {
        List<String> ready = new ArrayList<>();
        long now = clock.getAsLong();
        for (String id : s.graph.topologicalOrder()) {
            StageSpec spec = s.graph.stage(id);
            StageRecord rec = s.state.stage(id);
            boolean waiting = rec.status == StageStatus.PENDING
                    || (rec.status == StageStatus.RETRY_WAIT && rec.retryNotBeforeMillis != null && rec.retryNotBeforeMillis <= now)
                    || (rec.status == StageStatus.AWAITING_APPROVAL && rec.pendingCheckpoints.isEmpty()
                        && spec.approval() == StageSpec.Approval.ALWAYS && !s.asked.contains(id));
            if (!waiting || !depsSucceeded(s, spec) || (spec.isFanOut() && !expansionCurrent(s, spec))) {
                continue;
            }
            List<Gates.Result> entry = Gates.evaluate(spec.entryGates(), new Gates.Input(id, null, s.context, s.workspace));
            Optional<Gates.Result> failed = entry.stream().filter(r -> r.verdict() != Gates.Verdict.PASS).findFirst();
            entry.forEach(r -> s.audit.record("ENTRY_GATE", id, "orchestrator", r.toString()));
            if (failed.isPresent()) {
                failStage(s, id, "entry gate failed: " + failed.get());
                continue;
            }
            if (spec.approval() == StageSpec.Approval.ALWAYS && rec.approval == null && !preApprove(s, spec, rec)) {
                continue;
            }
            ready.add(id);
        }
        return ready;
    }

    /** Side-effect free check used to decide whether the loop can still make progress. */
    private List<String> readyStagesPeek(Session s) {
        List<String> ready = new ArrayList<>();
        for (String id : s.graph.topologicalOrder()) {
            StageSpec spec = s.graph.stage(id);
            StageRecord rec = s.state.stage(id);
            if (rec.status != StageStatus.PENDING) {
                continue;
            }
            boolean runnable = spec.isFanOut() && !expansionCurrent(s, spec)
                    ? spec.baseDependencies().stream().allMatch(d -> s.state.stage(d).status == StageStatus.SUCCEEDED)
                    : depsSucceeded(s, spec);
            if (runnable) {
                ready.add(id); // includes fan-outs that still need (re-)expansion
            }
        }
        return ready;
    }

    private boolean depsSucceeded(Session s, StageSpec spec) {
        return spec.dependsOn().stream().allMatch(d -> s.state.stage(d).status == StageStatus.SUCCEEDED);
    }

    private boolean expansionCurrent(Session s, StageSpec spec) {
        String planKey = spec.fanOut().split("\\.")[0];
        Optional<String> hash = s.context.hash(planKey);
        return hash.isPresent() && hash.get().equals(s.state.expansionHashes.get(spec.id()));
    }

    /** Pre-action approval for ALWAYS stages (e.g. release): asked before the agent may act. */
    private boolean preApprove(Session s, StageSpec spec, StageRecord rec) {
        s.asked.add(spec.id());
        List<String> evidence = new ArrayList<>();
        s.context.index().forEach((k, ref) -> evidence.add(k + "@v" + ref.version() + "#" + ref.sha256().substring(0, 12)));
        Approval.Request req = new Approval.Request(s.state.runId, spec.id(), spec.id(),
                "Approve stage '" + spec.id() + "' (" + spec.phase() + "): " + Optional.ofNullable(spec.description()).orElse(""),
                readinessReasons(s), evidence);
        if (rec.status != StageStatus.AWAITING_APPROVAL) {
            s.state.increment("approvalsRequested");
        }
        s.audit.record("APPROVAL_REQUESTED", spec.id(), "orchestrator", Json.tree(req));
        Approval.Decision d = approvals.decide(req);
        s.audit.record("APPROVAL_DECIDED", spec.id(), d.approver() == null ? "nobody" : d.approver(), Json.tree(d));
        switch (d.outcome()) {
            case APPROVED -> {
                rec.approval = spec.id() + " by " + d.approver();
                rec.status = StageStatus.PENDING;
                decide(s, spec.id(), "human:" + d.approver(), "Approved " + req.key(), d.comment(), List.of(), s.context.hash("release_readiness")
                        .map(h -> Map.of("release_readiness", h)).orElse(Map.of()));
                return true;
            }
            case REJECTED -> {
                s.state.increment("approvalsRejected");
                decide(s, spec.id(), "human:" + d.approver(), "Rejected " + req.key(), d.comment(), List.of(), Map.of());
                rec.status = StageStatus.FAILED;
                rec.lastError = "rejected by " + d.approver() + ": " + d.comment();
                terminate(s, RunStatus.REJECTED, spec.id() + " rejected by " + d.approver());
                return false;
            }
            default -> {
                rec.status = StageStatus.AWAITING_APPROVAL;
                writeApprovalRequest(s, req);
                save(s);
                return false;
            }
        }
    }

    private List<String> readinessReasons(Session s) {
        List<String> reasons = new ArrayList<>();
        s.context.get("release_readiness").ifPresent(a -> a.content().path("checks")
                .forEach(c -> reasons.add((c.path("passed").asBoolean() ? "PASS " : "FAIL ") + c.path("name").asText()
                        + ": " + c.path("detail").asText())));
        return reasons;
    }

    private void launch(Session s, String id, CompletionService<Outcome> completions) {
        StageSpec spec = s.graph.stage(id);
        StageRecord rec = s.state.stage(id);
        String agentName = spec.isFanOut() ? AgentRegistry.JOIN : rec.usedFallback ? spec.fallbackAgent() : spec.agent();
        Agent agent = agents.get(agentName);
        rec.status = StageStatus.RUNNING;
        rec.attempts++;
        rec.agentAttempts++;
        rec.agentUsed = agentName;
        rec.retryNotBeforeMillis = null;
        if (rec.startedAtMillis == null) {
            rec.startedAtMillis = clock.getAsLong();
        }
        s.state.increment("attempts");
        AgentContext ctx = new AgentContext(s.state.runId, spec, rec.attempts, s.context, s.workspace, s.scenario, catalog,
                policy, llm, repoRoot, s.runDir, sid -> s.state.stages.containsKey(sid) ? s.state.stage(sid).status : null);
        ObjectNode ev = Json.object();
        ev.put("agent", agentName);
        ev.put("attempt", rec.attempts);
        ev.put("fallback", rec.usedFallback);
        s.audit.record("STAGE_STARTED", id, agentName, ev);
        save(s);
        boolean fallback = rec.usedFallback;
        long started = System.nanoTime();
        Future<Outcome> f = completions.submit(() -> {
            try {
                faults.beforeExecute(id, fallback);
                AgentResult result = agent.execute(ctx);
                if (spec.mutatesWorkspace() && faults.shouldCorrupt(id)) {
                    corrupt(s.workspace, id, result);
                }
                return new Outcome(id, result, null, ctx.consumed(), elapsed(started));
            } catch (AgentException e) {
                return new Outcome(id, null, e, ctx.consumed(), elapsed(started));
            } catch (RuntimeException | Error e) {
                return new Outcome(id, null, new AgentException(AgentException.Kind.PERMANENT, "agent crashed: " + e, e),
                        ctx.consumed(), elapsed(started));
            }
        });
        s.inflight.put(id, new InFlight(f, clock.getAsLong() + TimeUnit.SECONDS.toMillis(spec.timeoutSeconds())));
    }

    // == completion handling ===================================================================

    private void handle(Session s, Future<Outcome> done) {
        Outcome o;
        try {
            o = done.get();
        } catch (ExecutionException | InterruptedException | java.util.concurrent.CancellationException e) {
            return; // cancelled by timeout handling, already accounted for
        }
        if (s.inflight.remove(o.stageId()) == null) {
            return; // result of a timed-out attempt that was already abandoned
        }
        StageRecord rec = s.state.stage(o.stageId());
        rec.attemptDurationsMillis.add(o.millis());
        if (o.error() != null) {
            onFailure(s, o.stageId(), o.error());
        } else {
            onResult(s, o.stageId(), o.result(), o.consumed());
        }
        save(s);
    }

    private void onFailure(Session s, String id, AgentException e) {
        StageSpec spec = s.graph.stage(id);
        StageRecord rec = s.state.stage(id);
        long now = clock.getAsLong();
        rec.lastError = e.getMessage();
        ObjectNode ev = Json.object();
        ev.put("kind", e.kind().name());
        ev.put("error", String.valueOf(e.getMessage()));
        ev.put("attempt", rec.attempts);
        s.audit.record("STAGE_ATTEMPT_FAILED", id, rec.agentUsed, ev);
        if (spec.mutatesWorkspace()) {
            s.workspace.undo(id); // never leave a half-done change behind between attempts
        }
        if (s.terminal != null) {
            rec.status = StageStatus.SKIPPED;
            return;
        }
        if (e.kind() != AgentException.Kind.UNAVAILABLE && rec.firstFailureAtMillis == null) {
            rec.firstFailureAtMillis = now;
        }
        if (e.kind() == AgentException.Kind.TRANSIENT && rec.agentAttempts < spec.maxAttempts()) {
            long backoff = Math.min(options.maxBackoffMillis(), spec.initialBackoffMillis() * (1L << (rec.agentAttempts - 1)));
            rec.status = StageStatus.RETRY_WAIT;
            rec.retryNotBeforeMillis = now + backoff;
            s.state.increment("retries");
            s.audit.record("RETRY_SCHEDULED", id, "orchestrator", "attempt " + (rec.attempts + 1) + " in " + backoff + " ms");
            log("  %-28s retry in %d ms (%s)", id, backoff, e.getMessage());
            return;
        }
        if (spec.fallbackAgent() != null && !rec.usedFallback) {
            rec.usedFallback = true;
            rec.agentAttempts = 0;
            rec.status = StageStatus.PENDING;
            // a primary that cannot run here (e.g. no LLM configured) is degraded mode, not a failure
            s.state.increment(e.kind() == AgentException.Kind.UNAVAILABLE ? "primaryUnavailable" : "fallbacks");
            s.audit.record("FALLBACK_INVOKED", id, "orchestrator", spec.agent() + " -> " + spec.fallbackAgent() + ": " + e.getMessage());
            log("  %-28s fallback -> %s (%s)", id, spec.fallbackAgent(), e.getMessage());
            return;
        }
        failStage(s, id, e.getMessage());
    }

    private void onResult(Session s, String id, AgentResult result, Map<String, String> consumed) {
        StageSpec spec = s.graph.stage(id);
        StageRecord rec = s.state.stage(id);
        if (s.terminal != null) { // run is stopping: do not accept new work
            if (spec.mutatesWorkspace()) {
                s.workspace.undo(id);
            }
            rec.status = StageStatus.SKIPPED;
            return;
        }
        // 1. exit gates (acceptance criteria) on the proposed output
        List<Gates.Result> gates = Gates.evaluate(spec.exitGates(), new Gates.Input(id, result, s.context, s.workspace));
        rec.gateResults.clear();
        gates.forEach(g -> {
            rec.gateResults.add(g.toString());
            s.audit.record("EXIT_GATE", id, "orchestrator", g.toString());
        });
        if (gates.stream().anyMatch(g -> g.verdict() == Gates.Verdict.NEEDS_INPUT)) {
            commitArtifacts(s, id, result, consumed);
            rec.status = StageStatus.AWAITING_INPUT;
            writeClarifications(s);
            log("  %-28s needs human input (see CLARIFICATIONS.md)", id);
            return;
        }
        Optional<Gates.Result> failedGate = gates.stream().filter(g -> g.verdict() == Gates.Verdict.FAIL).findFirst();
        if (failedGate.isPresent()) {
            commitArtifacts(s, id, result, consumed); // keep the evidence (e.g. failing test report)
            if (spec.mutatesWorkspace()) {
                s.workspace.undo(id);
            }
            failStage(s, id, "exit gate failed: " + failedGate.get());
            return;
        }
        // 2. policy guardrails
        for (PolicyFinding f : result.findings()) {
            s.state.increment("policyFindings");
            s.audit.record("POLICY_FINDING", id, "policy-engine", Json.tree(f));
        }
        if (PolicyEngine.anyBlocking(result.findings())) {
            if (spec.mutatesWorkspace()) {
                s.workspace.undo(id);
            }
            rec.status = StageStatus.FAILED;
            rec.lastError = "blocked by policy: " + PolicyEngine.describe(result.findings().stream().filter(PolicyFinding::blocking).toList());
            terminate(s, RunStatus.SAFE_STOPPED, "critical policy violation in " + id);
            return;
        }
        // 3. accept into shared context (versioned, hashed) with decision lineage
        commitArtifacts(s, id, result, consumed);
        // 4. human checkpoints: every HIGH policy finding (cannot be disabled by workflow config), plus
        //    checkpoints the agent asks for when the stage is configured for impact-based approval
        Map<String, List<String>> checkpoints = new LinkedHashMap<>();
        if (spec.approval() != StageSpec.Approval.NONE) {
            result.checkpoints().forEach(c -> checkpoints.computeIfAbsent(c.checkpoint(), k -> new ArrayList<>()).add(c.reason()));
        }
        result.findings().stream().filter(PolicyFinding::requiresApproval)
                .forEach(f -> checkpoints.computeIfAbsent(f.checkpoint(), k -> new ArrayList<>()).add(f.toString()));
        if (!checkpoints.isEmpty()) {
            rec.pendingCheckpoints.clear();
            checkpoints.forEach((cp, reasons) -> rec.pendingCheckpoints.add(new StageRecord.Checkpoint(cp, String.join(" | ", reasons))));
            rec.status = StageStatus.AWAITING_APPROVAL;
            s.state.increment("approvalsRequested");
            evaluateCheckpoints(s, id);
            return;
        }
        succeed(s, id, result.summary());
    }

    private void commitArtifacts(Session s, String id, AgentResult result, Map<String, String> consumed) {
        StageRecord rec = s.state.stage(id);
        rec.consumed.clear();
        rec.consumed.putAll(consumed);
        rec.produced.clear();
        for (var e : result.artifacts().entrySet()) {
            Artifact a = s.context.put(e.getKey(), id, e.getValue());
            rec.produced.add(e.getKey());
            s.audit.record("ARTIFACT_STORED", id, rec.agentUsed, artifactEvent(a));
        }
        for (AgentResult.DecisionDraft d : result.decisions()) {
            decide(s, id, "agent:" + rec.agentUsed, d.summary(), d.rationale(), d.alternatives(), consumed);
        }
        s.state.artifacts = s.context.index();
    }

    private void evaluateCheckpoints(Session s, String id) {
        s.asked.add(id);
        StageRecord rec = s.state.stage(id);
        List<String> evidence = rec.produced.stream()
                .map(k -> k + "#" + s.context.hash(k).map(h -> h.substring(0, 12)).orElse("?")).toList();
        boolean allApproved = true;
        List<String> approvalsGiven = new ArrayList<>();
        for (StageRecord.Checkpoint cp : List.copyOf(rec.pendingCheckpoints)) {
            Approval.Request req = new Approval.Request(s.state.runId, id, cp.checkpoint(),
                    "Stage '" + id + "' requires " + cp.checkpoint(), List.of(cp.reason().split(" \\| ")), evidence);
            s.audit.record("APPROVAL_REQUESTED", id, "orchestrator", Json.tree(req));
            Approval.Decision d = approvals.decide(req);
            s.audit.record("APPROVAL_DECIDED", id, d.approver() == null ? "nobody" : d.approver(), Json.tree(d));
            if (d.outcome() == Approval.Outcome.REJECTED) {
                onRejected(s, id, cp, d);
                return;
            }
            if (d.outcome() == Approval.Outcome.PENDING) {
                allApproved = false;
                writeApprovalRequest(s, req);
            } else {
                approvalsGiven.add(cp.checkpoint() + " by " + d.approver());
                decide(s, id, "human:" + d.approver(), "Approved " + req.key(), d.comment(), List.of(), rec.consumed);
            }
        }
        if (allApproved) {
            rec.approval = String.join(", ", approvalsGiven);
            rec.pendingCheckpoints.clear();
            succeed(s, id, "approved");
        } else {
            log("  %-28s awaiting approval: %s", id, rec.pendingCheckpoints.stream().map(StageRecord.Checkpoint::checkpoint).toList());
        }
    }

    /**
     * A rejection becomes a recorded constraint. Stages that can adapt (requirements, planning,
     * design) are re-executed with it (re-planning); others fail the run.
     */
    private void onRejected(Session s, String id, StageRecord.Checkpoint cp, Approval.Decision d) {
        StageSpec spec = s.graph.stage(id);
        StageRecord rec = s.state.stage(id);
        s.state.increment("approvalsRejected");
        if (spec.mutatesWorkspace()) {
            s.workspace.undo(id);
        }
        ObjectNode constraints = s.context.get(CONSTRAINTS).map(a -> (ObjectNode) a.content().deepCopy()).orElseGet(Json::object);
        ArrayNode rejections = constraints.withArray("rejections");
        ObjectNode r = rejections.addObject();
        r.put("stage", id);
        r.put("checkpoint", cp.checkpoint());
        r.put("approver", d.approver());
        r.put("comment", d.comment());
        r.put("subjects", cp.reason());
        Artifact a = s.context.put(CONSTRAINTS, "human:" + d.approver(), constraints);
        s.audit.record("ARTIFACT_STORED", id, d.approver(), artifactEvent(a));
        decide(s, id, "human:" + d.approver(), "Rejected " + id + "/" + cp.checkpoint(), d.comment(), List.of(), rec.consumed);

        boolean adaptable = ADAPTABLE.contains(spec.phase());
        if (adaptable && rec.reExecutions < options.maxReExecutions()) {
            rec.reExecutions++;
            s.state.increment("reExecutions");
            rec.reset();
            s.audit.record("REPLAN", id, "orchestrator", "re-executing " + id + " under new human constraint (" + cp.checkpoint() + ")");
            log("  %-28s rejected by %s -> re-planning with constraint", id, d.approver());
        } else {
            rec.status = StageStatus.FAILED;
            rec.lastError = "rejected by " + d.approver() + ": " + d.comment();
            terminate(s, RunStatus.REJECTED, id + "/" + cp.checkpoint() + " rejected by " + d.approver());
        }
    }

    private void succeed(Session s, String id, String summary) {
        StageSpec spec = s.graph.stage(id);
        StageRecord rec = s.state.stage(id);
        rec.status = StageStatus.SUCCEEDED;
        rec.finishedAtMillis = clock.getAsLong();
        if (rec.firstFailureAtMillis != null && rec.recoveredAtMillis == null) {
            rec.recoveredAtMillis = rec.finishedAtMillis;
        }
        if (spec.mutatesWorkspace()) {
            s.workspace.commit(id);
        }
        s.audit.record("STAGE_SUCCEEDED", id, rec.agentUsed, summary);
        log("  %-28s OK   %s%s", id, summary, rec.attempts > 1 ? " [attempts=" + rec.attempts + "]" : "");
    }

    private void failStage(Session s, String id, String reason) {
        StageRecord rec = s.state.stage(id);
        rec.status = StageStatus.FAILED;
        rec.lastError = reason;
        rec.finishedAtMillis = clock.getAsLong();
        s.audit.record("STAGE_FAILED", id, "orchestrator", reason);
        log("  %-28s FAIL %s", id, reason);
        terminate(s, RunStatus.FAILED, id + ": " + reason);
    }

    private void terminate(Session s, RunStatus status, String reason) {
        if (s.terminal == null) {
            s.terminal = status;
            s.reason = reason;
            s.audit.record(status == RunStatus.SAFE_STOPPED ? "SAFE_STOP" : "RUN_TERMINATING", null, "orchestrator", reason);
        }
    }

    // == dynamic re-planning ===================================================================

    /**
     * Any stage whose recorded inputs no longer match the latest artifact versions is stale; it and
     * everything downstream is reset. If stale work already changed the workspace, the workspace is
     * restored to the baseline and all workspace-mutating stages are re-run, so the result is always
     * equivalent to a clean execution of the current plan.
     */
    private void invalidateStale(Session s) {
        Map<String, String> stale = new LinkedHashMap<>();
        for (String id : s.graph.topologicalOrder()) {
            StageRecord rec = s.state.stage(id);
            if (rec.status == StageStatus.PENDING || rec.status == StageStatus.RUNNING || rec.consumed.isEmpty()) {
                continue;
            }
            for (var c : rec.consumed.entrySet()) {
                String current = s.context.hash(c.getKey()).orElse(AgentContext.ABSENT);
                if (!current.equals(c.getValue())) {
                    stale.put(id, c.getKey());
                    break;
                }
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        Set<String> reset = new LinkedHashSet<>();
        stale.keySet().forEach(id -> {
            reset.add(id);
            reset.addAll(s.graph.downstreamOf(id));
        });
        boolean mutatedWorkspace = reset.stream().anyMatch(id -> s.graph.contains(id) && s.graph.stage(id).mutatesWorkspace()
                && s.state.stage(id).attempts > 0);
        if (mutatedWorkspace) {
            s.workspace.restore(BASELINE);
            s.state.increment("rollbacks");
            s.audit.record("ROLLBACK", null, "orchestrator", "workspace restored to baseline before re-executing changed plan");
            for (StageSpec spec : s.graph.stages()) {
                if (spec.mutatesWorkspace() && s.state.stage(spec.id()).attempts > 0) {
                    reset.add(spec.id());
                    reset.addAll(s.graph.downstreamOf(spec.id()));
                }
            }
        }
        for (String id : reset) {
            if (!s.graph.contains(id)) {
                continue;
            }
            StageRecord rec = s.state.stage(id);
            if (rec.status == StageStatus.PENDING && rec.attempts == 0) {
                continue;
            }
            if (rec.reExecutions >= options.maxReExecutions()) {
                failStage(s, id, "re-plan budget exhausted (" + options.maxReExecutions() + " re-executions): inputs keep changing");
                return;
            }
            rec.reExecutions++;
            s.state.increment("reExecutions");
            String cause = stale.containsKey(id) ? "input '" + stale.get(id) + "' changed" : "upstream stage re-executing";
            s.audit.record("STAGE_INVALIDATED", id, "orchestrator", cause);
            log("  %-28s re-plan: %s", id, cause);
            rec.reset();
        }
        save(s);
    }

    private void expandFanOuts(Session s) {
        for (StageSpec spec : s.graph.stages()) {
            if (!spec.isFanOut() || expansionCurrent(s, spec)) {
                continue;
            }
            if (!spec.baseDependencies().stream().allMatch(d -> s.state.stage(d).status == StageStatus.SUCCEEDED)) {
                continue;
            }
            String[] ref = spec.fanOut().split("\\.");
            Optional<Artifact> plan = s.context.get(ref[0]);
            if (plan.isEmpty()) {
                continue;
            }
            List<WorkflowGraph.TaskNode> nodes = new ArrayList<>();
            for (JsonNode t : plan.get().content().path(ref[1])) {
                List<String> deps = new ArrayList<>();
                t.path("dependsOn").forEach(d -> deps.add(d.asText()));
                nodes.add(new WorkflowGraph.TaskNode(t.path("id").asText(), deps, t.path("title").asText()));
            }
            s.graph.generatedBy(spec.id()).forEach(s.state.stages::remove);
            List<String> ids = s.graph.expand(spec.id(), nodes);
            ids.forEach(s.state::stage);
            s.state.expansions.put(spec.id(), nodes);
            s.state.expansionHashes.put(spec.id(), plan.get().sha256());
            StageRecord placeholder = s.state.stage(spec.id());
            placeholder.consumed.put(ref[0], plan.get().sha256());
            ObjectNode ev = Json.object();
            ev.set("stages", Json.tree(ids));
            ev.put("planVersion", plan.get().version());
            s.audit.record("GRAPH_EXPANDED", spec.id(), "orchestrator", ev);
            log("  %-28s expanded into %d parallelisable task stages", spec.id(), ids.size());
            save(s);
        }
    }

    private void askPendingApprovals(Session s) {
        for (String id : s.graph.topologicalOrder()) {
            StageRecord rec = s.state.stage(id);
            if (rec.status == StageStatus.AWAITING_APPROVAL && !rec.pendingCheckpoints.isEmpty() && !s.asked.contains(id)) {
                evaluateCheckpoints(s, id);
                save(s);
                if (s.terminal != null) {
                    return;
                }
            }
        }
    }

    // == safety ==================================================================================

    private void checkSafeStop(Session s, long activeStart) {
        if (Files.exists(s.runDir.resolve("STOP"))) {
            terminate(s, RunStatus.SAFE_STOPPED, "operator kill switch (STOP file) engaged");
        } else if (s.state.counter("attempts") >= options.maxTotalAttempts()) {
            terminate(s, RunStatus.SAFE_STOPPED, "attempt budget exhausted (" + options.maxTotalAttempts() + ")");
        } else if (s.state.activeMillis + clock.getAsLong() - activeStart > options.maxActiveMillis()) {
            terminate(s, RunStatus.SAFE_STOPPED, "time budget exhausted");
        }
    }

    private void checkTimeouts(Session s) {
        long now = clock.getAsLong();
        for (var e : List.copyOf(s.inflight.entrySet())) {
            if (now > e.getValue().deadlineMillis()) {
                e.getValue().future().cancel(true);
                s.inflight.remove(e.getKey());
                onFailure(s, e.getKey(), AgentException.transientFailure("timed out after "
                        + s.graph.stage(e.getKey()).timeoutSeconds() + " s"));
                save(s);
            }
        }
    }

    /** Settles the run outcome. Failed and stopped runs are rolled back to the baseline checkpoint. */
    private void finish(Session s) {
        RunState st = s.state;
        if (s.terminal != null) {
            boolean mutated = s.graph.stages().stream().anyMatch(sp -> sp.mutatesWorkspace() && st.stage(sp.id()).attempts > 0);
            if (mutated && s.terminal != RunStatus.REJECTED) {
                s.workspace.restore(BASELINE);
                st.increment("rollbacks");
                s.audit.record("ROLLBACK", null, "orchestrator", "workspace restored to baseline: " + s.reason);
                for (StageSpec sp : s.graph.stages()) {
                    StageRecord r = st.stage(sp.id());
                    if (sp.mutatesWorkspace() && r.status == StageStatus.SUCCEEDED) {
                        r.status = StageStatus.ROLLED_BACK;
                    }
                }
                log("  rollback: workspace restored to baseline");
            }
            for (StageRecord r : st.stages.values()) {
                if (!r.status.terminal()) {
                    r.status = StageStatus.SKIPPED;
                }
            }
            st.status = s.terminal;
            st.stopReason = s.reason;
            st.finishedAtMillis = clock.getAsLong();
            return;
        }
        boolean allDone = st.stages.values().stream().allMatch(r -> r.status == StageStatus.SUCCEEDED);
        if (allDone) {
            st.status = RunStatus.SUCCEEDED;
            st.stopReason = null;
            st.finishedAtMillis = clock.getAsLong();
        } else if (st.stages.values().stream().anyMatch(r -> r.status == StageStatus.AWAITING_INPUT)) {
            st.status = RunStatus.AWAITING_INPUT;
            st.stopReason = "waiting for answers to clarification questions (see CLARIFICATIONS.md)";
        } else if (st.stages.values().stream().anyMatch(r -> r.status == StageStatus.AWAITING_APPROVAL)) {
            st.status = RunStatus.AWAITING_APPROVAL;
            st.stopReason = "waiting for human approval (see APPROVALS-PENDING.md)";
        } else {
            st.status = RunStatus.FAILED;
            st.stopReason = "no runnable stages remain (scheduler deadlock)";
            st.finishedAtMillis = clock.getAsLong();
        }
    }

    // == helpers =================================================================================

    private void decide(Session s, String stage, String actor, String summary, String rationale, List<String> alternatives,
                        Map<String, String> basedOn) {
        Decision d = new Decision("D-" + (s.state.decisions.size() + 1), stage, actor, summary, rationale, alternatives, basedOn,
                clock.getAsLong());
        s.state.decisions.add(d);
        s.audit.record("DECISION_RECORDED", stage, actor, Json.tree(d));
    }

    private void writeClarifications(Session s) {
        JsonNode req = s.context.content("requirements");
        StringBuilder md = new StringBuilder("# Clarification needed - run ").append(s.state.runId).append("\n\n")
                .append("The requirement is ambiguous in ways that change what gets built. Answer by creating a JSON file "
                        + "like `answers-template.json` and run:\n\n```\n./agentic resume ").append(s.state.runId)
                .append(" --answers <file>\n```\n\n");
        ObjectNode template = Json.object();
        template.put("answeredBy", "<your name>");
        ObjectNode answers = template.putObject("answers");
        for (JsonNode a : req.path("ambiguities")) {
            if (!a.path("blocking").asBoolean() || a.has("resolution")) {
                continue;
            }
            md.append("## ").append(a.path("id").asText()).append(": ").append(a.path("question").asText()).append("\n\n")
                    .append("Triggered by: \"").append(a.path("trigger").asText()).append("\"\n\n");
            for (JsonNode o : a.path("options")) {
                md.append("- `").append(o.path("id").asText()).append("`: ").append(o.path("label").asText())
                        .append(o.path("id").asText().equals(a.path("defaultOption").asText()) ? " *(suggested default)*" : "").append('\n');
            }
            md.append('\n');
            answers.put(a.path("id").asText(), a.path("defaultOption").asText());
        }
        Json.writeAtomically(s.runDir.resolve("CLARIFICATIONS.md"), md.toString());
        Json.writeAtomically(s.runDir.resolve("answers-template.json"), Json.pretty(template));
    }

    private void writeApprovalRequest(Session s, Approval.Request req) {
        Path f = s.runDir.resolve("APPROVALS-PENDING.md");
        StringBuilder md = new StringBuilder();
        try {
            if (Files.exists(f)) {
                md.append(Files.readString(f));
            } else {
                md.append("# Pending approvals - run ").append(s.state.runId).append("\n\nRecord decisions in a JSON file "
                        + "(see scenarios/*/approvals.json) and run `./agentic resume ").append(s.state.runId)
                        .append(" --approvals <file>`.\n\n");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (md.indexOf("`" + req.key() + "`") < 0) {
            md.append("## `").append(req.key()).append("`\n\n").append(req.summary()).append("\n\n");
            req.reasons().forEach(r -> md.append("- ").append(r).append('\n'));
            md.append('\n');
        }
        Json.writeAtomically(f, md.toString());
    }

    private static void corrupt(Workspace ws, String id, AgentResult result) {
        result.changedFiles().stream().filter(p -> p.endsWith(".java")).findFirst().ifPresent(p -> {
            String content = ws.read(p);
            ws.apply(id, List.of(FileOp.patch(p, content, content + "\n#injected-corruption (not valid Java)\n")));
        });
    }

    private ObjectNode runInfo(Scenario scenario, WorkflowGraph graph) {
        ObjectNode n = Json.object();
        n.put("scenario", scenario.id());
        n.put("type", scenario.type());
        n.put("workflow", graph.name());
        n.put("requirement", scenario.requirement());
        n.put("llm", llm == null ? "none (deterministic agents)" : llm.model());
        n.put("faultInjection", faults.active());
        n.put("maxParallel", options.maxParallel());
        return n;
    }

    private static ObjectNode artifactEvent(Artifact a) {
        ObjectNode n = Json.object();
        n.put("key", a.key());
        n.put("version", a.version());
        n.put("sha256", a.sha256());
        n.put("producer", a.producer());
        return n;
    }

    private void save(Session s) {
        s.state.artifacts = s.context.index();
        s.state.save(s.runDir);
    }

    private void log(String fmt, Object... args) {
        if (console != null) {
            console.println(String.format(fmt, args));
        }
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record Outcome(String stageId, AgentResult result, AgentException error, Map<String, String> consumed, long millis) { }

    private record InFlight(Future<Outcome> future, long deadlineMillis) { }

    /** Mutable per-execution context (one per start/resume call). */
    private final class Session {
        final Path runDir;
        final RunState state;
        final WorkflowGraph graph;
        final Scenario scenario;
        final Workspace workspace;
        final ContextStore context;
        final AuditLog audit;
        final Map<String, InFlight> inflight = new LinkedHashMap<>();
        final Set<String> asked = new HashSet<>();
        RunStatus terminal;
        String reason;

        Session(Path runDir, RunState state, WorkflowGraph graph, Scenario scenario, Workspace workspace) {
            this.runDir = runDir;
            this.state = state;
            this.graph = graph;
            this.scenario = scenario;
            this.workspace = workspace;
            this.context = new ContextStore(runDir, state.artifacts, clock);
            this.audit = new AuditLog(runDir.resolve("audit.jsonl"), state.runId, clock);
        }
    }
}
