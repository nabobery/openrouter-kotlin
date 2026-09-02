#!/usr/bin/env bash
# The exact sequence the release job runs, minus signing/upload — usable locally, credential-free. Stages both
# published modules into an isolated file repository, proves the inventory, resolves the published coordinates in the
# isolated consumer matrix, builds the SBOM, and packs a deterministic Central bundle. The release workflow sets
# RELEASE_ARGS=-Popenrouter.release=true and REQUIRE_SIGNATURES=1 (with signing keys in the environment); the local
# rehearsal leaves them empty. On a memory-constrained host, set GRADLE_PUBLISH_ARGS="--max-workers=1" to serialize
# the native compiles (macos-15 CI has the headroom to omit it).
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(python3 scripts/release-version.py get)"
REPO=build/publication-repository
rm -rf "$REPO" build/consumer-gradle-home

# A SNAPSHOT rehearsal must tell the bundler the version is intentionally a SNAPSHOT; a real release version is not.
ALLOW_SNAPSHOT=""
case "$VERSION" in *-SNAPSHOT) ALLOW_SNAPSHOT="--allow-snapshot" ;; esac

echo "== release-rehearsal: version $VERSION =="

./gradlew :sdk:generateOpenrouterSdk --rerun-tasks --console=plain
bash scripts/check-drift.sh

# shellcheck disable=SC2086  # RELEASE_ARGS / GRADLE_PUBLISH_ARGS are intentionally word-split.
./gradlew publishAllPublicationsToIsolatedRepository \
    --init-script publication/isolated-repository.init.gradle.kts \
    -PpublicationRepository="$REPO" -Popenrouter.androidTarget=true \
    ${RELEASE_ARGS:-} ${GRADLE_PUBLISH_ARGS:-} --console=plain

python3 scripts/publication-inventory.py check publication/expected-artifacts.json "$REPO" \
    --version "$VERSION" ${REQUIRE_SIGNATURES:+--require-signatures}
python3 scripts/publication-inventory.py write "$REPO" build/publication-inventory.json --version "$VERSION"

bash scripts/consumer-matrix.sh --repo "$REPO" --version "$VERSION"

./gradlew :publication:sbom:cyclonedxDirectBom --console=plain
mkdir -p build/sbom
cp publication/sbom/build/reports/cyclonedx-direct/bom.json \
    publication/sbom/build/reports/cyclonedx-direct/bom.xml build/sbom/

python3 scripts/central-bundle.py "$REPO" build/central-bundle.zip --version "$VERSION" $ALLOW_SNAPSHOT

echo "== release-rehearsal: OK =="
