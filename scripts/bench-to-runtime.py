#!/usr/bin/env python3
"""Map a kotlinx-benchmark / JMH JSON report onto the runtime-budget schema (docs/budgets/runtime.json).

The benchmark suite (:benchmarks) is average-time / microseconds-per-op, so each `primaryMetric.score` becomes a
`<method>-microsPerOp` entry. When the JVM run was taken with the JMH `gc` profiler (`-prof gc`), the
`gc.alloc.rate.norm` secondary metric becomes an allocation entry. The 200-event stream benchmark is normalized to
bytes/event; the one-event and buffered benchmarks remain bytes/op. The output is fed to
`scripts/budgets.py check docs/budgets/runtime.json <out>`.

Usage:
  bench-to-runtime.py <report.json> [<report.json> ...] --out <runtime.json>

Each report is the JMH-format array kotlinx-benchmark writes (JVM: build/reports/benchmarks/main/<ts>/jvm.json, or
the `-rff` file of a `java -jar …-jmh.jar` run). Multiple reports are merged (later files win on key collision).
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys


EVENTS_PER_OPERATION = {"chatStreamDecode200Events": 200}


def _short_name(benchmark: str) -> str:
    """`com.nabobery.…StreamingBenchmarks.firstEventLatency` -> `firstEventLatency`."""
    return benchmark.rsplit(".", 1)[-1]


def _alloc_metric(secondary: dict) -> float | None:
    """The JMH gc profiler reports allocation as `·gc.alloc.rate.norm` (a leading middot); match it tolerantly."""
    for key, metric in secondary.items():
        if "gc.alloc.rate.norm" in key:
            return float(metric["score"])
    return None


def convert(reports: list[list[dict]]) -> dict[str, float]:
    """Fold JMH report arrays into the runtime-budget dict."""
    out: dict[str, float] = {}
    for report in reports:
        for entry in report:
            name = _short_name(entry["benchmark"])
            primary = entry["primaryMetric"]
            unit = primary.get("scoreUnit", "")
            if unit != "us/op":
                raise SystemExit(
                    f"benchmark '{name}' has scoreUnit '{unit}', expected 'us/op' "
                    f"(annotate @BenchmarkMode(AverageTime) + @OutputTimeUnit(MICROSECONDS))"
                )
            out[f"{name}-microsPerOp"] = round(float(primary["score"]), 2)
            # JMH puts the profiler results in a `secondaryMetrics` map at the entry level (sibling of primaryMetric).
            alloc = _alloc_metric(entry.get("secondaryMetrics", {}))
            if alloc is not None:
                event_count = EVENTS_PER_OPERATION.get(name)
                suffix = "allocBytesPerEvent" if event_count is not None else "allocBytesPerOp"
                out[f"{name}-{suffix}"] = round(alloc / (event_count or 1), 2)
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    reports = [json.loads(pathlib.Path(p).read_text()) for p in args.reports]
    measured = convert(reports)
    if not measured:
        raise SystemExit("no benchmark entries found in the report(s)")
    pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(args.out).write_text(json.dumps(measured, indent=2, sort_keys=True) + "\n")
    print(f"Wrote {len(measured)} runtime metric(s) -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
