# Example runs

Committed outputs of every scenario and recovery demo, produced by `scripts/generate-examples.sh` from
the code in this repository (absolute paths stripped). Each `*.REPORT.md` is the engineering summary
the orchestrator writes for a run; `*.audit.jsonl` is its hash-chained event trail.

| Run | Outcome | Start with |
|---|---|---|
| greenfield | SUCCEEDED: 10 tasks / 5 waves, 36 tests, 3 human checkpoints | [REPORT](runs/greenfield.REPORT.md), [console](runs/greenfield.console.txt), [generated API doc](runs/greenfield.generated-API.md) |
| brownfield | SUCCEEDED: 4 tasks, 46 tests (36 existing + 10 new), additive API/data change | [REPORT](runs/brownfield.REPORT.md), [CHANGE.patch](runs/brownfield.CHANGE.patch) |
| ambiguous, run 1 | AWAITING_INPUT: two blocking ambiguities, nothing built | [CLARIFICATIONS](runs/ambiguous-1-paused.CLARIFICATIONS.md), [REPORT](runs/ambiguous-1-paused.REPORT.md) |
| ambiguous, run 2 | SUCCEEDED after answers, privacy rejection and re-plan (3 tasks → 2) | [REPORT](runs/ambiguous.REPORT.md) (see the decision lineage), [console](runs/ambiguous-2-resumed.console.txt), [CHANGE.patch](runs/ambiguous.CHANGE.patch) |
| recovery-retry | SUCCEEDED with 3 injected transient faults, retried with backoff | [REPORT](runs/recovery-retry.REPORT.md) |
| recovery-rollback | FAILED by design: corrupted change caught by the build gate, workspace rolled back | [REPORT](runs/recovery-rollback.REPORT.md) |
| approval-pending | AWAITING_APPROVAL: no decisions supplied, paused at the design review | [APPROVALS-PENDING](runs/approval-pending.APPROVALS-PENDING.md) |

`audit-verification.txt` holds the `verify-audit` result for each run. `metrics-aggregate.json` holds the
cross-run reliability metrics.
