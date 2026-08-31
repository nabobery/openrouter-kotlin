# Guides

Task-oriented documentation for the OpenRouter Kotlin SDK, organised by the [Diátaxis](https://diataxis.fr/) model:

- **Tutorials** — learning-oriented, step-by-step, with a stated outcome. Start here.
- **How-to guides** — goal-oriented recipes that assume you have done the tutorials.

Every code block in these guides is **real, compiled Kotlin**: the examples are `// region` blocks in the
`:samples:docs` module (and the Android/iOS sample modules), injected into this Markdown by
`scripts/docs-snippets.py` and compiled by `samplesCheck` / CI. A guide can therefore never drift from a compiling
API — a stale block fails the `docs-snippets.py check` gate. (We chose this `kt → md` direction over a `md → kt`
tool such as kotlinx-knit so the examples refactor with the IDE and add no plugin to the build classpath.)

## Tutorials

- [Your first chat request](tutorials/first-chat-request.md)
- [Streaming with Flow](tutorials/streaming-with-flow.md)

## How-to guides

- [Choose a Ktor engine](how-to/choose-a-ktor-engine.md)
- [Configure retries and deadlines](how-to/configure-retries-and-deadlines.md)
- [Attribution and headers](how-to/attribution-and-headers.md)
- [Handle errors](how-to/handle-errors.md)
- [Paginate](how-to/paginate.md)
- [Upload and download files](how-to/files-upload-and-download.md)
- [Test with a fake transport](how-to/test-with-a-fake-transport.md)
- [Use the exact generated API](how-to/use-the-exact-generated-api.md)
- [Android lifecycle](how-to/android-lifecycle.md)
- [iOS Swift consumer](how-to/ios-swift-consumer.md)

For API reference material, use the [module/package overview](../api/module.md), source KDoc, and the
[exact generated API guide](how-to/use-the-exact-generated-api.md).
