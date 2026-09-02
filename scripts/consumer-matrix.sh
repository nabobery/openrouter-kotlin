#!/usr/bin/env bash
# Resolves the PUBLISHED coordinates from an isolated repository (or a validated Central deployment) in a standalone
# Gradle build with its own Gradle user home, so ~/.gradle caches and project substitution can never mask a staging
# defect. Usage:
#   scripts/consumer-matrix.sh --repo build/publication-repository [--version V] [--dry-run]
#   scripts/consumer-matrix.sh --central-deployment <id> [--version V]   # needs CENTRAL_TOKEN in the environment
#   scripts/consumer-matrix.sh --repo https://repo1.maven.org/maven2/ --public   # post-publish, no credentials
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=""
CENTRAL_DEPLOYMENT=""
VERSION=""
DRY_RUN=0
PUBLIC=0
while [ $# -gt 0 ]; do
    case "$1" in
        --repo) REPO="$2"; shift 2 ;;
        --central-deployment) CENTRAL_DEPLOYMENT="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --public) PUBLIC=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -n "$VERSION" ] || VERSION="$(python3 scripts/release-version.py get)"

if [ -n "$CENTRAL_DEPLOYMENT" ]; then
    # A SPECIFIC validated (not-yet-published) deployment, served by the Portal as a read-only Maven repo. The path is
    # /deployment/<id>/download/ — singular, id BEFORE download. (The plural /deployments/download/ form omits the id
    # and searches ANY validated deployment for a requested file, which is not what we want here.) See
    # https://central.sonatype.org/publish/publish-portal-api/#manually-testing-a-deployment-bundle
    REPO_URL="https://central.sonatype.com/api/v1/publisher/deployment/$CENTRAL_DEPLOYMENT/download/"
elif [ -n "$REPO" ]; then
    case "$REPO" in
        http://*|https://*|file://*) REPO_URL="$REPO" ;;
        *) REPO_URL="file://$PWD/$REPO/" ;;
    esac
else
    echo "pass --repo <dir|url> or --central-deployment <id>" >&2
    exit 2
fi

# Host-agnostic tasks: JVM run, JS compile, and the three cross-compiling native compiles. The JS consumer COMPILES
# (proving the published coordinates resolve and compile on Kotlin/JS) rather than executing under Node: KGP downloads
# Node/Yarn from plugin-added project repositories, which the strict FAIL_ON_PROJECT_REPOS isolation forbids, and the
# host's Yarn 4 is incompatible with KGP's Yarn-Classic invocation. The identical smoke DOES execute on JVM + native.
tasks=(":jvm:run" ":js:compileKotlinJs" ":native:compileKotlinLinuxX64" ":native:compileKotlinLinuxArm64" ":native:compileKotlinMingwX64")
# Host-specific run/link tasks.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) tasks+=(":apple:runDebugExecutableMacosArm64" ":apple:linkDebugExecutableIosSimulatorArm64") ;;
    Linux-x86_64) tasks+=(":native:runDebugExecutableLinuxX64") ;;
    Linux-aarch64) tasks+=(":native:runDebugExecutableLinuxArm64") ;;
    MINGW*|MSYS*|CYGWIN*) tasks+=(":native:runDebugExecutableMingwX64") ;;
esac
if [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then tasks+=(":android:compileAndroidMain" ":android:testAndroidHostTest"); fi

if [ "$DRY_RUN" = 1 ]; then
    # Emit the resolved repository URL (asserted by consumer_matrix_test.sh) plus the host task list. No credentials
    # are needed to compute either, so a dry run of a Central deployment does not require CENTRAL_TOKEN.
    echo "# repo: $REPO_URL"
    printf '%s\n' "${tasks[@]}"
    exit 0
fi

# Credentials are required only to ACTUALLY resolve from a parked (non-public) Central deployment.
if [ -n "$CENTRAL_DEPLOYMENT" ] && [ "$PUBLIC" != 1 ] && [ -z "${CENTRAL_TOKEN:-}" ]; then
    echo "CENTRAL_TOKEN required for a Central deployment" >&2
    exit 2
fi

mkdir -p build
# --max-workers=1 serializes: with --refresh-dependencies, parallel native compiles can race on the first download of
# a shared klib and fail spuriously (each target succeeds standalone). Correctness, not speed, is the goal here.
./gradlew -p publication/consumers --gradle-user-home build/consumer-gradle-home --refresh-dependencies --no-daemon \
    --max-workers=1 --console=plain -PconsumerRepository="$REPO_URL" -PopenrouterVersion="$VERSION" "${tasks[@]}" | tee build/consumer-matrix.log
grep -q "openrouter-kotlin OK: resolved" build/consumer-matrix.log
