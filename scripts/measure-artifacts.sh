#!/usr/bin/env bash
# Measures what a consumer actually downloads: publishes :sdk's per-target artifacts to a throwaway local Maven
# repo, then emits { "<artifact-file>": <bytes>, ... } for every *.jar / *.klib / *.aar / *-metadata.jar (excluding
# -sources / -javadoc jars). Writes build/budgets/measured.json. Apple-only publications are produced on a macOS
# host; pass --merge <file.json> to fold an earlier (e.g. Linux) measurement in.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="build/budgets/measured.json"
MERGE=""
EXTRA_GRADLE_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --merge) MERGE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        *) EXTRA_GRADLE_ARGS+=("$1"); shift ;;
    esac
done

TMP_REPO="$(mktemp -d)"
trap 'rm -rf "$TMP_REPO"' EXIT

./gradlew :sdk:publishToMavenLocal -Dmaven.repo.local="$TMP_REPO" --console=plain ${EXTRA_GRADLE_ARGS[@]+"${EXTRA_GRADLE_ARGS[@]}"}

mkdir -p "$(dirname "$OUT")"
# Emit a JSON object of artifact basename -> byte size for the primary consumable artifacts.
python3 - "$TMP_REPO" "$OUT" "$MERGE" <<'PY'
import json, os, pathlib, re, sys
repo, out, merge = sys.argv[1], sys.argv[2], sys.argv[3]
root = pathlib.Path(repo) / "io" / "github" / "nabobery"
# Strip the embedded version (e.g. sdk-jvm-0.1.0-SNAPSHOT.jar -> sdk-jvm.jar) so the baseline is version-stable.
version = re.compile(r"-\d+\.\d+\.\d+(?:-SNAPSHOT)?(?=(?:-metadata)?\.(?:jar|klib|aar)$)")
sizes = {}
if root.exists():
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        name = path.name
        if name.endswith("-sources.jar") or name.endswith("-javadoc.jar"):
            continue
        if name.endswith(".jar") or name.endswith(".klib") or name.endswith(".aar") or name.endswith("-metadata.jar"):
            sizes[version.sub("", name)] = path.stat().st_size
if merge and os.path.exists(merge):
    sizes.update(json.load(open(merge)))
pathlib.Path(out).write_text(json.dumps(sizes, indent=2, sort_keys=True) + "\n")
print(f"Measured {len(sizes)} artifact(s) -> {out}")
PY
