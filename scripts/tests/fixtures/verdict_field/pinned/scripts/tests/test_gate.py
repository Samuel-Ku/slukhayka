"""Свідок фікстури: той самий інструмент, але `errors` тепер піниться.

Різниця з `unpinned` — тільки цей файл. Один тест проводить через ланку
артефакт із `errors>0` і вимагає, щоб вердикт це побачив: саме цього тесту не
було до #1257, і саме тому мутант `"errors": "0"` тоді виживав.
"""

import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts" / "mutate.sh"
TRACE = ROOT / "scripts" / "mutation_trace.py"
ARTIFACT = ROOT / ".artifacts" / "TEST-gate.xml"
RECORD = ROOT / ".artifacts" / "runs.jsonl"


def artifact(tests: int, failures: int, skipped: int = 0, errors: int = 0) -> None:
    ARTIFACT.parent.mkdir(parents=True, exist_ok=True)
    ARTIFACT.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<testsuite name="gate" tests="%d" skipped="%d" failures="%d" errors="%d"/>\n'
        % (tests, skipped, failures, errors),
        encoding="utf-8",
    )


def run_tool() -> dict:
    RECORD.unlink(missing_ok=True)
    subprocess.run(["bash", str(TOOL)], check=True, capture_output=True, text=True)
    return json.loads(RECORD.read_text(encoding="utf-8").splitlines()[-1])


class GateTest(unittest.TestCase):
    def test_a_run_with_failures_is_killed(self):
        artifact(tests=2, failures=1)

        self.assertEqual("killed", run_tool()["outcome"])

    def test_a_green_run_survives(self):
        artifact(tests=2, failures=0)

        self.assertEqual("survived", run_tool()["outcome"])

    def test_every_test_skipped_has_no_verdict(self):
        artifact(tests=2, failures=0, skipped=2)
        record = run_tool()

        self.assertEqual("no-verdict", record["outcome"])
        self.assertEqual(2, record["tests"])

    def test_the_counts_travel_into_the_record(self):
        artifact(tests=3, failures=2)
        record = run_tool()

        self.assertEqual(3, record["tests"])
        self.assertEqual(2, record["failures"])

    def test_a_witness_that_raises_is_killed_and_counted(self):
        """Свідок падає error'ом: killed робить `errors`, а не `failures`."""
        artifact(tests=1, failures=0, errors=1)
        record = run_tool()

        self.assertEqual("killed", record["outcome"])
        self.assertEqual(1, record["errors"])
        self.assertEqual(0, record["failures"])
