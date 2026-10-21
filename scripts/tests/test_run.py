"""Тести scripts/run.sh: код виходу, який не бреше (#1370).

Ціна, виміряна сесією: `cmd | tail` віддає код `tail`, не команди. Чотири рази
було прочитано `exit 0`, коли в лозі стояло `BUILD FAILED`. Рецепт
`(cmd > log 2>&1; echo $? > exit)` записаний у docs/agents/local-environment.md,
але це cure, не захист — знати треба заздалегідь.

Тести нижче ЧЕРВОНІ на старій поведінці («пропустити через конвеєр»), бо саме
конвеєр і був помилкою.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "run.sh"


class RunShTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="runsh-"))
        self.env = dict(os.environ)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_sh(self, *args):
        """Викликає скрипт і ПОВЕРТАЄ завершений процес — без конвеєра.

        Тут навмисно немає `| tail`: саме конвеєр і з'їдає код, який тестується.
        """
        return subprocess.run(
            ["bash", str(SCRIPT), "--log", str(self.tmp / "out.log"), "--", *args],
            capture_output=True, text=True, cwd=str(self.tmp), env=self.env,
            check=False,
        )

    def test_a_failing_command_reports_its_own_exit_code(self):
        """Головний випадок: `false` мусить дати 1, а не 0 від чужого процесу."""
        result = self.run_sh("false")
        self.assertEqual(1, result.returncode)

    def test_a_succeeding_command_reports_zero(self):
        result = self.run_sh("true")
        self.assertEqual(0, result.returncode)

    def test_the_command_output_reaches_the_log(self):
        """Лог мусить містити ВИВІД команди, а не порожнечу.

        `> >(tee …)` робив саме порожній лог: підстановка процесу асинхронна і
        скрипт виходив раніше, ніж tee дописував. Тест пінить синхронність.
        """
        self.run_sh("echo", "привіт з команди")
        log = (self.tmp / "out.log").read_text(encoding="utf-8")
        self.assertIn("привіт з команди", log)

    def test_the_exit_code_is_written_to_a_file(self):
        """Код мусить лежати у файлі — щоб його можна було прочитати після."""
        self.run_sh("false")
        self.assertEqual("1", (self.tmp / "out.exit").read_text(encoding="utf-8").strip())

    def test_a_nonzero_code_survives_a_large_output(self):
        """Код не мусить залежати від обсягу виводу (конвеєр саме так і ламався)."""
        result = self.run_sh("bash", "-c", "seq 1 500; exit 7")
        self.assertEqual(7, result.returncode)

    def test_a_missing_double_dash_refuses(self):
        """Без `--` скрипт відмовляє кодом 2, а не виконує щось навмання."""
        result = subprocess.run(
            ["bash", str(SCRIPT), "echo", "hi"],
            capture_output=True, text=True, cwd=str(self.tmp), check=False,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("--", result.stderr)

    def test_no_command_refuses(self):
        result = subprocess.run(
            ["bash", str(SCRIPT), "--"],
            capture_output=True, text=True, cwd=str(self.tmp), check=False,
        )
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
