#!/usr/bin/env python3
"""Tests for the benchmark->runtime-budget adapter (scripts/bench-to-runtime.py)."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).with_name("bench-to-runtime.py")
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("bench_to_runtime", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
adapter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adapter)


def _entry(name: str, score: float, alloc: float | None = None) -> dict:
    # Mirrors JMH's JSON shape: primaryMetric holds score/unit; the gc profiler's results live in a
    # `secondaryMetrics` map at the entry level (sibling of primaryMetric).
    entry: dict = {
        "benchmark": f"com.nabobery.openrouter.bench.StreamingBenchmarks.{name}",
        "primaryMetric": {"score": score, "scoreUnit": "us/op"},
        "secondaryMetrics": {},
    }
    if alloc is not None:
        entry["secondaryMetrics"]["gc.alloc.rate.norm"] = {"score": alloc, "scoreUnit": "B/op"}
    return entry


class ConvertTest(unittest.TestCase):
    def test_latency_maps_to_micros_per_op(self) -> None:
        out = adapter.convert([[_entry("firstEventLatency", 52.61)]])
        self.assertEqual({"firstEventLatency-microsPerOp": 52.61}, out)

    def test_gc_profiler_normalizes_stream_allocation_per_event(self) -> None:
        out = adapter.convert([[_entry("chatStreamDecode200Events", 293.76, alloc=40960.0)]])
        self.assertEqual(
            {
                "chatStreamDecode200Events-microsPerOp": 293.76,
                "chatStreamDecode200Events-allocBytesPerEvent": 204.8,
            },
            out,
        )

    def test_missing_gc_omits_alloc_key(self) -> None:
        out = adapter.convert([[_entry("bufferedChatDecode", 22.89)]])
        self.assertNotIn("bufferedChatDecode-allocBytesPerOp", out)

    def test_wrong_unit_is_rejected(self) -> None:
        bad = [{"benchmark": "x.y.z.foo", "primaryMetric": {"score": 1.0, "scoreUnit": "ops/s"}}]
        with self.assertRaises(SystemExit):
            adapter.convert([bad])

    def test_multiple_reports_merge(self) -> None:
        out = adapter.convert([[_entry("firstEventLatency", 50.0)], [_entry("bufferedChatDecode", 20.0)]])
        self.assertEqual(
            {"firstEventLatency-microsPerOp": 50.0, "bufferedChatDecode-microsPerOp": 20.0}, out
        )


if __name__ == "__main__":
    unittest.main()
