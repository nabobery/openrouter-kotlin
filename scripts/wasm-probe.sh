#!/usr/bin/env bash
# Probes whether :sdk can compile a wasmJs target yet. wasmJs is "declared, not published" (ADR 0007): the
# kotlin-sdkgen runtime publishes no wasmJs variant, so this is EXPECTED TO FAIL today with a dependency-resolution
# error. It will light up the day the runtime ships wasmJs. Works on a temporary patched copy of sdk/build.gradle.kts
# and always restores the original.
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_FILE="sdk/build.gradle.kts"
BACKUP="$(mktemp)"
cp "$BUILD_FILE" "$BACKUP"
restore() { cp "$BACKUP" "$BUILD_FILE"; rm -f "$BACKUP"; }
trap restore EXIT

# Insert a wasmJs target next to the js target.
python3 - "$BUILD_FILE" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
if "wasmJs" not in text:
    text = text.replace("    js {", "    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)\n    wasmJs { nodejs() }\n    js {", 1)
    open(path, "w").write(text)
PY

echo "==> Probing :sdk:compileKotlinWasmJs (expected to FAIL until kotlin-sdkgen publishes a wasmJs runtime)"
if ./gradlew :sdk:compileKotlinWasmJs --console=plain > /tmp/wasm-probe.log 2>&1; then
    echo "UNEXPECTED: wasmJs compiled — the runtime now ships wasmJs. Promote wasmJs to Tier 3-tested (ADR 0007)."
    exit 0
else
    echo "EXPECTED FAILURE. First resolution error:"
    grep -iE "Could not (find|resolve)|no matching variant|wasmJs|kotlin-sdkgen" /tmp/wasm-probe.log | head -3 || tail -3 /tmp/wasm-probe.log
    exit 0
fi
