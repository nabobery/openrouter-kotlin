#!/usr/bin/env bash
# Fetch → compare → (if changed) re-pin, regenerate twice (determinism), re-baseline, refresh dumps/dashboards.
# Exit 0 = no upstream change; 10 = changed and fully re-baselined; 20 = changed but generation failed (blocked).
# DRIFT_WORKTREE=<git-ref>: run inside a temporary worktree at <ref> (never touches the caller's checkout).
#
# This is the orchestrator both the drift workflow and a human re-pin run. It NEVER overwrites or restores
# tracked files in the caller's checkout except in its documented default (in-place) mode. The rehearsal mode
# (DRIFT_WORKTREE set) does all its work inside a throwaway `git worktree` it creates and removes itself.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${DRIFT_WORKTREE:-}" ] && [ -z "${DRIFT_IN_WORKTREE:-}" ]; then
  # The worktree is created from HEAD (so this script, spec-pin.py, and the current build exist in it) and only the
  # spec inputs + API dumps are then taken from the rehearsal ref INSIDE the worktree. The caller's checkout is
  # never written to; the trap removes exactly this worktree.
  rehearsal_out="${DRIFT_OUT:-build/drift}"
  case "$rehearsal_out" in /*) ;; *) rehearsal_out="$PWD/$rehearsal_out" ;; esac
  mkdir -p "$rehearsal_out"
  wt="$(mktemp -d "${TMPDIR:-/tmp}/openrouter-drift.XXXXXX")/worktree"
  git worktree add --detach "$wt" HEAD >/dev/null
  trap 'git worktree remove --force "$wt" >/dev/null 2>&1 || true' EXIT
  git -C "$wt" checkout "$DRIFT_WORKTREE" -- spec sdk/api
  # The nested run exits 0/10/20 by design; disable errexit around it so those expected non-zero statuses are
  # captured (and the patch/summary below still run) instead of terminating this outer script (set -e).
  set +e
  ( cd "$wt" && DRIFT_IN_WORKTREE=1 DRIFT_OUT="$rehearsal_out" bash scripts/drift-refresh.sh )
  rc=$?
  set -e
  ( cd "$wt" && git add -A -- spec sdk/api docs/coverage/operation-coverage.md docs/compat 2>/dev/null && git diff --cached --no-renames > "$rehearsal_out/drift.patch" ) || true
  echo "REHEARSAL: worktree $wt (removed on exit); patch at $rehearsal_out/drift.patch"
  exit "$rc"
fi

OUT="${DRIFT_OUT:-build/drift}"; mkdir -p "$OUT"
current="$(python3 scripts/spec-pin.py read-source | sed 's/^sha256=//')"
bash scripts/fetch-upstream-spec.sh "$OUT/upstream.yaml" > "$OUT/fetch.txt"
new="$(grep '^sha256=' "$OUT/fetch.txt" | cut -d= -f2)"
size="$(grep '^sizeBytes=' "$OUT/fetch.txt" | cut -d= -f2)"
ops="$(grep '^operations=' "$OUT/fetch.txt" | cut -d= -f2)"
at="$(grep '^retrievedAt=' "$OUT/fetch.txt" | cut -d= -f2)"
if [ "$new" = "$current" ]; then echo "NO-CHANGE: upstream digest $new matches the pin"; exit 0; fi
echo "CHANGED: $current -> $new ($size bytes, $ops operationIds)"
cp spec/openapi.yaml "$OUT/before-openapi.yaml"
cp -R "$(readlink -f sdk/build/generated/sdkgen/openrouter/sources 2>/dev/null || true)" "$OUT/before-sources" 2>/dev/null || true
cp "$OUT/upstream.yaml" spec/openapi.yaml
python3 scripts/spec-pin.py update-source --sha "$new" --size "$size" --retrieved-at "$at" \
  --provenance "Retrieved directly from https://openrouter.ai/openapi.yaml on ${at%%T*} via scripts/drift-refresh.sh (previous pin ${current:0:8}…)."
if ! ./gradlew :sdk:generateOpenrouterSdk --rerun-tasks --console=plain > "$OUT/generate-1.log" 2>&1; then
  echo "BLOCKED: generation failed after re-pin — see $OUT/generate-1.log and build/reports/problems/problems-report.html"
  cp build/reports/problems/problems-report.html "$OUT/" 2>/dev/null || true
  exit 20
fi
addr1="$(readlink sdk/build/generated/sdkgen/openrouter/sources | sed 's#.*/##')"
if ! ./gradlew :sdk:generateOpenrouterSdk --rerun-tasks --console=plain > "$OUT/generate-2.log" 2>&1; then
  echo "BLOCKED: second generation failed — see $OUT/generate-2.log and build/reports/problems/problems-report.html"
  cp build/reports/problems/problems-report.html "$OUT/" 2>/dev/null || true
  exit 20
fi
addr2="$(readlink sdk/build/generated/sdkgen/openrouter/sources | sed 's#.*/##')"
[ "$addr1" = "$addr2" ] || { echo "NONDETERMINISTIC: $addr1 != $addr2"; exit 20; }
files="$(find -L sdk/build/generated/sdkgen/openrouter/sources -type f | wc -l | tr -d ' ')"
kt="$(find -L sdk/build/generated/sdkgen/openrouter/sources -name '*.kt' | wc -l | tr -d ' ')"
python3 scripts/spec-pin.py update-lock --address "$addr1" --files "$files" --kotlin-files "$kt"
./gradlew :sdk:apiDump --console=plain > "$OUT/apidump.log" 2>&1
python3 scripts/coverage-dashboard.py
bash scripts/check-drift.sh > "$OUT/check-drift.log" 2>&1
cp -R "$(readlink -f sdk/build/generated/sdkgen/openrouter/sources)" "$OUT/after-sources"
echo "REBASELINED: $current -> $new; snapshot $addr1 ($files files / $kt kt)"
exit 10
