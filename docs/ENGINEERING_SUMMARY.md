# Engineering summary

## 1. Problem as understood

The brief asks for a system that turns a requirement into a *reviewable engineering outcome* through agentic execution with controlled autonomy. The URL shortener is the subject matter, not the point.

I read the evaluation weight as falling on four things:

1. **The orchestration layer.** Non-linear, stateful and governed, not a chain of prompts.
2. **Defensible engineering decisions** in both the orchestrator and the product.
3. **Evidence:** every claim the system makes about its work should be checkable.
4. **Honesty about limits.**

Ambiguities in the brief itself, and how I resolved them:

| Ambiguity | Decision |
|---|---|
| "Agentic" is undefined: must agents be LLM calls? | Agents are autonomous units with bounded authority. The model is a **pluggable proposal source** behind validation, never a trusted actor. The default mode is deterministic and offline, so every behaviour is reproducible in CI and reviewable. |
| "Release readiness" vs "release" | The system packages a verified, approved patch. **It never pushes or deploys**: release execution belongs to existing change management (MR, CI, deploy pipeline). |
| How "real" must greenfield output be? | Real enough to run: the generated Spring Boot workspace is built and tested with Gradle (49 tests) on every greenfield run, and the reference service is a usable product. |
| Human approvals in an automated demo | Approvals are recorded **as data** (reviewable files, named approvers) or given interactively. The demo uses files, so runs are reproducible, and a missing decision pauses the run instead of defaulting to yes. |

## 2. Plan and rationale

The order of work was:

1. **Product first:** the shortener, with tests. The orchestrator needs a real codebase to reason about, and the brownfield and ambiguous scenarios need a baseline.
2. **Orchestration kernel:** DAG, scheduler, state, gates, retries, fallback, rollback, safe-stop, audit, metrics. It is tested with stub agents so that control flow is verified independently of agent quality.
3. **SDLC agents and knowledge base**, then the three scenarios, driven end to end. Failures found here were fixed in the engine, not worked around in scenarios. Examples: a traceability gap and a resume ordering bug, described in §5.
4. **Documentation generated from runs.** The examples are produced by a script, so the documentation cannot drift from behaviour.

### Key decisions

| # | Decision | Why | Alternatives rejected |
|---|---|---|---|
| D-01 | Single-threaded scheduler owns all state; agents run on a bounded pool and return proposals | There are no locks around run state. Agents physically cannot commit, approve or skip gates. Concurrency bugs are confined to the workspace, which serialises writes. | Agents writing state directly (needs locking, invites self-approval); an actor framework (dependency weight for no benefit at this scale) |
| D-02 | Lineage by content hash: each stage records the hashes it consumed | "What changed upstream?" becomes an exact, cheap comparison. It drives invalidation, re-planning and the decision lineage from one mechanism. | Event-driven "plan changed" notifications (easy to miss a path); timestamps (not content-addressed) |
| D-03 | Re-planning restores the workspace baseline and re-runs every mutating stage | The result is always equivalent to a clean run of the current plan. Incremental reconciliation of partially applied plans is where subtle corruption lives. | Diff-and-patch the workspace towards the new plan |
| D-04 | Isolated workspace, baseline snapshot, per-stage undo logs persisted before each write | Per-stage undo works while other stages run in parallel on disjoint files, and survives crashes. Baseline restore gives run-level rollback. | Git branches inside the workspace (heavier; harder to undo one stage among parallel ones) |
| D-05 | Policy-as-code: HIGH → human checkpoint, CRITICAL → safe-stop; workflow configuration cannot disable it | Governance has to hold even when the workflow definition is wrong. Severity maps to a fixed control response. | Policy as advisory warnings; per-workflow opt-outs |
| D-06 | Pre-action approval for release; output approval for design/policy checkpoints | A high-impact *action* must be authorised before it happens. A risky *proposal* can be reviewed with its evidence. | Approve everything up front (rubber stamp); approve only at the end (too late for privacy) |
| D-07 | Compliance checks on the **plan**, before code | The geo-analytics privacy issue is caught and rejected for the price of one planning step, not after implementation. | Scan only the code (late, expensive, and IP processing may be indirect) |
| D-08 | A rejection becomes a recorded constraint; adaptable phases re-plan, others fail | Humans steer instead of just stopping work. Re-plans are bounded (3) to prevent loops. | Rejection always fails the run |
| D-09 | Hash-chained, fail-closed audit log | Tamper evidence without infrastructure. If auditing fails, the run stops. | Plain logs (mutable); external ledger (infrastructure) |
| D-10 | A declarative knowledge catalog plus reviewed recipes in offline mode; LLM as a validated seam | Reproducible, testable, reviewable autonomy. Model output is untrusted input: JSON-validated and restricted to known ids, with a deterministic fallback. | Prompt-driven agents writing code directly (unreviewable, non-reproducible, untestable offline) |
| D-11 | Patch anchors must match exactly once; no "already applied" heuristics | Drift between reviewed change and current code is a conflict, never a silent misapplication. An idempotency heuristic was tried and removed after a test showed false positives. | Fuzzy patching |
| D-12 | Service on **Spring Boot 4.0** with a framework-free domain (`core/`, `store/`) wired by one `@Configuration`, and storage behind a `LinkRepository` port | Spring Boot is the team standard and gives Actuator probes, Micrometer metrics, structured logging and validated configuration for free. Keeping Spring out of the domain keeps business rules unit-testable in milliseconds and the framework replaceable. The port keeps a database swap (JPA/Postgres) local to one class. | Spring annotations throughout the domain; Spring Data from day one (a database dependency the brief doesn't need) |
| D-13 | 302 + `no-cache`; HEAD not counted; no IPs stored | Correct analytics, expiry that actually takes effect, privacy by default. | 301 (cached forever; breaks analytics and expiry) |

## 3. Artifacts produced

| Artifact | Location |
|---|---|
| Working Spring Boot 4 service + 49 tests + generated API reference | `shortener-service/` |
| Orchestrator + 47 tests (unit, engine, end-to-end) | `orchestrator/` |
| Workflow definition (declarative DAG with gates and approval modes) | `orchestrator/src/main/resources/workflows/sdlc.json` |
| Knowledge base: capabilities, tasks, ambiguity patterns, reviewed change sets | `knowledge/` |
| Scenario definitions and recorded human decisions | `scenarios/` |
| Per run: report, audit trail, versioned artifacts, metrics, workspace, release patch | `runs/<id>/` (examples in `examples/runs/`) |
| Architecture, scenarios, this summary | `docs/` |
| CI pipeline: build, tests, all scenarios, audit verification | `.github/workflows/ci.yml` |

## 4. Risks, trade-offs and failure scenarios

### Orchestrator

| Risk / failure scenario | Mitigation | Residual |
|---|---|---|
| An agent produces a harmful change | Pre-write policy evaluation, workspace isolation, verification gates, human release approval; agents cannot touch the repository | Policy rules are pattern-based; a determined obfuscation could evade them (defence in depth: review + tests) |
| The model hallucinates capabilities | Strict schema and id validation → deterministic fallback | The model can still choose a *valid but wrong* capability; the plan and design reviews catch it |
| Re-plan oscillation | Bounded re-executions per stage; then the run fails with the reason | — |
| Runaway cost or time | Total-attempt and active-time budgets → safe-stop | — |
| Code drifted after a recipe was reviewed | Exact-once anchors → conflict, nothing written; end-to-end test in CI detects drift | Recipes need maintenance when the code changes, which is the cost of reviewability |
| Parallel stages conflict | Planner serialises shared-file tasks; workspace writes serialised; per-stage undo | — |
| A hung agent after a timeout | The attempt is cancelled (interrupted), its late result ignored, and its changes undone before the retry | An in-process agent that ignores interruption keeps running, and a write it makes *after* the undo is not guaranteed to be reverted. Agents that run untrusted or long work (tests) use a separate process with a hard kill. |
| Tampering with the evidence | Audit hash chain, artifact hashes checked on read, `verify-audit` | The chain proves integrity, not authenticity; signing entries is the next step |
| Two processes resume the same run | `--recover` is an explicit operator assertion | No distributed lock (single-operator prototype) |

### Service

| Trade-off | Choice | When to revisit |
|---|---|---|
| Journal replay into memory | Simple, durable, fast reads | When the data set outgrows memory or startup time matters: move to Postgres behind `LinkRepository` |
| In-process rate limiter | No infrastructure | With more than one instance: shared token bucket (Redis) or a limit at the gateway |
| Analytics computed on read (O(clicks per link)) | Fine for the prototype | Pre-aggregate daily counters on write, or use an event stream |
| No auth on create/stats | Out of the stated scope; the ambiguous scenario shows how auth would arrive (API-key option) | Before exposure to the internet |
| 302 for every redirect | Correct analytics and expiry | Never use 301 for tracked links |

## 5. Validation approach

* **Static:** both modules compile with lint warnings enabled (`-Werror` for the orchestrator). Generated workspaces are built with the same Gradle and Spring Boot versions as the reference service.
* **Service:** 49 tests (JUnit 5).
  * Unit tests: validation edge cases including IDN, IPv6 unique-local, metadata IP and redirect loops; code generation; idempotency under 32 concurrent threads; collision retry and its bound; analytics windows; rate limiter refill and eviction.
  * MockMvc contract tests: status codes, headers, every error mapping (400/404/405/409/413/415/429), request-id handling.
  * Journal: restart, torn tail, corruption, future format version (Jackson 3).
  * Full Spring Boot context on a random port: create, redirect, restart with data intact, Actuator liveness/readiness, metrics, uniform errors.
* **Orchestrator:** 58 tests.
  * Pure components (40): graph cycles and re-expansion; policy rules; audit tamper detection (modify, delete, reorder); workspace atomicity, undo, path escape, OS-metadata and binary-file handling; approval gateway separation of duties; LLM output validation; model-written change sets (the model sees current code, writes are limited to Java sources, retry with feedback, secrets blocked before writing, the AI-001 review is always raised, transcript recorded); Docker sandbox commands (lock-down flags, network only in the prepare phase, read-only cache in the test phase); Gradle report parsing and credential stripping.
  * Engine with stub agents (13 tests): prove that branches run *concurrently* (a barrier that only releases if both run), and cover retry/bounded retry, fallback, rollback, safe-stop, pause/resume, rejection, invalidation, crash recovery and the kill switch.
  * End-to-end (5): all three real scenarios, each generating a Spring Boot workspace and building it with Gradle, plus the LLM seam with fake models (valid output used, invalid output falls back).
* **Docker sandbox, run for real:** `BuildTestAgent` with `AGENTIC_SANDBOX=docker` built and tested a probe project in two containers. Its tests asserted, from inside the test phase, that the network is unreachable, the dependency cache is read-only and host secrets such as `ANTHROPIC_API_KEY` and proxy variables are not visible. All passed. A compile error was reported with correct workspace-relative paths.
* **Model-written code through the engine:** the `ai-assisted` scenario was run with a stub model. The task with no recipe fell back to `implementation-llm`, and the run paused at `implement:T-apikey/code-review`. Without a model, the same run fails at that task and rolls back to the baseline.
* **Independent check of the output:** the brownfield `CHANGE.patch` was applied to a clean copy of the module with `git apply`, and the module's real Gradle build passed all 46 tests.
* **Bugs found by validation and fixed in the engine:**
  * A traceability gap: capabilities delivered by tasks owned by other capabilities.
  * A resume ordering bug: an approval granted on resume was not followed by fan-out expansion.
  * A potential livelock in the progress check.
  * A false-positive "already applied" patch heuristic.
  * A garbled generated API table.
  * During the Spring Boot port, on the first real builds: a bean-name clash with Spring MVC's own `requestContextFilter`; a test whose settings were silently overridden by `application.yml` (default properties have the lowest precedence); and a macOS `.DS_Store` file in the baseline that broke workspace diffs. Each was fixed at the root and covered by a test.

## 6. Assumptions

* Scope is a single-node prototype with one operator. Run state lives on the local file system.
* The capability catalog and recipes are curated by engineers and change-controlled like code. In offline mode, agents can build only what the knowledge base knows how to build; unknown requests fail fast at the `requirements-testable` gate instead of improvising.
* Approver identities in decision files are trusted. Integration with an identity provider or code-owners is out of scope.
* For the service:
  * creation and stats are unauthenticated;
  * a link's destination is immutable;
  * codes are case-sensitive;
  * analytics windows are in UTC days.

## 7. Limitations (honest list)

1. **Model-written code is optional and secondary.** By default, implementation agents replay *reviewed* change sets: greenfield copies from the reference implementation, brownfield applies patches. This keeps every committed run reproducible. With `--llm`, a model implements tasks that have no recipe (or whose recipe no longer applies). It runs behind the same pipeline and always requires a human code review. It is unit-tested and engine-tested with stub models but was **not exercised against the live API** here. Its output quality depends on the model and is non-deterministic by nature, which is why review is mandatory.
2. **Requirement understanding is catalog-driven.** Keyword and pattern matching against known capabilities and ambiguity patterns. The optional LLM classifier (`--llm`, needs `ANTHROPIC_API_KEY`) is unit-tested with fake models but was **not exercised against the live API**, because the build environment had no network access.
3. **Docker sandbox scope.** The prepare phase needs network access to fetch Gradle and dependencies, so the build script runs online. That is acceptable because build files are change-controlled (CHG-002) and the model cannot write them. The sandbox was verified with a probe project and a locally built JDK image. The full Spring Boot workspace in Docker still has to be run on a networked machine. The default stays `process`, so nothing depends on Docker.
4. **Two build environments.** The service is Spring Boot 4.0.8 and needs Maven Central. The orchestrator is plain Java + Jackson 2 + JUnit 4, because it was developed in a sandbox without Maven Central access and verified there offline (`-PofflineLibs`, `-PskipE2E`). The Spring service and the end-to-end scenario tests were built and verified on a networked developer machine. A follow-up would move the orchestrator to JUnit 5 for consistency.
5. Orchestrator scale: one process, file-based state, no distributed locking, no multi-tenancy, and timeouts are cooperative for in-process agents.
6. Policy rules are regex-based heuristics, not a full SAST tool. In a real pipeline they complement Snyk/CodeQL rather than replace them.
7. The service is single-node (see the §4 trade-offs).

## 8. Mapping to the brief

| Brief requirement | Where |
|---|---|
| Requirement understanding, ambiguity, normalisation | `RequirementsAgent` (capabilities, FR/NFR, acceptance, assumptions, blocking vs non-blocking ambiguity); `CLARIFICATIONS.md`; scenario 3 |
| Task decomposition with dependencies and sequencing | `PlanningAgent` (prerequisites, shared-file serialisation, waves); runtime fan-out in `WorkflowGraph.expand` |
| Codebase reasoning (brownfield) | `CodebaseAnalysisAgent` (types, reference graph, routes, tests, present capabilities); `DesignAgent` (changed types, ripple set) |
| Explicit dependency graph with entry/exit gates | `sdlc.json`, `WorkflowGraph`, `Gates` |
| Sequential and parallel paths with synchronisation | DAG readiness, worker pool, join stages (`plan`, `implement` barrier, `release_readiness`); proven by `independentBranchesRunInParallelAndJoin` |
| Cross-stage context and decision lineage | `ContextStore` (versioned, hashed), `AgentContext.consumed`, `Decision.basedOn`, report §5 |
| Human approval for high-impact actions | `Approval.*` gateways, pre-action release approval, policy-driven checkpoints, separation of duties |
| Bounded retries, fallback, rollback, safe-stop | `Orchestrator.onFailure`, `fallbackAgent`, `Workspace.undo/restore`, `checkSafeStop`/`terminate`/`finish`, kill switch, budgets, crash recovery |
| Policy guardrails: security, compliance, change control | `PolicyEngine` (plan, change set, diff) |
| Audit-grade observability and traceability | `AuditLog` (hash chain), `RunReport`, traceability matrix in readiness |
| Reliability metrics: success rate, retry/rollback frequency, MTTR, e2e latency | `Metrics.forRun` / `Metrics.aggregate`, `agentic metrics` |
| Dynamic re-planning when upstream outputs change | `invalidateStale`, rejection → constraint → re-execution, fan-out re-expansion |
| Production-quality code, API definitions, tests, docs | `shortener-service/` (Spring Boot 4), generated `docs/API.md`, 96 tests, CI |
| Controlled autonomy | Autonomy boundaries table in ARCHITECTURE §6; agents never modify the repository |
| Three scenarios with decomposition, orchestration and validation | `docs/SCENARIOS.md`, `examples/runs/` |
