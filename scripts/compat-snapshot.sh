#!/usr/bin/env bash
# Capture a before/after snapshot pair and render the layered compatibility report.
#
# Modes:
#   --before DIR --after DIR   the two dirs are already populated (capture format) — just render.
#   --base-ref <git-ref>       capture `before` from a throwaway worktree at <ref> (generated there) and
#                              `after` from the current tree, run oasdiff + sdkgen diff + jvmTest, then render.
#                              The ref is taken from the argument only and always quoted.
#
# oasdiff is optional: if it is not on PATH (and not at build/tools/oasdiff/oasdiff), the OpenAPI layer is
# reported as unavailable rather than faked. Propagates scripts/compat-report.py's exit code (0/1/3).
set -euo pipefail
cd "$(dirname "$0")/.."

BEFORE=""; AFTER=""; BASE_REF=""; REPORT="build/compat/report.md"
while [ $# -gt 0 ]; do
  case "$1" in
    --before) BEFORE="$2"; shift 2 ;;
    --after) AFTER="$2"; shift 2 ;;
    --base-ref) BASE_REF="$2"; shift 2 ;;
    --report) REPORT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

OASDIFF=""
if command -v oasdiff >/dev/null 2>&1; then OASDIFF="oasdiff"
elif [ -x build/tools/oasdiff/oasdiff ]; then OASDIFF="build/tools/oasdiff/oasdiff"; fi

# Resolve a path to absolute (the repo root is the CWD). Used so paths handed to the Gradle sdkgenDiff task are
# unambiguous — a JavaExec task resolves a relative path against the SDK subproject dir, not the repo root.
abspath() { case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s/%s\n' "$PWD" "$1" ;; esac; }

# capture <dir> <spec-yaml> <sources-symlink> <api-dir> <coverage-md>
capture() {
  local dir="$1" spec="$2" srcs="$3" apidir="$4" cov="$5"
  mkdir -p "$dir"
  [ -f "$spec" ] && cp "$spec" "$dir/openapi.yaml" || true
  local snap; snap="$(readlink -f "$srcs" 2>/dev/null || true)"
  if [ -n "$snap" ] && [ -d "$snap" ]; then
    [ -f "$snap/manifest.json" ] && cp "$snap/manifest.json" "$dir/manifest.json" || true
    ( cd "$snap" && find -L . -type f -print0 | sort -z | xargs -0 shasum -a 256 ) > "$dir/sources.txt" || true
  fi
  [ -f "$apidir/sdk.api" ] && cp "$apidir/sdk.api" "$dir/sdk.api" || true
  [ -f "$apidir/sdk.klib.api" ] && cp "$apidir/sdk.klib.api" "$dir/sdk.klib.api" || true
  [ -f "$cov" ] && cp "$cov" "$dir/operation-coverage.md" || true
}

run_oasdiff() {  # <before-spec> <after-spec> <out-dir>
  local b="$1" a="$2" out="$3"
  if [ -z "$OASDIFF" ]; then echo "oasdiff: unavailable (OpenAPI layer will be reported as such)"; return 0; fi
  # oasdiff exits non-zero when it FINDS breaking changes, so capture output regardless of exit code.
  "$OASDIFF" breaking -f json "$b" "$a" > "$out/oasdiff-breaking.json" 2>/dev/null || true
  "$OASDIFF" changelog -f json "$b" "$a" > "$out/oasdiff-changelog.json" 2>/dev/null || true
  [ -s "$out/oasdiff-breaking.json" ] || rm -f "$out/oasdiff-breaking.json"
  [ -s "$out/oasdiff-changelog.json" ] || rm -f "$out/oasdiff-changelog.json"
}

if [ -n "$BASE_REF" ]; then
  BEFORE="build/compat/before"; AFTER="build/compat/after"
  rm -rf "$BEFORE" "$AFTER"; mkdir -p "$BEFORE" "$AFTER"
  wt="$(mktemp -d "${TMPDIR:-/tmp}/openrouter-compat.XXXXXX")/wt"
  git worktree add --detach "$wt" "$BASE_REF" >/dev/null
  trap 'git worktree remove --force "$wt" >/dev/null 2>&1 || true; git worktree prune >/dev/null 2>&1 || true' EXIT
  ( cd "$wt" && ./gradlew :sdk:generateOpenrouterSdk --console=plain >/dev/null 2>&1 ) || {
    echo "base-ref generation failed in the worktree" >&2; exit 20; }
  # Generate the CURRENT tree too: on a clean CI checkout sdk/build/generated/.../sources does not yet exist, so
  # without this the `after` semantic/source layers would be captured as absent and silently reported "unavailable"
  # instead of gated. Both sides must produce real generated evidence for the gate to be meaningful.
  ./gradlew :sdk:generateOpenrouterSdk --console=plain >/dev/null 2>&1 || {
    echo "current-tree generation failed" >&2; exit 20; }
  capture "$BEFORE" "$wt/spec/openapi.yaml" "$wt/sdk/build/generated/sdkgen/openrouter/sources" "$wt/sdk/api" "$wt/docs/coverage/operation-coverage.md"
  capture "$AFTER" "spec/openapi.yaml" "sdk/build/generated/sdkgen/openrouter/sources" "sdk/api" "docs/coverage/operation-coverage.md"
  # Gate mode requires the generated semantic/source evidence on BOTH sides — never a silent "unavailable" pass.
  for side in "$BEFORE" "$AFTER"; do
    for artifact in openapi.yaml manifest.json sources.txt; do
      [ -s "$side/$artifact" ] || { echo "compat gate: required snapshot artifact missing: $side/$artifact" >&2; exit 20; }
    done
  done
  run_oasdiff "$BEFORE/openapi.yaml" "$AFTER/openapi.yaml" "$AFTER"
  if [ -f "$BEFORE/manifest.json" ] && [ -f "$AFTER/manifest.json" ]; then
    ./gradlew :sdk:sdkgenDiff -Pcompat.from="$(abspath "$BEFORE/manifest.json")" -Pcompat.to="$(abspath "$AFTER/manifest.json")" \
      -Pcompat.out="$(abspath "$AFTER/sdkgen-diff.json")" --console=plain >/dev/null 2>&1 || true
  fi
  if ./gradlew :sdk:jvmTest --console=plain >/dev/null 2>&1; then jvm=passed; else jvm=failed; fi
  printf '{"jvmTest":"%s","goldens":"%s"}\n' "$jvm" "$jvm" > "$AFTER/tests.json"
fi

[ -n "$BEFORE" ] && [ -n "$AFTER" ] || { echo "provide --before/--after or --base-ref" >&2; exit 2; }
python3 scripts/compat-report.py --before "$BEFORE" --after "$AFTER" --out "$REPORT"
