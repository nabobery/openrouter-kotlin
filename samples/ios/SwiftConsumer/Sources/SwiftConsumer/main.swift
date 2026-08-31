import Foundation
import OpenRouterSample

// Constructs the sample facade over the XCFramework, then — only when OPENROUTER_API_KEY is set — awaits a one-shot
// completion (Kotlin `suspend fun` is exposed to Swift as `async`). With no key it just proves the framework links
// and the facade constructs, then releases the HTTP client. Compile-and-link is the CI evidence; the call is opt-in.
// region ios-consumer
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
// endregion
