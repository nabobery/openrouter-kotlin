#!/usr/bin/env bash
# Generation drift gate (plugin-native model).
#
# The published kotlin-sdkgen 0.3.0 plugin generates a content-addressed snapshot under
# build/ rather than a checked-in source tree, so drift is detected by regenerating from
# the pinned spec and comparing the produced snapshot against the committed baseline in
# spec/generated.lock.json. EVERY field in that lock is validated here — none may silently
# go stale. A mismatch means the generated surface changed without the baseline being
# updated (i.e. drift).
set -euo pipefail

cd "$(dirname "$0")/.."

LOCK="spec/generated.lock.json"
SNAPSHOT_LINK="sdk/build/generated/sdkgen/openrouter/sources"

json_str() { grep -o "\"$1\": *\"[a-f0-9]\{64\}\"" "$LOCK" | grep -o '[a-f0-9]\{64\}'; }
json_int() { grep -o "\"$1\": *[0-9]\+" "$LOCK" | grep -o '[0-9]\+'; }

echo "==> Regenerating from the pinned spec (spec/openapi.yaml @ pinned sha256)"
./gradlew :sdk:generateOpenrouterSdk --rerun-tasks --console=plain

actual_address="$(readlink "$SNAPSHOT_LINK" | sed 's#.*/##')"
actual_files="$(find -L "$SNAPSHOT_LINK" -type f | wc -l | tr -d ' ')"
actual_kt="$(find -L "$SNAPSHOT_LINK" -name '*.kt' | wc -l | tr -d ' ')"

expected_address="$(json_str snapshotContentAddress)"
expected_files="$(json_int fileCount)"
expected_kt="$(json_int kotlinFileCount)"

status=0
check() { # name expected actual
    printf '==> %-22s expected=%s actual=%s\n' "$1" "$2" "$3"
    [ "$2" = "$3" ] || { echo "    MISMATCH: $1" >&2; status=1; }
}
check "snapshotContentAddress" "$expected_address" "$actual_address"
check "fileCount"              "$expected_files"   "$actual_files"
check "kotlinFileCount"        "$expected_kt"      "$actual_kt"

if [ "$status" -ne 0 ]; then
    echo "DRIFT: regenerated surface does not match $LOCK." >&2
    echo "If this change is intentional, update $LOCK to the new values." >&2
    exit 1
fi

echo "NO-DRIFT: generated surface reproduces the committed baseline (address + file counts)."
