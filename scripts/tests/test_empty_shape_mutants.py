"""Механізм #1256: порожня форма рішення `case` мусить мати мутацію, яка її знімає.

Головний доказ лежить у фікстурах. `fixtures/empty_shape/unpinned` повторює
стан PR #1254 — тест пінить літерал `"detached HEAD"`, а порожньої форми (її дає
зупинений `git am`, який не лишає `head-name`) не виробляє ніхто, — і механізм
мусить бути на ній ЧЕРВОНИМ. `fixtures/empty_shape/pinned` відрізняється лише
набором-свідком, і механізм на ньому зелений. Обидва корені мають байт-у-байт
той самий `gate.sh`: різниця тільки в тестах, і це піниться окремим тестом.

Механізм по всьому репозиторію жене окремий тест — він додається разом із
чищенням того, що механізм знайшов на першому прогоні.
"""

import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts"))

import empty_shape_mutants as esm  # noqa: E402

FIXTURES = REPO_ROOT / "scripts" / "tests" / "fixtures" / "empty_shape"
TESTS_DIR = Path("scripts/tests")

SAMPLE = """#!/usr/bin/env bash
case "$branch" in
  "" | "detached HEAD") refuse ;;
  "$HEAD_REF") refuse ;;
  *) warn ;;
esac
"""


class ScannerTest(unittest.TestCase):
    def test_reads_arms_with_offsets_and_keeps_the_source_intact(self):
        arms = esm.scan(SAMPLE)

        self.assertEqual(3, len(arms))
        self.assertEqual(["", "detached HEAD"], [alt.value for alt in arms[0].alternatives])
        self.assertEqual(3, arms[0].arm_line)
        # Зсуви справжні: вирізаний шматок — це рівно список шаблонів arms.
        first, last = arms[0].alternatives[0], arms[0].alternatives[-1]
        self.assertEqual('"" | "detached HEAD"', SAMPLE[first.start:last.end])

    def test_a_quoted_pipe_is_not_a_separator(self):
        arms = esm.scan('case "$probe" in\n  *"HTTP 404"* | *"Not Found"*) die ;;\nesac\n')

        self.assertEqual(
            ['*"HTTP 404"*', '*"Not Found"*'],
            [alt.text for alt in arms[0].alternatives],
        )

    def test_only_an_empty_pattern_beside_a_named_one_is_a_shape(self):
        shapes = esm.shapes(SAMPLE)

        self.assertEqual(1, len(shapes))
        self.assertEqual(3, shapes[0].line)
        self.assertEqual(('"detached HEAD"',), shapes[0].named)

    def test_named_alternatives_and_single_arms_are_not_shapes(self):
        source = (
            'case "$1" in\n  -h | --help) usage ;;\n  --dry-run) dry=1 ;;\nesac\n'
            'case "$x" in\n  "") refuse ;;\nesac\n'
        )

        self.assertEqual([], esm.shapes(source))

    def test_mutation_removes_every_empty_pattern_of_the_arm(self):
        source = 'case "$branch" in\n  "" | \'\' | "detached HEAD") refuse ;;\nesac\n'

        mutant = esm.mutate(source, esm.shapes(source)[0])

        self.assertIn('"detached HEAD") refuse', mutant)
        self.assertNotIn('""', mutant)
        self.assertNotIn("''", mutant)

    def test_an_unparsable_case_is_an_error_not_an_empty_repository(self):
        """`case`, який парсер не довів до arms: невідомість, а не «форм немає»."""
        with self.assertRaises(esm.ShapeError):
            esm.scan('case "$x" in\n  arm_without_parenthesis\n')

    def test_the_repository_scripts_all_parse(self):
        """Парсер не відстає від мови скриптів репозиторію — інакше він би мовчав."""
        for script in esm.shell_scripts(REPO_ROOT):
            with self.subTest(script=script.name):
                esm.scan(script.read_text(encoding="utf-8"))


class HistoricalStateTest(unittest.TestCase):
    """Фікстура #1254: літерал піниться, порожня форма — ні."""

    def check(self, name):
        root = FIXTURES / name
        return esm.check(root / "scripts" / "gate.sh", TESTS_DIR, root)

    def test_the_two_fixtures_differ_only_in_the_witness(self):
        unpinned = (FIXTURES / "unpinned" / "scripts" / "gate.sh").read_bytes()
        pinned = (FIXTURES / "pinned" / "scripts" / "gate.sh").read_bytes()

        self.assertEqual(unpinned, pinned)

    def test_the_historical_state_is_red(self):
        report = self.check("unpinned")

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.killed)
        self.assertEqual(1, len(report.findings))
        self.assertIn("порожня форма", report.findings[0])
        self.assertIn("не пінить жоден тест", report.findings[0])

    def test_the_pinned_state_is_green(self):
        report = self.check("pinned")

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.findings)
        self.assertEqual(["test_gate.py"], report.killed[0].killed_by)


class FailClosedTest(unittest.TestCase):
    def fixture(self, witness: str) -> Path:
        """Корінь-фікстура з тим самим gate.sh і заданим набором-свідком."""
        root = Path(self._tmp.name)
        (root / "scripts" / "tests").mkdir(parents=True, exist_ok=True)
        shutil.copy(FIXTURES / "pinned" / "scripts" / "gate.sh", root / "scripts" / "gate.sh")
        (root / "scripts" / "tests" / "test_gate.py").write_text(witness, encoding="utf-8")
        return root

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)

    def test_a_script_without_a_witness_is_refused_not_ignored(self):
        root = self.fixture("import unittest\n")
        (root / "scripts" / "tests" / "test_gate.py").unlink()

        report = esm.check(root / "scripts" / "gate.sh", TESTS_DIR, root)

        self.assertEqual([], report.verdicts)
        self.assertEqual(1, len(report.blocked))
        self.assertIn("нічим не піниться", report.blocked[0])

    def test_a_red_witness_suite_is_not_read_as_a_killed_mutant(self):
        """Червоний набір БЕЗ мутації — невідомість: зеленого тут не відрізнити."""
        root = self.fixture(
            'GATE = "scripts/gate.sh"  # свідок мусить називати скрипт\n\n'
            "import unittest\n\n\nclass FailingTest(unittest.TestCase):\n"
            "    def test_gate_is_always_red(self):\n        self.fail('зламаний набір')\n"
        )

        report = esm.check(root / "scripts" / "gate.sh", TESTS_DIR, root)

        self.assertEqual([], report.verdicts)
        self.assertEqual(1, len(report.blocked))
        self.assertIn("червоний БЕЗ мутації", report.blocked[0])

    def test_the_mechanism_refuses_to_run_inside_itself(self):
        """Маркер глибини може лише відмовити: прогін у себе не доводить нічого."""
        root = self.fixture("import unittest\n")

        with mock.patch.dict(os.environ, {esm.RECURSION_MARKER: "1"}):
            report = esm.check(root / "scripts" / "gate.sh", TESTS_DIR, root)

        self.assertEqual([], report.verdicts)
        self.assertEqual(1, len(report.blocked))
        self.assertIn("усередині механізму", report.blocked[0])

    def test_the_mechanism_has_no_switch_to_turn_it_off(self):
        """Вимикача немає навмисно: гейт, який можна вимкнути, — не гейт.

        Дві спроби вимкнути: невідомий прапорець і змінні, якими зазвичай
        глушать гейти. Перша — помилка аргументів, друга не змінює нічого:
        на навмисно непіненій фікстурі механізм усе одно відмовляє.
        """
        tool = REPO_ROOT / "scripts" / "empty_shape_mutants.py"
        unpinned = FIXTURES / "unpinned"
        hostile = {
            **os.environ,
            "SKIP_EMPTY_SHAPE_MUTANTS": "1",
            "EMPTY_SHAPE_MUTANTS": "off",
            "CI": "true",
        }

        skipped = subprocess.run(
            [sys.executable, str(tool), "--root", str(unpinned), "--skip"],
            check=False, capture_output=True, text=True, env=hostile,
        )
        refused = subprocess.run(
            [sys.executable, str(tool), "--root", str(unpinned)],
            check=False, capture_output=True, text=True, env=hostile,
        )

        self.assertEqual(2, skipped.returncode, skipped.stderr)
        self.assertEqual(1, refused.returncode, refused.stdout)
        self.assertIn("не пінить жоден тест", refused.stderr)


if __name__ == "__main__":
    unittest.main()
