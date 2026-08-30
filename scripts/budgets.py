#!/usr/bin/env python3
"""Budget checker for JSON size/time baselines under docs/budgets/.

Subcommands:
  check  <baseline.json> <measured.json> [--tolerance F] [--allow-removed a,b]
             -> exit 1 with a report if any measured value exceeds its baseline * (1 + tolerance), is a new key not
                in the baseline (so a baseline can never silently grow), OR is a baseline key missing from the
                measurement (so an artifact can never silently disappear past the gate). A key may be intentionally
                dropped only by naming it in --allow-removed (comma-separated); otherwise a missing artifact fails.
  record <measured.json> <baseline.json>                  -> write measured as the new baseline.

Baselines are ceilings against regression, not performance targets: a deliberate change is landed by re-recording
the baseline in the same commit that explains why the number moved (same discipline as the coverage dashboard).
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys


def check(
    baseline: dict,
    measured: dict,
    tolerance: float = 0.10,
    allow_removed: set[str] | None = None,
) -> list[str]:
    """Return a list of failure messages (empty = pass)."""
    allow_removed = allow_removed or set()
    failures: list[str] = []
    for name, value in sorted(measured.items()):
        if name not in baseline:
            failures.append(f"new artifact '{name}' ({value}) is not in the baseline; re-record to accept it")
            continue
        ceiling = baseline[name] * (1 + tolerance)
        if value > ceiling:
            pct = (value / baseline[name] - 1) * 100 if baseline[name] else float("inf")
            failures.append(
                f"'{name}' = {value} exceeds baseline {baseline[name]} by {pct:.1f}% (ceiling {ceiling:.0f}, "
                f"tolerance {tolerance:.0%})"
            )
    # An artifact in the baseline but absent from the measurement is a silent disappearance (e.g. a target dropped
    # from the publication, or a measurement run that never built it). Fail unless it was deliberately allow-listed.
    for name in sorted(baseline.keys() - measured.keys() - allow_removed):
        failures.append(
            f"artifact '{name}' is in the baseline but missing from the measurement; if the drop is intended pass "
            f"--allow-removed {name} (or re-record the baseline)"
        )
    return failures


def _load(path: str) -> dict:
    return json.loads(pathlib.Path(path).read_text())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_check = sub.add_parser("check")
    p_check.add_argument("baseline")
    p_check.add_argument("measured")
    p_check.add_argument("--tolerance", type=float, default=0.10)
    p_check.add_argument(
        "--allow-removed",
        default="",
        help="comma-separated baseline keys permitted to be absent from the measurement",
    )
    p_record = sub.add_parser("record")
    p_record.add_argument("measured")
    p_record.add_argument("baseline")
    args = parser.parse_args(argv)

    if args.cmd == "check":
        allow_removed = {k.strip() for k in args.allow_removed.split(",") if k.strip()}
        failures = check(_load(args.baseline), _load(args.measured), args.tolerance, allow_removed)
        for failure in failures:
            print(f"BUDGET FAIL: {failure}", file=sys.stderr)
        if failures:
            return 1
        print("BUDGET OK: every measured value is within its baseline ceiling.")
        return 0

    # record
    measured = _load(args.measured)
    pathlib.Path(args.baseline).write_text(json.dumps(measured, indent=2, sort_keys=True) + "\n")
    print(f"Recorded {len(measured)} value(s) into {args.baseline}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
