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


def environment() -> dict:
    """Оточення інструмента — своє, а не успадковане.

    CI-раннер приносить у процес власні змінні, і серед них може бути будь-що:
    експортована функція (`BASH_FUNC_dirname%%`) затіняє команду, `repo_root`
    підставного інструмента стає `/`, `python3` не знаходить `mutation_trace.py`
    і виходить із кодом 2 — при незмінному файлі. Саме так ця фікстура й
    почервоніла в CI, хоч локально була зелена.

    `scripts/tests/test_mutate.py` задає оточення так само — свіжим словником, а
    не `os.environ`, — і саме тому його фікстура від раннера не залежить.
    """
    return {
        "PATH": "/usr/bin:/bin",
        "HOME": str(ROOT),
        "LC_ALL": "C",
    }


def run_tool() -> dict:
    RECORD.unlink(missing_ok=True)
    result = subprocess.run(["bash", str(TOOL)], capture_output=True, text=True,
                            env=environment())
    if result.returncode != 0:
        # `check=True` ховає stderr інструмента за CalledProcessError: у CI саме
        # через це падіння не мало причини в логу. Причина мусить бути в тексті.
        raise AssertionError(
            "підставний інструмент вийшов із кодом %d\n--- stdout ---\n%s\n--- stderr ---\n%s"
            % (result.returncode, result.stdout[-500:], result.stderr[-500:])
        )
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
