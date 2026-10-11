#!/usr/bin/env python3
"""Run one unittest target and write the JUnit XML a mutation record points at.

#1255: the Python half of `scripts/mutate.sh`. A JVM mutation run leaves Gradle's
`app/build/test-results/testDebugUnitTest/TEST-<class>.xml`, and `mutate.sh` reads
that one file twice — its sha256 goes into the record as the artifact the run
produced, and its tests/failures/errors attributes decide killed, survived or no
verdict. `unittest` writes no such file, so a `scripts/**` mutation had nowhere to
put that evidence: the nine mutations of PR #1254 lived only in commit bodies.

This module runs the same target `python3 -m unittest <target>` runs — the same
loader, the same suite — and writes a JUnit XML of the same shape, so both halves
feed one record schema, one verdict and one `status` vocabulary. `mutate.sh`
owns when a run is recorded; this file owns nothing but the run.

Exit codes: 0 the target ran green · 1 the target ran and failed · 2 unusable
arguments · 3 the target could not be loaded, or declared no tests, so no
artifact was written and the run has no verdict — the Python spelling of the JVM
half's compile error.
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
import traceback
import unittest
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


# No artifact, no verdict. Distinct from 1 so mutate.sh can tell "the target
# failed" from "the target never ran".
LOAD_FAILURE = 3


def flatten(suite: unittest.TestSuite):
    """Every test in a suite of suites, in run order."""
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from flatten(item)
        else:
            yield item


def message_of(trace: str) -> str:
    return trace.strip().splitlines()[-1] if trace.strip() else ""


class TimedResult(unittest.TextTestResult):
    """A TextTestResult that also remembers how long each test took."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.durations: dict[str, float] = {}

    def startTest(self, test):
        self._started = time.monotonic()
        super().startTest(test)

    def stopTest(self, test):
        super().stopTest(test)
        started = getattr(self, "_started", None)
        if started is not None:
            self.durations[test.id()] = time.monotonic() - started


def load(tests_dir: Path, target: str):
    """The suite `python3 -m unittest <target>` would build, or None.

    A target that cannot be loaded — a name that does not resolve, a module whose
    import raises (a syntax error in the mutated file is one of those) — is not a
    failed test: nothing ran, so there is no verdict. `unittest` reports a bad
    name as a suite holding a `_FailedTest` placeholder; a syntax error escapes
    the loader entirely. Both are "no verdict" here. The placeholder is matched by
    its type name rather than imported: it is private to `unittest`. Its public
    behaviour is that `python3 -m unittest <bad name>` prints the same traceback
    and exits 1, which this file must not mistake for a test noticing a mutation.
    """
    sys.path.insert(0, str(tests_dir))
    loader = unittest.TestLoader()
    try:
        suite = loader.loadTestsFromName(target)
    except Exception:
        traceback.print_exc()
        return None
    tests = list(flatten(suite))
    unloadable = [test for test in tests if type(test).__name__ == "_FailedTest"]
    if unloadable:
        for line in loader.errors:
            print(line, file=sys.stderr)
        print(
            f"mutation python runner: {target} could not be loaded; "
            "no test ran, so no artifact is written",
            file=sys.stderr,
        )
        return None
    if not tests:
        print(
            f"mutation python runner: {target} declares no tests; "
            "no artifact is written",
            file=sys.stderr,
        )
        return None
    return suite, tests


def outcomes_of(result: unittest.TextTestResult) -> dict[str, tuple[str, str, str]]:
    """test id → (element, message, body) for everything that did not just pass."""
    outcomes: dict[str, tuple[str, str, str]] = {}
    for test, trace in result.failures:
        outcomes[test.id()] = ("failure", message_of(trace), trace)
    for test, trace in result.errors:
        outcomes[test.id()] = ("error", message_of(trace), trace)
    for test in result.unexpectedSuccesses:
        outcomes[test.id()] = ("failure", "unexpected success", "")
    for test, reason in result.skipped:
        outcomes[test.id()] = ("skipped", reason, "")
    for test, _trace in result.expectedFailures:
        # unittest hands over the traceback here, not a reason: the element is
        # named by the outcome, not by that string.
        outcomes[test.id()] = ("skipped", "expected failure", "")
    return outcomes


def write_artifact(
    path: Path,
    target: str,
    tests: list,
    result: unittest.TextTestResult,
    seconds: float,
) -> None:
    """The JUnit XML `mutate.sh` hashes, reads counts from, and records.

    The attribute names and the shape are Gradle's, because that is what the JVM
    half of the tool parses; the counts are the ones `unittest` itself would print
    (`unexpectedSuccesses` counts as a failure there too — `wasSuccessful` is
    False and `python3 -m unittest` exits 1). `timestamp` is not decoration: a
    second run of the same target must not reproduce the first run's bytes, or
    `mutate.sh` would read the new run's XML as stale and report no verdict.
    """
    outcomes = outcomes_of(result)
    suite = ET.Element(
        "testsuite",
        {
            "name": target,
            "tests": str(result.testsRun),
            "skipped": str(len(result.skipped) + len(result.expectedFailures)),
            "failures": str(len(result.failures) + len(result.unexpectedSuccesses)),
            "errors": str(len(result.errors)),
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]
            + "Z",
            "hostname": socket.gethostname(),
            "time": f"{seconds:.3f}",
        },
    )
    for test in tests:
        kind = type(test)
        element = ET.SubElement(
            suite,
            "testcase",
            {
                "name": getattr(test, "_testMethodName", test.id()),
                "classname": f"{kind.__module__}.{kind.__qualname__}",
                "time": f"{result.durations.get(test.id(), 0.0):.3f}",
            },
        )
        outcome = outcomes.get(test.id())
        if outcome is None:
            continue
        name, message, body = outcome
        child = ET.SubElement(element, name, {"message": message})
        if body:
            child.text = body
    path.parent.mkdir(parents=True, exist_ok=True)
    tree = ET.ElementTree(suite)
    ET.indent(tree, space="  ")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tests-dir", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    args = parser.parse_args()

    if not args.tests_dir.is_dir():
        print(f"mutation python runner: no test directory {args.tests_dir}", file=sys.stderr)
        return 2

    loaded = load(args.tests_dir, args.target)
    if loaded is None:
        return LOAD_FAILURE
    suite, tests = loaded

    started = time.monotonic()
    runner = unittest.TextTestRunner(
        stream=sys.stderr, verbosity=2, resultclass=TimedResult
    )
    result = runner.run(suite)
    seconds = time.monotonic() - started

    write_artifact(args.artifact, args.target, tests, result, seconds)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
