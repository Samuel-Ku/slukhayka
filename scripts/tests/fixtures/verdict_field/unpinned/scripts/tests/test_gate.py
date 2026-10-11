"""Свідок фікстури: стан JVM-половини до #1257.

Підставний `gradlew` умів писати `errors`, але жоден тест не проводив через
нього артефакт із `errors>0`: `MUTATE_TEST_ERRORS` був завжди 0. Тут так само —
свідок бачить `killed` через `failures` і `survived` на зеленому прогоні, а
поле `errors` у нього завжди нуль. Саме тому мутант, який його обнулює, тут
виживає, і механізм мусить це назвати.

Жоден тест цього модуля не називає `errors` навмисно: у справжній історії його
не називав жоден тест теж.
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


def artifact(tests: int, failures: int, skipped: int = 0) -> None:
    ARTIFACT.parent.mkdir(parents=True, exist_ok=True)
    ARTIFACT.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<testsuite name="gate" tests="%d" skipped="%d" failures="%d" errors="0"/>\n'
        % (tests, skipped, failures),
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
