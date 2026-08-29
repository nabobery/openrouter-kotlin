#!/usr/bin/env bash
# Fetch the canonical OpenRouter OpenAPI document with size/format/digest reporting (spec-sync doc "Drift workflow").
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://openrouter.ai/openapi.yaml"
OUT="${1:-spec/openapi.yaml}"
TMP="$(mktemp)"
curl --fail --silent --show-error --location --max-time 120 --max-filesize 16777216 --proto '=https' --tlsv1.2 "$URL" -o "$TMP"
# Validate it is an OpenAPI document by the top-level `openapi:` key. Upstream does not guarantee key
# ordering (as of 2026-08-29 the document starts with `components:`, with `openapi: '3.1.0'` further down),
# so match the key anywhere at column 0 rather than only in the first bytes.
grep -q '^openapi:' "$TMP" || { echo "not an OpenAPI YAML document (no top-level openapi: key)" >&2; exit 1; }
SHA="$(shasum -a 256 "$TMP" | cut -d' ' -f1)"
SIZE="$(wc -c < "$TMP" | tr -d ' ')"
OPS="$(grep -c 'operationId:' "$TMP")"
mv "$TMP" "$OUT"
printf 'source=%s\nsha256=%s\nsizeBytes=%s\noperations=%s\nretrievedAt=%s\n' "$URL" "$SHA" "$SIZE" "$OPS" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
