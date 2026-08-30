#!/usr/bin/env bash
# Measures generated-code compile time and compiler-warning count from Kotlin build reports (no HTML parsing).
# Recompiles a set of targets with --rerun-tasks, reads each compile task's duration from the JSON build report,
# and counts kotlinc warnings from the console. Writes docs/budgets/compile-times.json and docs/budgets/warnings.json
# by default (pass --out-dir to redirect). Pass target simple-names as args to override the default set.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR="docs/budgets"
TARGETS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        *) TARGETS+=("$1"); shift ;;
    esac
done
if [ ${#TARGETS[@]} -eq 0 ]; then
    TARGETS=(compileKotlinJvm compileKotlinJs compileKotlinLinuxX64)
    if [ "$(uname)" = "Darwin" ]; then TARGETS+=(compileKotlinMacosArm64 compileKotlinIosSimulatorArm64); fi
fi

REPORT_DIR="build/reports/kotlin-build"
rm -f "$REPORT_DIR"/*.json 2>/dev/null || true
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

GRADLE_TASKS=()
for t in "${TARGETS[@]}"; do GRADLE_TASKS+=(":sdk:$t"); done
./gradlew "${GRADLE_TASKS[@]}" --rerun-tasks --console=plain 2>&1 | tee "$LOG"

# kotlinc warnings only: real compiler warnings begin `w: file://…`; drop the KGP/Gradle config lines (`w: ⚠️ …`).
WARN="$(grep '^w: ' "$LOG" | grep -vc '⚠️' || true)"

mkdir -p "$OUT_DIR"
REPORT="$(ls -t "$REPORT_DIR"/*.json | head -1)"
python3 - "$REPORT" "$WARN" "$OUT_DIR" "${TARGETS[@]}" <<'PY'
import json, pathlib, sys
report, warn, out_dir = sys.argv[1], int(sys.argv[2]), sys.argv[3]
targets = sys.argv[4:]
records = json.load(open(report)).get("buildOperationRecord", [])
by_path = {r.get("path"): r.get("totalTimeMs") for r in records if isinstance(r, dict)}
times = {}
for t in targets:
    ms = by_path.get(f":sdk:{t}")
    if ms is not None:
        times[t] = ms
pathlib.Path(out_dir, "compile-times.json").write_text(json.dumps(times, indent=2, sort_keys=True) + "\n")
pathlib.Path(out_dir, "warnings.json").write_text(
    json.dumps({"kotlin-compiler-warnings": warn}, indent=2, sort_keys=True) + "\n"
)
print(f"compile-times: {times}")
print(f"kotlin-compiler-warnings: {warn}")
PY
