# Scenarios

Each scenario is a directory under `scenarios/`:

* `scenario.json` holds the requirement exactly as a stakeholder wrote it, plus where work starts.
* The other files hold the recorded **human** decisions: approvals and clarification answers.

Same engine, same workflow and same agents for all three. Only the input differs.

Committed outputs for every run below are in [`examples/runs/`](../examples/runs). To regenerate them, run `scripts/generate-examples.sh`.

---

## 1. Greenfield: build the URL shortener

> *Build a URL shortener service. Clients shorten long URLs into a short link, optionally choosing a custom alias. Visiting a short link should redirect to the original URL, and link owners need click analytics (total clicks, daily trend, top referrers). The service must be reliable: data has to survive restarts, clients need safe retries when creating links, and it must be protected against abuse. Expose health and metrics endpoints for operations. Target p99 redirect latency under 50 ms at 200 requests per second on a single node.*

```bash
./agentic run scenarios/greenfield --approvals scenarios/greenfield/approvals.json
```

**Requirement understanding.** The requirement maps to 8 capabilities and 9 functional requirements, each with acceptance criteria.

* One NFR is *stated*: p99 < 50 ms at 200 rps.
* "reliable" has no target, so a measurable interpretation is *assumed* and listed as an assumption for review.
* Four organisational engineering standards are added as NFRs.
* No blocking ambiguity is found.

**Decomposition.** The workspace is empty, so nothing is reused. The plan has 11 tasks in 6 dependency waves, with the critical path being 6 stages long:

| Wave | Tasks (run in parallel) |
|---|---|
| 1 | `T-build` (Spring Boot 4 `build.gradle` + `application.yml`), `T-domain`, `T-codes`, `T-ratelimit` |
| 2 | `T-config` (after build), `T-store` (after domain) |
| 3 | `T-validation` (after config, domain), `T-journal` (after store, build) |
| 4 | `T-service` (after store, validation, codes, config) |
| 5 | `T-api`: controller, advice, interceptor, filter (after service, ratelimit, build) |
| 6 | `T-bootstrap`: Spring wiring, health indicator, application, end-to-end test (after api, journal) |

Each capability is traced to the tasks that deliver it. For example, *click analytics* is delivered by `T-service` and `T-api`. The `plan-traceable` gate fails the run if any capability has no task, no existing implementation and no recorded exclusion. An earlier version of the planner did fail this gate, which is how the multi-capability traceability was introduced.

**Orchestration highlights:**

* `requirements` ∥ `codebase_analysis`.
* The implement fan-out runs each wave concurrently.
* `build_test` ∥ `security_scan` ∥ `docs`, then the `release_readiness` join.
* **Four human checkpoints:**
  1. `design/design-review`: a new public API and data format.
  2. `implement:T-build/change-control`: `build.gradle` is a change-controlled file (dependencies), so the new build needs sign-off.
  3. `implement:T-api/privacy-review`: the policy engine flagged `clientIp` in `RateLimitInterceptor`. The approver recorded that the IP is used in memory only as the rate-limit key.
  4. `release/release`: a pre-action approval.

**Validation:**

* The generated Spring Boot workspace is built with Gradle in an isolated copy, and all 49 tests pass, including the full-context test on a random port.
* The security scan of 33 source files finds 0 critical and 1 high finding, the approved IP use.
* All 7 release checks pass, and the traceability matrix covers all 8 capabilities.
* The release is a 36-file patch plus a hash manifest.
* The audit chain of 118 entries verifies.

Outputs:

* [`greenfield.REPORT.md`](../examples/runs/greenfield.REPORT.md)
* [`greenfield.generated-API.md`](../examples/runs/greenfield.generated-API.md)
* [`greenfield.MANIFEST.json`](../examples/runs/greenfield.MANIFEST.json)

---

## 2. Brownfield: add link expiration

> *Marketing wants campaign links that expire. When creating a short link, clients may set an optional expiry time. After that time the short link must stop redirecting and return 410 Gone, while its metadata and click analytics remain available. Links created without an expiry keep working forever, and the redirect path has to stay as fast as it is today.*

```bash
./agentic run scenarios/brownfield --approvals scenarios/brownfield/approvals.json
```

The workspace starts as a copy of `shortener-service/`.

**Codebase reasoning.** `codebase_analysis` indexes the existing Spring Boot code: its types, the 36 `@Test` methods and the HTTP routes. It builds the type-level reference graph and finds 8 capabilities already present from their catalog evidence. The planner therefore **reuses** link creation, redirect and click analytics, and plans **only** the new capability:

| Wave | Task | Touches |
|---|---|---|
| 1 | `T-expiry-domain` | `Link` (optional `expiresAt`, source-compatible constructor kept), `ServiceException` (`GONE`), `ShortenerService` (validate & enforce) |
| 2 | `T-expiry-store` ∥ `T-expiry-api` | `JournalLinkRepository` (optional field, old records still load) ∥ `LinkController` + `LinkResponse` (request/response field, ISO-8601 parsing, 410 in route table) |
| 3 | `T-expiry-tests` | 3 new test classes: service, API, journal backward-compatibility |

**Impact analysis.** The design stage reports:

* 6 changed existing types: `Link`, `ServiceException`, `ShortenerService`, `JournalLinkRepository`, `LinkController`, `LinkResponse`.
* A **ripple set** of 9 existing types and tests that reference them, for example `ShortenerConfiguration`, `ApiExceptionHandler` and `LinkControllerTest`. These are why the full suite is run rather than only the new tests.
* API changes: 3, all additive.
* Data change: 1 additive journal field. The format version is unchanged because old readers ignore the field and new readers default it.
* Two risks: clock skew, and cached redirects. The second is mitigated by the existing `no-cache` header.

Because the contract and the data format change, the design agent requests `design-review`.

**Validation:**

* 59 tests run: the 49 existing ones (the `no-test-regression` gate) plus 10 new: service, MockMvc API and journal-compatibility tests.
* The security scan covers 9 changed source files.
* The release is an 11-file `CHANGE.patch`, ready for `git apply -p1` in `shortener-service/`.

**Controlled autonomy in practice.** Run without an approvals file, the run pauses at `design/design-review`. It writes `APPROVALS-PENDING.md` and exits with code 3. `agentic resume <id> --approvals scenarios/brownfield/approvals.json` continues from the exact point it stopped. See [`approval-pending.*`](../examples/runs/).

Outputs:

* [`brownfield.REPORT.md`](../examples/runs/brownfield.REPORT.md)
* [`brownfield.CHANGE.patch`](../examples/runs/brownfield.CHANGE.patch)

---

## 3. Ambiguous: "make it safer and give marketing better insight"

> *Make our link shortener safer for the people who click our links, and give marketing better insight into how their links perform.*

**Run 1: the agent refuses to guess.**

```bash
./agentic run scenarios/ambiguous --run-id amb          # exit code 3: AWAITING_INPUT
```

* The requirements agent recognises two **blocking** ambiguities, because their interpretations lead to different systems:
  * `AMB-safety`: *safer how?* Protect people who click by blocking malicious destinations, or protect the service with API keys.
  * `AMB-insight`: *which insight?* Traffic sources and trends, or a per-country breakdown, which needs client IPs.
* The `requirements-unambiguous` exit gate returns `NEEDS_INPUT`.
* `codebase_analysis` completes in parallel, but **nothing is planned or built**.
* The run writes [`CLARIFICATIONS.md`](../examples/runs/ambiguous-1-paused.CLARIFICATIONS.md) with options and suggested defaults, plus an answers template.

**Run 2: answers, a governance rejection, and a re-plan.**

```bash
./agentic resume amb --answers scenarios/ambiguous/answers.json --approvals scenarios/ambiguous/approvals.json
```

1. The product owner answers `safety=denylist` and `insight=geo`. This is stored as an artifact, so the hash of `requirements`' input changes, and `requirements` is **invalidated and re-executed**.
2. The planner reuses existing link creation and click analytics, and plans `T-denylist`, `T-denylist-wiring` and `T-geo`. `T-geo` is tagged `personal-data`.
3. **Plan-level policy** raises a `privacy-review` before any code exists. The DPO **rejects** it: *"needs client IP processing; not allowed without a DPIA"*.
4. The rejection is recorded as a `human.constraints` artifact. Planning is an adaptable phase, so the planner **re-executes**, excludes `geo_analytics` with the DPO's reason, and the graph is re-expanded with **2 tasks instead of 3**. Traceability still passes because the exclusion is a recorded decision, not a silent drop.
5. The design review is approved. `T-denylist` adds a `DenylistUrlValidator` through the existing `checkHost` extension point; subdomains are blocked, and look-alike domains are not. `T-denylist-wiring` changes the `urlValidator` bean in `ShortenerConfiguration` to read `shortener.blocked-hosts` (env `SHORTENER_BLOCKED_HOSTS`).
6. 56 tests pass (49 existing + 7 new), the release is approved, and a 5-file patch is produced.

The decision lineage in [`ambiguous.REPORT.md`](../examples/runs/ambiguous.REPORT.md) shows the whole chain:

* agent interpretation, then the human answers (`human:product-owner.maria`);
* the resolved ambiguities, then the first plan;
* the **rejection** (`human:dpo.office`), then the **exclusion decision** (based on `human.constraints#…`);
* the second plan, then the approvals.

---

## 4. Recovery behaviour (fault injection)

| Demo | Command | What happens |
|---|---|---|
| Transient faults | `--fault implement:T-expiry-store:transient:2 --fault codebase_analysis:transient:1` | 3 retries with backoff (200 ms, then 400 ms). The run succeeds and the metrics record MTTR. Report: [`recovery-retry.REPORT.md`](../examples/runs/recovery-retry.REPORT.md) |
| Broken change | `--fault implement:T-expiry-api:corrupt` | The implementation "succeeds" but leaves a compile error. `build_test` exit gate `tests-green=FAIL (compilation failed)`, so the run is `FAILED`. **The workspace is restored to baseline** and the implementation stages are marked `ROLLED_BACK`. Report: [`recovery-rollback.REPORT.md`](../examples/runs/recovery-rollback.REPORT.md) |
| Invalid LLM output | (test) a fake model proposes the capability `teleportation` | Validation rejects it, the fallback rules agent produces the requirements, and the run succeeds (`ScenarioEndToEndTest`). |
| Critical policy violation | (test) a change containing a hard-coded secret | `SAFE_STOPPED` before anything is accepted, with rollback (`OrchestratorTest`). |
| Operator kill switch | `./agentic stop <run-id>` | `SAFE_STOPPED`, drain, rollback. |
| Process crash | (test) state left `RUNNING` with a half-written file | `resume --recover` undoes the interrupted attempt from its persisted undo log and finishes. |

Aggregate metrics over the example runs ([`metrics-aggregate.json`](../examples/runs/metrics-aggregate.json)):

* success rate 0.8 over 5 finished runs (the corrupt-change demo fails by design; 1 run is paused);
* rollback frequency 0.2;
* MTTR ≈ 0.5 s;
* p50 end-to-end ≈ 5.3 s, p95 ≈ 6.9 s, dominated by the Gradle build and the Spring Boot test suite (with a warm Gradle cache).
