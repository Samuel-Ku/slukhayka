"""Число в доку мусить мати команду, яка його відтворює — і мусить падати (#1277).

`scripts/doc_numbers.py` виводить число з запису в дереві, а не читає його з
прози. Тут — його власні перевірки, і жодна з них не «сьома перевірка» самого
себе:

  * `RepositoryTest` — на справжньому дереві: жодного числа, яке не
    виводиться, і жодної невідомості; серія, реєстр і перелік сканованих файлів
    — рівно ті, що названі (друге джерело правди: звузити їх мовчки не можна,
    це видно як правку тесту);
  * `FindingTest` — підставлене дерево: число, яке розходиться з записом,
    незараховане число в сканованому файлі й число в доку, якого ніхто не
    виводить, мусять бути знахідками з іменами — а не тихою згодою;
  * `RefusalTest` — невідомість коштує відмову (код 2): зник якір, якір не
    один, рядок більше не несе числа, ключа немає в серії, файлу немає;
  * `BoundaryTest` — межа названа МАШИННО: число у формі, якої немає в
    `NOUNS` (одиниці, секунди), число у файлі, якого немає в `SCANNED`, число в
    код-спані (цитата, не твердження) і кількість в однині («один запис» —
    вказівний зворот, не число) механізм НЕ ловить, і тест це стверджує. Це не
    діра, яку тут замовчують, а названа межа — «число, яке щось стверджує про
    дерево» відрізняє оглядач, не regex.

Зарахований у `scripts/guard_witnesses.py` — ОДИН сторож цього файлу, пін на
справжньому дереві (`test_every_number_derives_on_the_real_tree`); решта
перевіряють сам механізм і в реєстрі не стоять. Реєстр покриває НАЗВАНИХ, а не
всіх, і не вдає іншого — питати «де запис?» треба про кожен сторож окремо.
"""

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "doc_numbers.py"
sys.path.insert(0, str(MECHANISM.parent))

import doc_numbers as d  # noqa: E402

# Друге джерело правди: серія, записана тут буквально. Вісім місць — це перелік
# із тіла #1270 (`#1233`, `#1231`, `#1239`, `#1246`, `#1254`, `#1257`, `#1265`)
# плюс сам сторож; `empty_shape_mutants.py` називає випадок `#1254` п'ятим, і це
# те саме місце. Зникнення місця з `CASES` — не тиха згода, а правка цього
# рядка.
EXPECTED_CASES = (
    "#1233", "#1231", "#1239", "#1246", "#1254", "#1257", "#1265", "#1270",
)

EXPECTED_FIGURES = (
    ("docs/agents/local-environment.md", "gate is about the guards themselves"),
    ("scripts/guard_witnesses.py", "випадок класу"),
    ("scripts/empty_shape_mutants.py", "випадок за дві цілі"),
    ("scripts/guard_witnesses.py", "не стає галасливим"),
)

EXPECTED_SCANNED = (
    "docs/agents/local-environment.md",
    "scripts/guard_witnesses.py",
    "scripts/empty_shape_mutants.py",
    "scripts/verdict_field_mutants.py",
    "scripts/declared_fields.py",
)

# Файли, з яких складається підставлене дерево: механізм читає їх як текст, і
# жоден не імпортується (крім `guard_witnesses.py`, який дає `GUARDS`).
FIXTURE_FILES = tuple(sorted(set(EXPECTED_SCANNED)))


def run(argv):
    """`main` із перехопленим виводом: (код, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = d.main(argv)
    return code, out.getvalue(), err.getvalue()


class FixtureMixin(unittest.TestCase):
    """Копія справжніх файлів, яку тест править по одному місцю."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="doc-numbers-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for relative in FIXTURE_FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO_ROOT / relative, target)

    def edit(self, relative, find, replace, count=1):
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertEqual(count, text.count(find),
                         "правка мусить мати рівно одне місце: %r" % find)
        path.write_text(text.replace(find, replace), encoding="utf-8")

    def check(self):
        return run(["--root", str(self.root)])


class RepositoryTest(unittest.TestCase):
    """Справжнє дерево: кожне зараховане число виводиться, і немає чужих."""

    def test_every_number_derives_on_the_real_tree(self):
        """Сторож: пораховане в доку мусить збігатися з тим, що дає дерево."""
        findings, unknowns = d.check()
        self.assertEqual([], unknowns, "невідомість — не «число відтворено»")
        self.assertEqual([], findings, "кожне число в доку мусить виводитися")

    def test_the_cli_is_green_on_the_real_tree(self):
        code, out, err = run([])
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_the_series_is_the_named_set(self):
        self.assertEqual(list(EXPECTED_CASES), list(d.keys("cases")),
                         "серія — це дані, і звузити її мовчки не можна")
        self.assertEqual(len(EXPECTED_CASES), len(d.CASES))

    def test_the_registry_is_the_named_set(self):
        self.assertEqual(
            list(EXPECTED_FIGURES),
            [(relative, anchor) for relative, anchor, _ in d.FIGURES],
        )

    def test_the_scanned_files_are_the_named_set(self):
        self.assertEqual(list(EXPECTED_SCANNED), list(d.SCANNED))

    def test_every_enrolled_line_carries_exactly_one_number(self):
        for relative, anchor, source in d.FIGURES:
            with self.subTest(anchor=anchor):
                text = (REPO_ROOT / relative).read_text(encoding="utf-8")
                number, _ = d.line_with_anchor(text, anchor, relative)
                on_line = [
                    one for one in d.line_hits(REPO_ROOT, relative, number)
                    if d.key_of(one[3]) == d.key_of(source)
                ]
                self.assertEqual(1, len(on_line),
                                 "рядок мусить нести рівно одне число джерела")

    def test_the_listing_names_every_enrolled_number(self):
        code, out, err = run(["--list"])
        self.assertEqual(0, code, err)
        for relative, anchor, source in d.FIGURES:
            with self.subTest(anchor=anchor):
                self.assertIn(anchor, out)
                self.assertIn(d.spelled(d.derive(source), source[0]), out)


class FindingTest(FixtureMixin):
    """Число, яке не виводиться, — знахідка з іменами, а не тиха згода."""

    def test_a_position_that_disagrees_with_the_series_is_a_finding(self):
        """Живий приклад тікета: «seventh» там, де серія дає восьме місце."""
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of", "The seventh case of")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «seventh»", out)
        self.assertIn("«eighth»", out)
        self.assertIn("docs/agents/local-environment.md:337", out)

    def test_a_count_that_disagrees_with_the_registry_is_a_finding(self):
        self.edit("scripts/guard_witnesses.py",
                  "разом вісім записів", "разом шість записів")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «шість»", out)
        self.assertIn("«eight»", out)

    def test_a_number_nobody_derives_is_a_finding(self):
        """Число на рядку, якого немає в реєстрі, проходить повз нього?"""
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of",
                  "The eighth case of\nThe twelfth case of \"a check that does "
                  "not check\" was invented.")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("«twelfth case»", out)
        self.assertIn("числа немає в жодному записі дерева", out)

    def test_a_number_in_a_scanned_file_without_an_entry_is_a_finding(self):
        self.edit("scripts/declared_fields.py",
                  "Кожне оголошене поле мусить бути",
                  "Сьомий випадок класу. Кожне оголошене поле мусить бути")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("scripts/declared_fields.py:2", out)
        self.assertIn("«Сьомий випадок»", out)


class RefusalTest(FixtureMixin):
    """Невідомість коштує відмову (код 2), а не тихий дозвіл."""

    def test_a_missing_anchor_is_an_unknown(self):
        self.edit("docs/agents/local-environment.md",
                  "gate is about the guards themselves", "gate is about something else")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("якір мусить траплятися рівно раз", err)

    def test_an_anchor_that_is_not_alone_is_an_unknown(self):
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of",
                  "The eighth case of ... and the gate is about the guards "
                  "themselves, and the eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("якір мусить траплятися рівно раз", err)

    def test_a_line_that_no_longer_carries_the_number_is_an_unknown(self):
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of", "The case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("рядок більше не несе числа", err)

    def test_a_key_missing_from_the_series_is_an_unknown(self):
        """Мутація, якою доведено сторожа: місце зникає з серії."""
        without_guard = tuple(case for case in d.CASES if case[0] != "#1270")
        with unittest.mock.patch.object(d, "CASES", without_guard):
            findings, unknowns = d.check()
        self.assertTrue(unknowns, "зникле місце — невідомість, а не згода")
        self.assertIn("ключа #1270 немає в серії", unknowns[0])
        self.assertTrue(
            any("числа немає в жодному записі дерева" in one for one in findings),
            "рядок, чиє місце зникло, лишається незарахованим — і це видно",
        )

    def test_a_missing_scanned_file_is_an_unknown(self):
        (self.root / "scripts/declared_fields.py").unlink()
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("немає файлу: scripts/declared_fields.py", err)


class BoundaryTest(FixtureMixin):
    """Межа названа машинно: чого механізм НЕ бачить, тест це стверджує."""

    def test_a_figure_of_an_unlisted_shape_passes_silently(self):
        """Одиниці й секунди поза формами: 999 MiB нічого не червонить."""
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of",
                  "The eighth case of (naive 999 MiB, 42 s) ")
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_number_in_an_unscanned_file_passes_silently(self):
        """Кого немає в `SCANNED`, того механізм не бачить — і не вдає."""
        (self.root / "scripts" / "not-scanned-example.py").write_text(
            "# шість записів і сьомий випадок\n", encoding="utf-8")
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_number_in_a_code_span_is_a_citation_not_a_claim(self):
        """Число в бектиках — те, що документ НАВОДИТЬ; механізм читає прозу."""
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of",
                  "The eighth case of\nThe text used to read `the seventh case of "
                  "\"a check that does not check\"`.")
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_count_in_the_singular_is_not_a_count(self):
        """«один запис» — вказівний зворот; форма кількості його не ловить."""
        self.edit("scripts/guard_witnesses.py",
                  "разом вісім записів", "разом вісім записів, і один запис окремо")
        code, _, err = self.check()
        self.assertEqual(0, code, err)


if __name__ == "__main__":
    unittest.main()
