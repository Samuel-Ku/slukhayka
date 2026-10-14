"""Число в доку мусить мати команду, яка його відтворює — і мусить падати (#1277).

`scripts/doc_numbers.py` виводить число з запису в дереві, а не читає його з
прози. Тут — його власні перевірки, і жодна з них не «сьома перевірка» самого
себе:

  * `RepositoryTest` — на справжньому дереві: жодного числа, яке не
    виводиться, і жодної невідомості; серія, реєстр і перелік сканованих файлів
    — рівно ті, що названі (друге джерело правди: звузити їх мовчки не можна,
    це видно як правку тесту); і реєстр ВИМІРІВ (`("record", …)`, #1308): шапка
    `issue_premise.py` та `docs/agents/issue-tracker.md` мусять дорівнювати
    `PRICES`, а не лише одна одній;
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

Зараховані в `scripts/guard_witnesses.py` сторожі цього файлу — піни на
справжньому дереві (`test_every_number_derives_on_the_real_tree`,
`test_the_cited_commands_all_exist` і новий
`test_the_enrolled_prices_are_the_recorded_measurement`, #1308); решта
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
    ("docs/agents/local-environment.md", "series has"),
    ("docs/agents/local-environment.md", "figures of"),
    ("docs/agents/local-environment.md", "sit on a line"),
    ("CONTRIBUTING.md", "показав межу самого правила"),
    ("CONTRIBUTING.md", "і другий машинний гейт"),
    ("scripts/issue_premise.py", "«ціна правила про теку»"),
    ("scripts/issue_premise.py", "«без крапки в імені»"),
    ("scripts/issue_premise.py", "«разом із кореневими»"),
    ("scripts/issue_premise.py", "«без крапки, разом із кореневими»"),
    ("scripts/issue_premise.py", "«будь-який збіг-дерево»"),
    ("scripts/issue_premise.py", "«без крапки, будь-який збіг-дерево»"),
    ("docs/agents/issue-tracker.md", "«ціна правила про теку»"),
    ("docs/agents/issue-tracker.md", "«без крапки в імені»"),
    ("docs/agents/issue-tracker.md", "«разом із кореневими»"),
    ("docs/agents/issue-tracker.md", "«без крапки, разом із кореневими»"),
    ("docs/agents/issue-tracker.md", "«будь-який збіг-дерево»"),
    ("docs/agents/issue-tracker.md", "«без крапки, будь-який збіг-дерево»"),
)

EXPECTED_SCANNED = (
    "CONTRIBUTING.md",
    "docs/agents/local-environment.md",
    "docs/agents/issue-tracker.md",
    "scripts/guard_witnesses.py",
    "scripts/empty_shape_mutants.py",
    "scripts/verdict_field_mutants.py",
    "scripts/declared_fields.py",
    "scripts/issue_premise.py",
)

# Файли, з яких складається підставлене дерево. Механізм читає їх як текст і
# розв'язує ЗГАДАНІ в них команди, тож копіюється все `scripts/` — інакше кожна
# цитата читалася б як «команди немає в дереві» (це і є перша версія фікстури,
# яка на цьому й спіткнулася).
FIXTURE_FILES = tuple(sorted(set(EXPECTED_SCANNED)))


def count_phrase():
    """«22 записи» — число й іменник РІВНО так, як їх пише шапка `guard_witnesses`.

    Українська форма множини залежить від числа: 21 — «запис», 22–24 — «записи»,
    далі — «записів». Механізм ловить ЛИШЕ форми множини (`NOUNS`), тож шапка
    пише ту форму, яка граматична, а тест мусить брати ту саму — інакше він
    ламався б на кожному новому сторожі (живий приклад: двадцять другий сторож
    `GUARDS` у #1299 перевів шапку на «22 записи», і два тести, що знали форму
    «записів», упали). Число тут не хардкодиться: його звіряє сам механізм
    (`FIGURES`: `("count", "guards")`), а не цей рядок.
    """
    words = {word for pair in d.CARDINALS for word in pair}
    text = (REPO_ROOT / "scripts" / "guard_witnesses.py").read_text(encoding="utf-8")
    for line in text.splitlines():
        if "разом " not in line:
            continue
        phrase = line.split("разом ", 1)[1].split(",")[0].strip()
        first = phrase.split(" ")[0]
        if first.isdigit() or first in words:
            return phrase
    raise AssertionError("шапка `guard_witnesses` більше не пише числа записів")


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
        # Копіюється РІВНО те, що механізм читає: скановані файли й скрипти, на
        # які вони посилаються. Копіювати все `scripts/` — це та сама ціна, що
        # й повний реєстр, а кожен тест тут платить її за себе.
        wanted = set(FIXTURE_FILES) | d.cited_scripts(REPO_ROOT, d.SCANNED)
        for relative in sorted(wanted):
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

    def test_the_enrolled_prices_are_the_recorded_measurement(self):
        """Ціна правила про теку (#1308): шапка — проти реєстру, а не проти віри.

        Сторож, який ловить «132 проти 133» у шапці `issue_premise.py` і в
        `docs/agents/issue-tracker.md`: обидва файли в `SCANNED`, а кожне число —
        в `FIGURES` джерелом `("record", "prices", ключ)`. Гейт читає РЕЄСТР
        (`issue_premise.PRICES`), а не git: копія дерева без `.git`, у якій
        біжить кожен сторож, і CI без `origin/main` дали б нуль (межа названа в
        шапці `doc_numbers.py`). Тому тут перевіряється рівно те, що тримає
        гейт: рядок мусить називати свій клас, нести рівно одне число — і це
        число мусить бути тим, що записано в реєстрі.
        """
        enrolled = [
            (relative, anchor, source)
            for relative, anchor, source in d.FIGURES
            if source[0] == "record"
        ]
        self.assertTrue(enrolled, "ціна в шапках мусить бути зарахована")
        for relative, anchor, source in enrolled:
            with self.subTest(file=relative, key=source[2]):
                self.assertIn(source[2], anchor, "якір називає фразу класу")
                text = (REPO_ROOT / relative).read_text(encoding="utf-8")
                _, line = d.line_with_anchor(text, anchor, relative)
                on_line = d.figure_hits(line, source)
                self.assertEqual(1, len(on_line), "рядок несе рівно одне число")
                self.assertEqual(
                    str(d.derive(source)), d.normalise(on_line[0][1]),
                    "шапка мусить дорівнювати реєстру, а не сусідньому рядку",
                )

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
                  "разом %s" % count_phrase(), "разом шість записів")
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

    def test_a_header_price_that_disagrees_with_the_record_is_a_finding(self):
        """Живий приклад тікета #1308: `132` у шапці `issue_premise.py` став 133.

        Мутація — рівно та, якою огляд PR #1306 довів щілину: до #1308 цей
        рядок не бачив ніхто, і заміна проходила зеленим.
        """
        self.edit("scripts/issue_premise.py",
                  "«будь-який збіг-дерево» — 132", "«будь-який збіг-дерево» — 133")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «133»", out)
        self.assertIn("132", out)

    def test_the_same_price_in_the_tracker_is_a_finding_too(self):
        """Другий файл тієї самої щілини: `docs/agents/issue-tracker.md`."""
        self.edit("docs/agents/issue-tracker.md",
                  "«без крапки, будь-який збіг-дерево» — 131",
                  "«без крапки, будь-який збіг-дерево» — 130")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «130»", out)
        self.assertIn("131", out)

    def test_a_case_word_in_the_premise_header_is_now_a_finding(self):
        """Критерій #1308: файл у `SCANNED` — і число класу в ньому вже видно."""
        self.edit("scripts/issue_premise.py",
                  "Межі, названі прямо:", "Сьомий випадок класу тут.\nМежі, названі прямо:")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("«Сьомий випадок»", out)
        self.assertIn("числа немає в жодному записі дерева", out)

    def test_a_contributing_case_number_that_moves_is_a_finding(self):
        """Той самий клас у `CONTRIBUTING.md`: п'ятий випадок серії — це `#1254`."""
        self.edit("CONTRIBUTING.md",
                  "П'ятий випадок (`#1254`)", "Шостий випадок (`#1254`)")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «Шостий»", out)
        self.assertIn("#1254", out)

    def test_a_quoted_wrong_number_is_invisible_only_as_a_citation(self):
        """Історію помилки в `CONTRIBUTING.md` тримають бектики, не слово.

        «Дев'ятий випадок» — цитата того, що шапка механізму казала **хибно**;
        зняти бектики означає зробити з неї твердження, і тоді механізм мусить
        її побачити.
        """
        self.edit("CONTRIBUTING.md", "`«Дев'ятий випадок»`", "«Дев'ятий випадок»")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("«Дев'ятий випадок»", out)


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

    def test_a_record_line_that_does_not_name_its_key_is_an_unknown(self):
        """Запис виміру: число класу під чужою фразою — невідомість, не згода."""
        figures = tuple(d.FIGURES) + ((
            "scripts/issue_premise.py",
            "«ціна правила про теку»",
            ("record", "prices", "без крапки в імені"),
        ),)
        _, unknowns = d.check(self.root, figures=figures)
        self.assertTrue(
            any("не називає ключа «без крапки в імені»" in one for one in unknowns),
            unknowns,
        )

    def test_a_record_line_with_two_numbers_is_an_unknown(self):
        """Шапка обіцяє «рівно одне число» — і для запису виміру теж."""
        self.edit("docs/agents/issue-tracker.md",
                  "«ціна правила про теку» — 75", "«ціна правила про теку» — 75 і 200")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("а мусить рівно одне", err)


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
                  "разом %s" % count_phrase(),
                  "разом %s, і один запис окремо" % count_phrase())
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_gh_window_number_in_a_scanned_file_passes_silently(self):
        """Межа названа машинно: чисел `--scan 200` гейт НЕ бачить — і не вдає.

        `--scan 200` питає `gh` про рухоме вікно останніх тікетів; у гейта немає
        ні `gh`, ні мережі, тож джерела в `derive` для цих чисел немає. Вони
        лишаються прозою з названою командою і ревізією (межа — у шапці
        `doc_numbers.py`), і тест це стверджує, а не замовчує: правка такого
        числа в СКАНОВАНОМУ файлі проходить зеленим.
        """
        self.edit("docs/agents/issue-tracker.md", "91/90/10/9 на", "92/90/10/9 на")
        code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_a_dead_command_quoted_as_a_lesson_keeps_the_mechanism_out_of_itself(self):
        """Межа названа машинно: `doc_numbers.py` поза `SCANNED` — і чому.

        Його шапка цитує вигадану опцію першої версії механізму — урок, а не
        команда; командна половина читає саме код-спани, тож файл у `SCANNED`
        почервонив би на власному прикладі. Тест кличе ту саму половину на
        цьому файлі й вимагає побачити висновок — прогоном, а не прозою.
        """
        self.assertNotIn("scripts/doc_numbers.py", d.SCANNED)
        findings, unknowns = d.command_findings(REPO_ROOT, ("scripts/doc_numbers.py",))
        self.assertEqual([], unknowns)
        self.assertTrue(
            any("не оголошує --check" in one for one in findings),
            "цитата вигаданої опції мусить бути видима — саме тому файл поза списком",
        )


if __name__ == "__main__":
    unittest.main()
