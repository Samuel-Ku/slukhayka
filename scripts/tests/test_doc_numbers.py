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
    виводить, мусять бути знахідками з іменами — а не тихою згодою; окремо
    `CommandTest` — те саме про команди: вигадана опція і скрипт, якого немає,
    мусять червонити, а справжня команда з двома скриптами в рядку — ні;
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

# Друге джерело правди: серія, записана тут буквально. Два переліки в дереві —
# `CONTRIBUTING.md` (п'ять випадків, третій — знахідка огляду `#1218`) і тіло
# #1270 (сім issue, третій — `#1239`) — називають один випадок по-різному:
# `#1239` це щуп розкладки, і обидва переліки ставлять `#1254` п'ятим, що
# можливо лише тоді, коли третій член — той самий випадок. Тому вісім місць, і
# зникнення будь-якого з них — не тиха згода, а правка цього рядка.
EXPECTED_CASES = (
    "#1233", "#1231", "#1218", "#1246", "#1254", "#1257", "#1265", "#1270",
)

EXPECTED_FIGURES = (
    ("docs/agents/local-environment.md", "gate is about the guards themselves"),
    ("scripts/guard_witnesses.py", "випадок класу"),
    ("scripts/empty_shape_mutants.py", "випадок за дві цілі"),
    ("scripts/guard_witnesses.py", "не стає галасливим"),
    ("docs/agents/local-environment.md", "figures of"),
    ("docs/agents/local-environment.md", "sit on a line"),
)

EXPECTED_SCANNED = (
    "docs/agents/local-environment.md",
    "scripts/guard_witnesses.py",
    "scripts/empty_shape_mutants.py",
    "scripts/verdict_field_mutants.py",
    "scripts/declared_fields.py",
)

# Файли, з яких складається підставлене дерево. Механізм читає їх як текст і
# розв'язує ЗГАДАНІ в них команди, тож копіюється все `scripts/` — інакше кожна
# цитата читалася б як «команди немає в дереві» (це і є перша версія фікстури,
# яка на цьому й спіткнулася).
FIXTURE_FILES = tuple(sorted(set(EXPECTED_SCANNED)))


def count_word():
    """Скільки записів у `GUARDS` — українським словом, як пише шапка.

    Число росте з кожним новим сторожем, і тест не має його знати: хардкод
    ламався б на кожному записі, а не на тому, що тест перевіряє.
    """
    return d.CARDINALS[len(d.record("guards")) - 1][1]


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
        shutil.copytree(REPO_ROOT / "scripts", self.root / "scripts",
                        ignore=shutil.ignore_patterns("__pycache__"))
        for relative in FIXTURE_FILES:
            if relative.startswith("scripts/"):
                continue
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
                line = text.splitlines()[number - 1]
                on_line = d.figure_hits(line, source)
                self.assertEqual(1, len(on_line),
                                 "рядок мусить нести рівно одне число джерела")

    def test_the_cited_commands_all_exist(self):
        """Сторож командної половини: жодна згадана команда не вигадана."""
        findings, unknowns = d.command_findings(REPO_ROOT, d.SCANNED)
        self.assertEqual([], unknowns, "невідомість — не «команда є»")
        self.assertEqual([], findings, "кожна згадана команда мусить існувати")

    def test_the_audit_mode_prints_the_enrolled_numbers(self):
        code, out, err = run(["--audit"])
        self.assertEqual(0, code, err)
        units, undated = d.audit_counts()
        self.assertIn(str(units), out)
        self.assertIn(str(undated), out)

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
                  "разом %s записів" % count_word(), "разом шість записів")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «шість»", out)
        self.assertIn(str(len(d.record("guards"))), out)

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


class CommandTest(FixtureMixin):
    """Число має команду — і команда мусить існувати (клас `--measure-head`)."""

    def test_a_fabricated_option_is_a_finding(self):
        """Живий приклад цього PR: `--check`, якого в скрипті немає."""
        self.edit("scripts/guard_witnesses.py",
                  "(`python3 scripts/guard_witnesses.py`)",
                  "(`python3 scripts/guard_witnesses.py --check`)")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("вигадана команда", out)
        self.assertIn("не оголошує --check", out)

    def test_a_cited_script_that_does_not_exist_is_a_finding(self):
        self.edit("docs/agents/local-environment.md",
                  "scripts/doc_numbers.py --list",
                  "scripts/no-such-thing.py --list")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("команда, якої немає в дереві", out)
        self.assertIn("scripts/no-such-thing.py", out)

    def test_options_belong_to_the_command_before_them(self):
        """Рядок із двома скриптами — одна команда: опція належить тій, що перед нею."""
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_an_option_with_no_command_before_it_is_not_checked(self):
        self.edit("scripts/verdict_field_mutants.py",
                  "Клас, який тут ловиться",
                  "Опція `--not-of-anyone` сама по собі. Клас, який тут ловиться")
        code, _, err = self.check()
        self.assertEqual(0, code, err)


class NumberShapeTest(FixtureMixin):
    """Діри, названі оглядом PR #1295: кожна мусить бути червона."""

    def test_a_position_line_that_names_another_key_is_an_unknown(self):
        self.edit("docs/agents/local-environment.md",
                  "(`#1270`). The eighth case of", "(`#1233`). The eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("називає інший ключ (#1233)", err)

    def test_a_position_line_that_names_no_key_is_an_unknown(self):
        self.edit("docs/agents/local-environment.md",
                  "(`#1270`). The eighth case of", ". The eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("не називає ключа серії", err)

    def test_a_line_with_two_numbers_of_the_same_source_is_an_unknown(self):
        """Шапка обіцяє «рівно одне число» — і CLI мусить це тримати, не лише тест."""
        self.edit("docs/agents/local-environment.md",
                  "(`#1270`). The eighth case of",
                  "(`#1270`). The eighth case of, and the eighth case of again")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("а мусить рівно одне", err)

    def test_the_control_line_names_its_own_key(self):
        """`empty_shape_mutants.py:5` — той самий ключ, і це видно як зелений."""
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_control_line_that_names_another_key_is_an_unknown(self):
        self.edit("scripts/empty_shape_mutants.py",
                  "за дві цілі. У PR #1254", "за дві цілі. У PR #1233")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("називає інший ключ (#1233)", err)

    def test_an_ordinal_written_in_digits_is_accepted(self):
        """«The 8th case of» — те саме число, і воно мусить проходити."""
        self.edit("docs/agents/local-environment.md",
                  "The eighth case of", "The 8th case of")
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_number_word_past_the_table_is_an_unknown(self):
        """Обіцянка «число поза таблицею — невідомість» мусить бути кодом."""
        self.edit("scripts/declared_fields.py",
                  "Кожне оголошене поле", "Тринадцять записів. Кожне оголошене поле")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("поза таблицею написань", err)


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
                  "разом %s записів" % count_word(),
                  "разом %s записів, і один запис окремо" % count_word())
        code, _, err = self.check()
        self.assertEqual(0, code, err)


if __name__ == "__main__":
    unittest.main()
