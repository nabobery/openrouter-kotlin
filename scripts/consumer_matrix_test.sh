#!/usr/bin/env bash
# Bats-free test for scripts/consumer-matrix.sh: asserts the --dry-run task list for this host and that the consumer
# builds pin the same Kotlin/AGP versions as gradle/libs.versions.toml (a consumer has no access to the catalog, so
# the literals must be kept in lockstep by this test).
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "consumer_matrix_test: FAIL: $1" >&2; exit 1; }

# 1. Dry-run task list.
tasks="$(bash scripts/consumer-matrix.sh --dry-run --repo build/publication-repository)"
for expected in ":jvm:run" ":js:compileKotlinJs" ":native:compileKotlinLinuxX64" ":native:compileKotlinLinuxArm64" ":native:compileKotlinMingwX64"; do
    printf '%s\n' "$tasks" | grep -qx "$expected" || fail "missing host-agnostic task $expected"
done
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        for expected in ":apple:runDebugExecutableMacosArm64" ":apple:linkDebugExecutableIosSimulatorArm64"; do
            printf '%s\n' "$tasks" | grep -qx "$expected" || fail "missing apple task $expected on macOS arm64"
        done
        ;;
    Linux-x86_64) printf '%s\n' "$tasks" | grep -qx ":native:runDebugExecutableLinuxX64" || fail "missing linuxX64 run task" ;;
esac

# 2. A Central deployment resolves via the SPECIFIC-deployment Portal endpoint: /deployment/<id>/download/ (singular,
#    id before download). The plural /deployments/download/ form omits the id and would never resolve the bundle.
central_dry="$(bash scripts/consumer-matrix.sh --dry-run --central-deployment testdeploy123)"
printf '%s\n' "$central_dry" | grep -q "api/v1/publisher/deployment/testdeploy123/download/" \
    || fail "central deployment does not use the specific /deployment/<id>/download/ endpoint"
if printf '%s\n' "$central_dry" | grep -q "/deployments/download/"; then
    fail "central deployment uses the wrong plural /deployments/download/ endpoint"
fi

# 3. Consumer plugin versions (declared once in the root consumers build with `apply false`) match the catalog.
kotlin_version="$(grep -E '^kotlin = ' gradle/libs.versions.toml | sed -E 's/.*"([^"]+)".*/\1/')"
agp_version="$(grep -E '^agp = ' gradle/libs.versions.toml | sed -E 's/.*"([^"]+)".*/\1/')"
root_build="publication/consumers/build.gradle.kts"
grep -q "kotlin(\"multiplatform\") version \"$kotlin_version\"" "$root_build" || fail "consumers root does not pin Kotlin $kotlin_version"
grep -q "id(\"com.android.kotlin.multiplatform.library\") version \"$agp_version\"" "$root_build" || fail "consumers root does not pin AGP $agp_version"

echo "consumer_matrix_test: OK"
