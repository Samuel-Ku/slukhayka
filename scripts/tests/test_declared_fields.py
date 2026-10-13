"""Набір оголошених полів — під піном, і кожна розбіжність названа (#1276).

`scripts/declared_fields.py` пінить набір полів кожного запису-оголошення в
машинерії перевірок. Тут — його власні перевірки, і жодна з них не «сьома
перевірка» запису: механізм не питає, що поле означає, він питає, чи воно
оголошене — і вимагає, щоб автор назвав це сам.

  * `RepositoryTest` — на справжньому дереві: набір полів той самий, і перелік
    записів — рівно той, що оголошено (друге джерело правди, як `GUARD_TESTS`:
    звузити перелік мовчки не можна, це видно як правку тесту);
  * `FindingTest` — підставлене дерево: нове поле, зникле поле й переставлені
    поля мусять бути знахідками з іменами, а не тихою згодою;
  * `RefusalTest` — невідомість коштує відмову (код 2): файлу немає, клас уже
    не датаклас;
  * `BoundaryTest` — межа названа МАШИННО: новий запис-оголошення (нова
    датаклас-форма), якого немає в переліку, механізм НЕ ловить, і тест це
    стверджує. Це не діра, яку тут замовчують, а названа межа — «схоже на
    запис» і «запис» розрізняє оглядач, не AST. Якщо колись з'явиться
    твердження про повне покриття, цей тест почервоніє.

Зарахований у `scripts/guard_witnesses.py` — ОДИН сторож цього файлу, пін на
набір полів (`test_every_record_declares_exactly_the_listed_fields`); решта
вісім перевіряють сам механізм і в реєстрі не стоять. Реєстр покриває НАЗВАНИХ,
а не всіх, і не вдає іншого — питати «де запис?» треба про кожен сторож окремо.
"""

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "declared_fields.py"
sys.path.insert(0, str(MECHANISM.parent))

import declared_fields as d  # noqa: E402

# Друге джерело правди: перелік записів і полів, записаний тут буквально.
# Зникнення запису з `RECORDS` — не тиха згода, а правка цього рядка.
EXPECTED_RECORDS = (
    ("scripts/guard_witnesses.py", "Guard", ("test", "protects", "edits")),
    ("scripts/guard_witnesses.py", "Edit", ("file", "find", "replace")),
    ("scripts/verdict_field_mutants.py", "Field", ("name", "python_zero", "shell_zero")),
    ("scripts/tests/test_fixture_environments.py", "StandIn",
     ("name", "where", "prefix", "reads", "programs")),
    ("scripts/tests/test_fixture_environments.py", "Program", ("at", "marker")),
)

# Файли, з яких складається підставлене дерево: механізм читає джерела AST,
# тож копії досить — жоден із них не імпортується.
RECORD_FILES = (
    "scripts/guard_witnesses.py",
    "scripts/verdict_field_mutants.py",
    "scripts/tests/test_fixture_environments.py",
)


class TreeTest(unittest.TestCase):
    """Підставлене дерево: копії записів і правки, які мусять бути видно."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="declared-fields-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for relative in RECORD_FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO_ROOT / relative, target)

    def edit(self, relative: str, find: str, replace: str) -> None:
        """Одна правка — рівно раз: інакше підставлене дерево не те, що треба."""
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertEqual(1, text.count(find), "правка мусить застосуватись рівно раз")
        path.write_text(text.replace(find, replace, 1), encoding="utf-8")

    def run_cli(self) -> tuple[int, str]:
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = d.main(["--root", str(self.root)])
        return status, errors.getvalue()


class RepositoryTest(unittest.TestCase):
    """Типовий виклик: справжнє дерево, той самий механізм, що в CI."""

    def test_every_record_declares_exactly_the_listed_fields(self):
        report = d.check(REPO_ROOT)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings])
        self.assertEqual(len(EXPECTED_RECORDS), report.checked)

    def test_the_inventory_is_the_named_set(self):
        """Перелік записів — рівно названий: звузити його мовчки не можна."""
        self.assertEqual(EXPECTED_RECORDS, d.RECORDS)


class FindingTest(TreeTest):
    """Нове поле, зникле поле й переставлені поля — знахідки, обидві з іменами."""

    def test_a_new_declared_field_is_a_finding_that_names_it(self):
        self.edit("scripts/guard_witnesses.py",
                  "    test: str\n    protects: str\n    edits: tuple[Edit, ...]\n",
                  "    test: str\n    protects: str\n    edits: tuple[Edit, ...]\n"
                  '    owner: str = ""\n')

        status, errors = self.run_cli()

        self.assertEqual(1, status, errors)
        self.assertIn("НОВЕ оголошене поле: owner", errors)

    def test_a_field_that_disappeared_is_a_stale_entry(self):
        self.edit("scripts/guard_witnesses.py",
                  "    test: str\n    protects: str\n", "    test: str\n")

        status, errors = self.run_cli()

        self.assertEqual(1, status, errors)
        self.assertIn("застарілий запис: protects", errors)

    def test_a_reordered_field_set_is_a_finding_not_a_pass(self):
        """Порядок — частина оголошення: `Program` у `CASES` будують позиційно."""
        self.edit("scripts/guard_witnesses.py",
                  "    file: str\n    find: str\n    replace: str\n",
                  "    find: str\n    file: str\n    replace: str\n")

        status, errors = self.run_cli()

        self.assertEqual(1, status, errors)
        self.assertIn("порядок полів інший", errors)


class RefusalTest(TreeTest):
    """Невідомість — код 2, а не згода: «полів немає» і «не прочитати» різні."""

    def test_a_missing_file_is_an_unknown(self):
        (self.root / "scripts" / "guard_witnesses.py").unlink()

        status, errors = self.run_cli()

        self.assertEqual(2, status, errors)
        self.assertIn("немає scripts/guard_witnesses.py", errors)

    def test_a_record_that_is_no_longer_a_dataclass_is_an_unknown(self):
        self.edit("scripts/guard_witnesses.py",
                  "@dataclasses.dataclass(frozen=True)\nclass Guard:",
                  "class Guard:")

        status, errors = self.run_cli()

        self.assertEqual(2, status, errors)
        self.assertIn("уже не датаклас", errors)

    def test_a_malformed_inventory_entry_is_an_unknown(self):
        """Форма переліку теж невідома, якщо не та: «поля»-рядок дав би літери."""
        report = d.check(self.root,
                         records=(("scripts/guard_witnesses.py", "Guard"),))

        self.assertEqual([], [finding.detail for finding in report.findings])
        self.assertEqual(1, len(report.blocked))
        self.assertIn("(файл, клас, поля)", report.blocked[0])


class BoundaryTest(TreeTest):
    """Межа названа МАШИННО: новий запис у перелік сам не потрапляє."""

    def test_a_new_record_is_not_caught_and_the_limit_is_named(self):
        """Нова датаклас-форма без запису в `RECORDS` — зелено, і це межа.

        Не «ну, пощастило»: список записів розширюють руками, бо «схоже на
        запис» і «запис» розрізняє оглядач, а не AST (ціну такої спроби вже
        сплачено в `test_fixture_environments`). Тест стверджує саме це — і
        почервоніє, якщо механізм колись почне вдавати повне покриття.
        """
        (self.root / "scripts" / "new_gate.py").write_text(
            "import dataclasses\n\n\n"
            "@dataclasses.dataclass(frozen=True)\n"
            "class NewRecord:\n"
            "    claim: str\n"
            "    switch: bool = False\n",
            encoding="utf-8",
        )

        report = d.check(self.root)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings])


if __name__ == "__main__":
    unittest.main()
