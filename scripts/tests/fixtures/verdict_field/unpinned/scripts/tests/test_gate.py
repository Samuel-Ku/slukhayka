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
