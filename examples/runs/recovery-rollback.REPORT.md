# Run report: recovery-rollback

| | |
|---|---|
| Scenario | `brownfield-link-expiration` |
| Status | **FAILED** - build_test: exit gate failed: tests-green=FAIL (compilation failed) |
| Started | 2026-11-24T04:17:44.471Z |
| Active time | 1094 ms (excludes time paused for humans) |
| Resumes | 0 |
| Audit chain | verified, 71 entries (hash chain intact) |

## 1. Requirement understanding

> Marketing wants campaign links that expire. When creating a short link, clients may set an optional expiry time. After that time the short link must stop redirecting and return 410 Gone, while its metadata and click analytics remain available. Links created without an expiry keep working forever, and the redirect path has to stay as fast as it is today.

Interpreter: `rules` - 4 capabilities, 7 functional requirements, 5 NFRs, 0 ambiguities (0 blocking & open)

| Capability | Source | Functional requirements |
|---|---|---|
| Create short links | stated (matched 'short link') | FR-1: A client can submit an absolute http(s) URL and receive a unique, non-guessable short code and short URL.<br>FR-2: Invalid, unsafe or self-referencing destination URLs are rejected with a validation error.<br> |
| Redirect | stated (matched 'redirect') | FR-3: Requesting a short code redirects the caller to the destination URL.<br> |
| Click analytics | stated (matched 'analytics') | FR-4: Link owners can see total clicks, clicks per day and top referrers for a link.<br> |
| Link expiration | stated (matched 'expire') | FR-5: A client may set an optional expiry time (ISO-8601 UTC) when creating a link; it must be in the future and at most 5 years ahead.<br>FR-6: After expiry the short link stops redirecting and returns 410 Gone; expired visits are not counted as clicks.<br>FR-7: Metadata and analytics of expired links remain available; links created without expiry never expire.<br> |

**Non-functional requirements**

- NFR-1 Redirect p99 latency must not regress versus the current release (measured on the same hardware). _(assumed: 'fast' has no measurable target)_
- NFR-2 Every input from clients is validated; errors use a consistent JSON error format. _(engineering standard)_
- NFR-3 No secrets, credentials or personal data are written to source code or logs. _(engineering standard)_
- NFR-4 Every change ships with automated tests; the existing test suite must keep passing. _(engineering standard)_
- NFR-5 Changes are backward compatible for existing clients and stored data unless explicitly approved. _(engineering standard)_

**Assumptions**

- 'fast' interpreted as: Redirect p99 latency must not regress versus the current release (measured on the same hardware).

## 2. Plan (task decomposition)

| Task | Capability | Depends on | Files |
|---|---|---|---|
| `T-expiry-domain` Expiry in domain model and service | link_expiration | [] | 3 |
| `T-expiry-store` Persist expiry in the journal (backward compatible) | link_expiration | ["T-expiry-domain"] | 1 |
| `T-expiry-api` expiresAt in the HTTP contract, 410 Gone | link_expiration | ["T-expiry-domain"] | 2 |
| `T-expiry-tests` Expiry tests (service, API, journal compatibility) | link_expiration | ["T-expiry-domain","T-expiry-store","T-expiry-api"] | 3 |

Parallel waves: [["T-expiry-domain"],["T-expiry-store","T-expiry-api"],["T-expiry-tests"]]

**Reused existing capabilities**

- link_creation
- redirect
- click_analytics

## 3. Design and impact

- Impacted files: 9
- Changed existing types: ["Link","ServiceException","ShortenerService","JournalLinkRepository","LinkController","LinkResponse"]
- Ripple (re-verified) types: ["InMemoryLinkRepository","JournalLinkRepositoryTest","LinkRepository","ApiExceptionHandler","ShortenerServiceTest","UrlValidator","UrlValidatorTest","LinkControllerTest","ShortenerConfiguration"]

**API changes**

- POST /api/v1/links: new optional field expiresAt (additive, backward compatible)
- GET /api/v1/links/{code}: response gains expiresAt (null when not set)
- GET|HEAD /{code}: new status 410 gone for expired links

**Data changes**

- journal 'link' record: optional expiresAt field; old records without it load as never-expiring (format version unchanged)

**Risks**

- link_expiration: clock skew between instances could make expiry fire early or late by the skew - acceptable at second granularity
- link_expiration: clients that cached a 302 keep redirecting after expiry - mitigated: redirects are sent with Cache-Control private, max-age=0

## 4. Execution

| Stage | Phase | Status | Attempts | Agent | Duration | Notes |
|---|---|---|---|---|---|---|
| `requirements` | REQUIREMENTS | SUCCEEDED | 2 | requirements-rules | 57 ms | fallback; recovered from: no LLM configured (offline mode);  |
| `codebase_analysis` | ANALYSIS | SUCCEEDED | 1 | codebase-analysis | 80 ms |  |
| `plan` | PLANNING | SUCCEEDED | 1 | planner | 17 ms |  |
| `design` | DESIGN | SUCCEEDED | 1 | design | 14 ms | approved: design-review by tech-lead.alice;  |
| `implement:T-expiry-domain` | IMPLEMENTATION | ROLLED_BACK | 1 | implementation | 26 ms |  |
| `implement:T-expiry-store` | IMPLEMENTATION | ROLLED_BACK | 1 | implementation | 8 ms |  |
| `implement:T-expiry-api` | IMPLEMENTATION | ROLLED_BACK | 1 | implementation | 14 ms |  |
| `implement:T-expiry-tests` | IMPLEMENTATION | ROLLED_BACK | 1 | implementation | 7 ms |  |
| `implement` | IMPLEMENTATION | ROLLED_BACK | 1 | join | 2 ms |  |
| `build_test` | VERIFICATION | FAILED | 1 | build-test | 881 ms | error: exit gate failed: tests-green=FAIL (compilation failed);  |
| `security_scan` | VERIFICATION | SUCCEEDED | 1 | security-scan | 48 ms |  |
| `docs` | DOCUMENTATION | ROLLED_BACK | 1 | docs | 9 ms |  |
| `release_readiness` | RELEASE | SKIPPED | 0 |  |  |  |
| `release` | RELEASE | SKIPPED | 0 |  |  |  |

## 5. Decisions (lineage)

| Id | Stage | Actor | Decision | Rationale | Based on |
|---|---|---|---|---|---|
| D-1 | requirements | agent:requirements-rules | Interpreted requirement as capabilities [link_creation, redirect, click_analytics, link_expiration] | interpreter=rules; {link_creation=stated (matched 'short link'), redirect=stated (matched 'redirect'), click_analytics=stated (matched 'analytics'), link_expiration=stated (matched 'expire')} | [human.clarifications#absent] |
| D-2 | requirements | agent:requirements-rules | Interpreted vague quality 'fast' as a measurable target | Redirect p99 latency must not regress versus the current release (measured on the same hardware). | [human.clarifications#absent] |
| D-3 | plan | agent:planner | Reuse existing capability 'link_creation' | codebase analysis found its evidence; rebuilding would add risk | [requirements#72d73a7ddd, codebase_index#64d7e2257d, human.constraints#absent] |
| D-4 | plan | agent:planner | Reuse existing capability 'redirect' | codebase analysis found its evidence; rebuilding would add risk | [requirements#72d73a7ddd, codebase_index#64d7e2257d, human.constraints#absent] |
| D-5 | plan | agent:planner | Reuse existing capability 'click_analytics' | codebase analysis found its evidence; rebuilding would add risk | [requirements#72d73a7ddd, codebase_index#64d7e2257d, human.constraints#absent] |
| D-6 | plan | agent:planner | Decomposed into 4 tasks / 3 parallel waves | waves=[[T-expiry-domain], [T-expiry-store, T-expiry-api], [T-expiry-tests]] | [requirements#72d73a7ddd, codebase_index#64d7e2257d, human.constraints#absent] |
| D-7 | design | agent:design | Impact limited to [Link, ServiceException, ShortenerService, JournalLinkRepository, LinkController, LinkResponse] with ripple [InMemoryLinkRepository, JournalLinkRepositoryTest, LinkRepository, ApiExceptionHandler, ShortenerServiceTest, UrlValidator, UrlValidatorTest, LinkControllerTest, ShortenerConfiguration] | type-level reference graph from codebase_index | [requirements#72d73a7ddd, plan#61a8373276, codebase_index#64d7e2257d] |
| D-8 | design | human:tech-lead.alice | Approved design/design-review | Additive API change (optional expiresAt, new 410) and additive journal field; old clients and old data unaffected. | [requirements#72d73a7ddd, plan#61a8373276, codebase_index#64d7e2257d] |

## 6. Validation

- Tests: 0 run, 0 failed, compiled=false, errors: ["src/main/java/com/example/shortener/web/LinkController.java:122: illegal character: '#'","src/main/java/com/example/shortener/web/LinkController.java:122: class, interface, enum, or record expected","LinkController.java:122: class, interface, enum, or record expected","LinkController.java:122: illegal character: '#'","src/main/java/com/example/shortener/web/LinkController.java:122: class, interface, enum, or record expected","src/main/java/com/example/shortener/web/LinkController.java:122: illegal character: '#'"]
- Security scan: 0 critical, 0 high

**Workspace diff vs baseline:** 0 files


## 7. Reliability metrics

```json
{
  "runId" : "recovery-rollback",
  "scenario" : "brownfield-link-expiration",
  "status" : "FAILED",
  "finished" : true,
  "endToEndActiveMs" : 1094,
  "stagesExecuted" : 12,
  "stagesFailed" : 7,
  "retries" : 0,
  "fallbacks" : 0,
  "primaryUnavailable" : 1,
  "rollbacks" : 1,
  "reExecutions" : 0,
  "approvalsRequested" : 1,
  "approvalsRejected" : 0,
  "policyFindings" : 0,
  "resumes" : 0,
  "recoveries" : 0
}
```

Full event trail: `audit.jsonl` (hash-chained; verify with `./agentic verify-audit recovery-rollback`).
