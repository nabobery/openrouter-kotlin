package com.nabobery.openrouter.bench

import com.nabobery.openrouter.ChatRequest
import com.nabobery.openrouter.ChatResult
import com.nabobery.openrouter.chat.ChatStreamEvent
import com.nabobery.openrouter.chat.send
import com.nabobery.openrouter.chat.stream
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Runtime budgets from PRD §7, measured over the deterministic [Fixtures] fake transport (no network): first-event
 * latency, streaming decode throughput, and buffered decode. On the JVM the allocation rate of the throughput
 * benchmark is captured by JMH's `gc` profiler (`gc.alloc.rate.norm`, bytes/op). Baselines and tolerances live in
 * docs/budgets/runtime.json (latency/allocation are noisy → wide tolerance; a regression beyond the tolerance is a
 * real finding, anything under is host noise). Average-time / microseconds-per-op so the scores map directly onto
 * the `-microsPerOp` keys the budget gate checks (see scripts/bench-to-runtime.py).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
open class StreamingBenchmarks {
    private lateinit var sseBody: ByteArray
    private lateinit var bufferedBody: ByteArray
    private lateinit var request: ChatRequest

    @Setup
    fun setup() {
        sseBody = Fixtures.sseBody(count = 200)
        bufferedBody = Fixtures.bufferedBody(approxBytes = 20 * 1024)
        request = Fixtures.chatRequestFixture()
    }

    // Benchmarks are non-suspend and drive the coroutine with `runBlocking` (available on every benchmark target —
    // jvm, macosArm64, linuxX64; JS, which lacks it, is not a benchmark target). A `suspend @Benchmark` compiles to
    // a method with a `Continuation` parameter that JMH's method-shape check rejects. Each returns its result so the
    // harness consumes it.

    /** Time from starting collection to the FIRST decoded stream event — "first fixture event before completion". */
    @Benchmark
    fun firstEventLatency(): ChatStreamEvent =
        runBlocking { Fixtures.streamingClient(sseBody).chat.stream(request).first() }

    /** Average time to decode a full 200-event stream. Also the JVM `-prof gc` allocation-per-op bench. */
    @Benchmark
    fun chatStreamDecode200Events(): Int =
        runBlocking { Fixtures.streamingClient(sseBody).chat.stream(request).count() }

    /** One non-streaming completion over a ~20 KB buffered body. */
    @Benchmark
    fun bufferedChatDecode(): ChatResult =
        runBlocking { Fixtures.bufferedClient(bufferedBody).chat.send(request) }
}
