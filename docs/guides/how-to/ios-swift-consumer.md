# iOS Swift consumer

Kotlin/Native exposes a KMP module to Swift as an XCFramework. A small sample-owned facade wraps the SDK so Swift
callers see idiomatic `async` methods (a Kotlin `suspend fun` is bridged to Swift `async`). The facade is consumed
from Swift like any other framework:

<!-- snippet: samples/ios/SwiftConsumer/Sources/SwiftConsumer/main.swift#ios-consumer -->
```swift
let facade = OpenRouterFacade()

if let apiKey = ProcessInfo.processInfo.environment["OPENROUTER_API_KEY"], !apiKey.isEmpty {
    let done = DispatchSemaphore(value: 0)
    Task {
        do {
            let reply = try await facade.hello(apiKey: apiKey, model: "openrouter/free")
            print(reply)
        } catch {
            print("error: \(error)")
        }
        facade.close()
        done.signal()
    }
    done.wait()
} else {
    print("OPENROUTER_API_KEY not set — facade constructed and XCFramework linked; skipping the network call.")
    facade.close()
}
```
<!-- /snippet -->

Compile-and-link on a macOS host is the CI evidence (the network call is opt-in behind `OPENROUTER_API_KEY`). The
XCFramework is produced by the shared module's `assembleOpenRouterSampleDebugXCFramework` task and consumed as a
binary target — see [`samples/ios`](../../../samples/ios/) and `scripts/ios-consumer-check.sh`.

Because Swift does not see Kotlin's coroutine `Flow` directly, expose collected results (or a callback) from the
facade rather than returning a `Flow` across the boundary.
