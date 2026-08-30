// swift-tools-version:5.9
import PackageDescription

// A Swift executable that consumes the sample-owned OpenRouter facade through its XCFramework. `swift build` on a
// macOS host uses the framework's macosArm64 slice; the iosArm64 / iosSimulatorArm64 slices serve real iOS apps.
// The XCFramework is produced by `:samples:ios:shared:assembleOpenRouterSampleDebugXCFramework` (see
// scripts/ios-consumer-check.sh). Compile-and-link is the CI evidence; the network call is opt-in.
let package = Package(
    name: "SwiftConsumer",
    platforms: [.macOS(.v13)],
    targets: [
        .binaryTarget(
            name: "OpenRouterSample",
            path: "../shared/build/XCFrameworks/debug/OpenRouterSample.xcframework"
        ),
        .executableTarget(
            name: "SwiftConsumer",
            dependencies: ["OpenRouterSample"]
        ),
    ]
)
