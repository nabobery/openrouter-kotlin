# ADR 0004: Use Flow streaming, typed failures, replay-aware retries, and layered deadlines

## Status

Accepted.

## Decision

- Curated streaming functions return cold `Flow` values.
- Generated streaming preserves exact wire event types and SSE metadata where useful.
- In-band protocol error events remain values; failures that terminate transport or decoding throw typed exceptions.
- `CancellationException` identity is preserved and never wrapped or retried.
- Retry decisions consider operation metadata, request-body replayability, delivery evidence, response consumption, and
  caller policy.
- A stream is never automatically restarted after an event is emitted.
- The SDK supports logical-call total, physical-attempt, and stream-idle deadlines.
- Engine-specific connection and socket timeouts remain part of consumer Ktor configuration.

## Consequences

The Kotlin API feels native while retaining wire fidelity. Retry defaults may be more conservative than current
official generated SDKs because ambiguous inference replays can duplicate spend.

