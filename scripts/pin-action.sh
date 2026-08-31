#!/usr/bin/env bash
# Usage: scripts/pin-action.sh owner/repo vX.Y.Z  ->  prints "owner/repo@<commit-sha> # vX.Y.Z"
#
# Resolves a release tag to the *commit* SHA that a workflow must pin. Annotated tags
# (e.g. gradle/actions v6.3.0) point at a tag object, not a commit, so this dereferences
# the tag object to its target commit. Lightweight tags already point at the commit.
set -euo pipefail
repo="$1"; tag="$2"
ref="$(gh api "repos/$repo/git/ref/tags/$tag")"
type="$(printf '%s' "$ref" | python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["type"])')"
sha="$(printf '%s' "$ref" | python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["sha"])')"
if [ "$type" = "tag" ]; then
  sha="$(gh api "repos/$repo/git/tags/$sha" --jq '.object.sha')"
fi
printf '%s@%s # %s\n' "$repo" "$sha" "$tag"
