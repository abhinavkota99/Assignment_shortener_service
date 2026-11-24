# Agentic SDLC orchestrator + URL shortener

A working prototype of **governed, agentic software delivery**. A plain-language requirement goes in.
A reviewed, tested, documented and auditable change package comes out. Agents do the work inside
explicit autonomy boundaries, and humans own the approvals and the final release.

The repository contains two things:

| Module | What it is |
|---|---|
| [`shortener-service/`](shortener-service) | The product: a production-style URL shortener on **Spring Boot 4.0 (Spring Framework 7, Java 17)**. It has a REST API for create/redirect/metadata/analytics, idempotent creates, CSPRNG codes, URL abuse validation, a per-client rate-limit interceptor, crash-safe journal persistence (Jackson 3), Actuator liveness/readiness probes with a storage health check, Micrometer metrics, structured ECS JSON logs and 49 tests (JUnit 5, MockMvc, full-context). |
| [`orchestrator/`](orchestrator) | The agentic layer. It runs a DAG of SDLC stages: requirements, codebase analysis, planning, design, parallel implementation, parallel verification and docs, release readiness, and an approved release. It has entry/exit gates, bounded retries, fallback agents, rollback, safe-stop, policy guardrails, human checkpoints, decision lineage, a hash-chained audit trail, reliability metrics, and re-planning when upstream outputs change. |

The orchestrator has three scenarios:

- **Greenfield:** it builds the shortener into an empty workspace.
- **Brownfield:** it adds link expiration to the existing code.
- **Ambiguous:** given "make it safer and give marketing better insight", it stops to ask, then re-plans around a privacy rejection.
- **AI-assisted (optional):** API keys for link creation. No reviewed recipe exists for this task, so a model writes the change. The model is shown the current code, its output is validated and policy-checked, and the run **always stops for a human code review** before the change is accepted.

Two optional hardening features are both off by default, so the default build and demo stay deterministic and need nothing beyond a JDK:

- **Model-written code** (`--llm` + `ANTHROPIC_API_KEY`): the fallback when a task has no approved recipe or its recipe no longer applies.
- **Docker build sandbox** (`AGENTIC_SANDBOX=docker`): runs the workspace build and tests in a locked-down container. Tests run with **no network**.

## Quick start

Prerequisites: **JDK 17+** (`JAVA_HOME` set) and **git**. The Gradle wrapper downloads Gradle, and the first build downloads Spring Boot from Maven Central.

```bash
./gradlew build                      # both modules, 107 tests (includes all 3 scenarios end to end)

# Greenfield: build the service from an empty workspace (approvals recorded in a file)
./agentic run scenarios/greenfield --approvals scenarios/greenfield/approvals.json

# Brownfield: add link expiration to the existing service
./agentic run scenarios/brownfield --approvals scenarios/brownfield/approvals.json

# Ambiguous: pauses with clarification questions (exit code 3) ...
./agentic run scenarios/ambiguous --run-id amb
# ... then resume with a stakeholder's answers; the privacy officer rejects geo analytics -> automatic re-plan
./agentic resume amb --answers scenarios/ambiguous/answers.json --approvals scenarios/ambiguous/approvals.json

./agentic verify-audit amb           # recompute the audit hash chain
./agentic metrics                    # success rate, retry/rollback frequency, MTTR, e2e latency
```

Each run writes `runs/<run-id>/`:

- `REPORT.md`: the engineering summary for the run.
- `audit.jsonl`: the audit trail.
- `artifacts/`: every versioned stage output.
- `workspace/`: the code the agents changed.
- `release/CHANGE.patch` + `MANIFEST.json`: what a human applies.

Agents **never modify the repository itself**.

Pre-generated outputs of every scenario are committed in [`examples/runs/`](examples/runs) if you want to read
results without running anything.

### Try the failure paths

```bash
# transient faults -> bounded retries with exponential backoff (MTTR recorded)
./agentic run scenarios/brownfield --approvals scenarios/brownfield/approvals.json \
    --fault implement:T-expiry-store:transient:2

# a change that breaks compilation -> verification gate fails -> workspace rolled back to baseline
./agentic run scenarios/brownfield --approvals scenarios/brownfield/approvals.json --fault implement:T-expiry-api:corrupt

# no approvals supplied -> run pauses at the design review; resume with decisions later
./agentic run scenarios/brownfield --run-id pending
./agentic resume pending --approvals scenarios/brownfield/approvals.json

# interactive approvals in the terminal instead of a file
./agentic run scenarios/brownfield --interactive

# kill switch for a running run (safe-stop + rollback)
./agentic stop <run-id>
```

### Optional: model-written code and the Docker sandbox

```bash
# Without a model the AI-assisted scenario fails cleanly at the task that has no recipe, and rolls back
./agentic run scenarios/ai-assisted

# With a model: the model implements T-apikey, and the run pauses for code review (AI-001, always HIGH)
export ANTHROPIC_API_KEY=...            # never committed; never visible to the code under test
./agentic run scenarios/ai-assisted --llm --run-id ai-1
cat runs/ai-1/APPROVALS-PENDING.md      # what needs review
cat runs/ai-1/ai/T-apikey-a1.md         # exact prompt and raw model reply, for the reviewer and the audit
./agentic resume ai-1 --interactive     # approve or reject after reading the diff in runs/ai-1/workspace

# Build and test in a locked-down container (needs Docker; pulls eclipse-temurin:17-jdk)
AGENTIC_SANDBOX=docker ./agentic run scenarios/brownfield --approvals scenarios/brownfield/approvals.json
```

`AGENTIC_SANDBOX` takes `process` (the default), `docker` (fail if Docker is missing, never silently weaker) or `auto`.
The image, cache directory and limits can be overridden with `AGENTIC_SANDBOX_IMAGE`, `AGENTIC_SANDBOX_CACHE`, `AGENTIC_SANDBOX_MEMORY`, `AGENTIC_SANDBOX_CPUS` and `AGENTIC_SANDBOX_PIDS`.

### Run the service

```bash
./gradlew :shortener-service:bootRun      # http://localhost:8080, data in shortener-service/data/links.jsonl

curl -s -XPOST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com/docs","alias":"docs"}'
curl -si localhost:8080/docs | head -3                 # 302 Location: https://example.com/docs
curl -s localhost:8080/api/v1/links/docs/stats
curl -s localhost:8080/actuator/health/readiness       # {"status":"UP",...} incl. storage
curl -s localhost:8080/actuator/metrics/shortener.links.created
```

The API reference is in [`shortener-service/docs/API.md`](shortener-service/docs/API.md), generated by the docs agent.
Configuration is typed `@ConfigurationProperties` ([`Settings.java`](shortener-service/src/main/java/com/example/shortener/config/Settings.java)), with defaults in [`application.yml`](shortener-service/src/main/resources/application.yml). Every value can be overridden by an environment variable:

- `SERVER_PORT`
- `SHORTENER_BASE_URL`
- `SHORTENER_DATA_FILE` (empty = in-memory)
- `SHORTENER_RATE_LIMIT_PER_SECOND`, `SHORTENER_RATE_LIMIT_BURST`
- …

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, orchestration model, control loop, state machines, governance and data flow, key design decisions |
| [docs/SCENARIOS.md](docs/SCENARIOS.md) | The three scenarios: decomposition, orchestration and validation for each, with the actual outputs |
| [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) | Plan and rationale, artifacts, risks and trade-offs, validation approach, assumptions, limitations, mapping to the brief |

## Repository layout

```
orchestrator/            agentic SDLC engine (Java 17)
  engine/                scheduler, run state, gates, fault injection
  workflow/              DAG model + runtime fan-out expansion
  agents/                requirements, codebase analysis, planner, design, implementation, build/test, security, docs, readiness, release
  governance/            policy engine, approval gateways
  context/               versioned artifact store, decision records
  observability/         hash-chained audit log, metrics, run report
  workspace/             isolated workspace, atomic change sets, undo logs, snapshots
  src/main/resources/workflows/sdlc.json   the workflow graph (declarative)
shortener-service/       the URL shortener: Spring Boot 4 (web: controller/advice/interceptor/filter, core: domain, store, health, config)
knowledge/               engineering knowledge base: capability catalog + reviewed change-set recipes
scenarios/               greenfield / brownfield / ambiguous / ai-assisted: requirement text + recorded human decisions
examples/runs/           committed outputs of every scenario and recovery demo
agentic                  CLI launcher (builds incrementally, then runs)
```

## Testing

`./gradlew build` runs 96 tests:

- **49 service tests** (JUnit 5):
  - unit tests for validation, codes, service rules (including 32 concurrent idempotent requests), rate limiter and journal recovery;
  - **MockMvc** tests of the HTTP contract: status codes, headers, every error mapping;
  - a **full Spring Boot context** on a random port: create → redirect → restart → stats, Actuator probes and metrics.
- **58 orchestrator tests**, made up of:
  - **40 unit tests:** graph, policy, audit tampering, workspace atomicity/undo/binary handling, approval gateway, LLM output validation, model-written change sets (scope, retry feedback, policy blocking, mandatory review), Docker sandbox commands, Gradle report parsing.
  - **13 engine tests with stub agents:** parallel join, retry, bounded retry, fallback, rollback, safe-stop, approval pause/resume, rejection, re-planning, crash recovery, kill switch.
  - **5 end-to-end runs** of the real scenarios. Each has the agents generate a Spring Boot workspace and **build and test it with Gradle** in an isolated copy. This also detects drift: if the service code changes so that a reviewed recipe no longer applies, the build fails.

CI is in [`.github/workflows/ci.yml`](.github/workflows/ci.yml). It builds, runs all scenarios through the CLI and verifies every audit chain.

## AI assistance

As the brief encourages, this was built with an AI coding assistant. All code is compiled with lint
warnings enabled (`-Werror` for the orchestrator) and exercised by the test suite and the scenario runs
committed under `examples/`.
