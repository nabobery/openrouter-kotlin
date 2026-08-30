#!/usr/bin/env bash
# Builds the sample XCFramework and compiles/links the Swift consumer against it (macOS host only; no network).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :samples:ios:shared:assembleOpenRouterSampleDebugXCFramework --console=plain
( cd samples/ios/SwiftConsumer && swift build -c debug 2>&1 | tail -5 )
du -sh samples/ios/shared/build/XCFrameworks/debug/OpenRouterSample.xcframework | sed 's/^/xcframework size: /'
