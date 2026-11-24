#!/usr/bin/env bash
# Re-runs every scenario (and the recovery demos) and copies the evidence into examples/.
# Usage: scripts/generate-examples.sh            (AGENTIC_GRADLE_ARGS is passed through to ./agentic)
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
rm -rf runs examples/runs
mkdir -p examples/runs
A=./agentic
clean() { sed -e "s#$ROOT/##g" -e '/^Picked up /d'; }
run() { local name=$1; shift; "$A" "$@" 2>&1 | clean > "examples/runs/$name.console.txt"; }

run greenfield          run scenarios/greenfield --approvals scenarios/greenfield/approvals.json --run-id greenfield
run brownfield          run scenarios/brownfield --approvals scenarios/brownfield/approvals.json --run-id brownfield
run ambiguous-1-paused  run scenarios/ambiguous --run-id ambiguous
cp runs/ambiguous/REPORT.md examples/runs/ambiguous-1-paused.REPORT.md
cp runs/ambiguous/CLARIFICATIONS.md examples/runs/ambiguous-1-paused.CLARIFICATIONS.md
run ambiguous-2-resumed resume ambiguous --answers scenarios/ambiguous/answers.json --approvals scenarios/ambiguous/approvals.json
run recovery-retry      run scenarios/brownfield --approvals scenarios/brownfield/approvals.json --run-id recovery-retry \
                          --fault implement:T-expiry-store:transient:2 --fault codebase_analysis:transient:1
run recovery-rollback   run scenarios/brownfield --approvals scenarios/brownfield/approvals.json --run-id recovery-rollback \
                          --fault implement:T-expiry-api:corrupt
run approval-pending    run scenarios/brownfield --run-id approval-pending

for r in greenfield brownfield ambiguous recovery-retry recovery-rollback approval-pending; do
  cp "runs/$r/REPORT.md" "examples/runs/$r.REPORT.md"
  cp "runs/$r/audit.jsonl" "examples/runs/$r.audit.jsonl"
  "$A" verify-audit "$r" 2>&1 | clean >> examples/runs/audit-verification.txt
done
cp runs/approval-pending/APPROVALS-PENDING.md examples/runs/approval-pending.APPROVALS-PENDING.md
cp runs/brownfield/release/CHANGE.patch examples/runs/brownfield.CHANGE.patch
cp runs/ambiguous/release/CHANGE.patch examples/runs/ambiguous.CHANGE.patch
cp runs/greenfield/release/MANIFEST.json examples/runs/greenfield.MANIFEST.json
cp runs/greenfield/workspace/docs/API.md examples/runs/greenfield.generated-API.md
"$A" metrics 2>&1 | clean > examples/runs/metrics-aggregate.json
# portable in-place edit (GNU and BSD/macOS): perl instead of sed -i
for f in examples/runs/*.md examples/runs/*.json; do ROOT="$ROOT" perl -pi -e 's/\Q$ENV{ROOT}\E\///g' "$f"; done
echo "examples regenerated"
