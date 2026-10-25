#!/usr/bin/env python3
"""Print a per-test summary of AGP's connected-test JUnit XML.

Gradle prints nothing per test for connectedAndroidTest: a failure only says
"There were failing tests" and points at an HTML report that lives in an
artifact. This script runs after the Gradle step (pass or fail) and writes
counts, every failure with its message, and the slowest tests to the job log
and to the GitHub step summary.

Usage: android-test-summary.py <results-dir> [<gradle-outcome>]

Exits non-zero when no result files exist although Gradle reported success:
a run that executed nothing must not look green.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    results_dir = sys.argv[1]
    gradle_outcome = sys.argv[2] if len(sys.argv) > 2 else "unknown"
    files = sorted(glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True))

    lines = []
    if not files:
        lines.append(f"No instrumented test results found under {results_dir}.")
        emit(lines)
        if gradle_outcome == "success":
            print("::error::Gradle reported success but no tests ran.")
            return 1
        return 0

    total = failed = errored = skipped = 0
    failures = []
    per_class = {}
    durations = []
    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as e:
            lines.append(f"Could not parse {path}: {e}")
            continue
        for case in suite.iter("testcase"):
            cls = case.get("classname", "?").rsplit(".", 1)[-1]
            name = case.get("name", "?")
            duration = float(case.get("time", "0") or 0)
            durations.append((duration, f"{cls}.{name}"))
            counts = per_class.setdefault(cls, [0, 0])
            counts[0] += 1
            total += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
                if problem is not None:
                    errored += 1
            else:
                failed += 1
            if problem is not None:
                counts[1] += 1
                message = (problem.get("message") or problem.text or "").strip().splitlines()
                failures.append((cls, name, message[0] if message else "(no message)"))
            elif case.find("skipped") is not None:
                skipped += 1

    lines.append(
        f"Instrumented tests: {total} run, {failed} failed, {errored} errored, {skipped} skipped."
    )
    lines.append("")
    for cls in sorted(per_class):
        run, bad = per_class[cls]
        mark = "FAIL" if bad else "ok  "
        lines.append(f"{mark} {cls}: {run} tests, {bad} failed")
    if failures:
        lines.append("")
        lines.append("Failures:")
        for cls, name, message in failures:
            lines.append(f"- {cls}.{name}: {message}")
    slowest = sorted(durations, reverse=True)[:5]
    if slowest:
        lines.append("")
        lines.append("Slowest tests:")
        for duration, name in slowest:
            lines.append(f"- {name}: {duration:.1f}s")
    emit(lines)
    for cls, name, message in failures:
        print(f"::error title={cls}.{name}::{message}")
    return 0


def emit(lines: list[str]) -> None:
    text = "\n".join(lines)
    print(text)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write("## Instrumented tests\n\n```\n" + text + "\n```\n")


if __name__ == "__main__":
    sys.exit(main())
