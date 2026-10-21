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
    код-спані (цитата, не твердження) і СТАРШІ знімки `gh`-вікна (91/90/10/9,
    93/87/11/9) механізм НЕ ловить, і тест це стверджує. Це не діра, яку тут
    замовчують, а названа межа — «число, яке щось стверджує про дерево»
    відрізняє оглядач, не regex;
  * `ShellStepTest` і `ScanWindowTest` (#1328) — кроки серії на shell у
    `SCANNED`, і числа `gh`-вікна в них: до #1328 списку `.sh` не містив ЖОДЕН,
    тож числа в `new-lane.sh` не бачив жоден гейт. Тут — і те, що незарахований
    верхньорівневий крок червонить (`test_every_top_level_shell_step_is_enrolled`),
    і сторож на число класу в `.sh`, і сторож на ЗАПИС `SCAN_WINDOW` (мутація
    реєстру червонить), і те, що `102` → `999` тепер знахідка;
  * `GrammaticalFormTest` і `SingularBoundaryTest` (#1315) — форма іменника за
    класом ЧИСЛА і за МОВОЮ: реєстр на 41 мусить бути зеленим із граматично
    правильним «41 запис», червоним із чужим числом і з «41 записів»; а «один
    запис» словом лишається зворотом, тоді як «1 запис» цифрою — уже число.
    Англійська тут окремо: «21 cases» — ПРАВИЛЬНА множина, і вона мусить бути
    зеленою, а не хибною відмовою (той бік, у який клас-таблиця брехала б із
    одним українським правилом для всіх мов).

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
    ("docs/agents/gates.md", "gate is about the guards themselves"),
    ("scripts/guard_witnesses.py", "випадок класу"),
    ("scripts/empty_shape_mutants.py", "випадок за дві цілі"),
    ("scripts/guard_witnesses.py", "не стає галасливим"),
    ("docs/agents/gates.md", "series has"),
    ("docs/agents/gates.md", "figures of"),
    ("docs/agents/gates.md", "sit on a line"),
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
    ("scripts/new-lane.sh", "«премиса є»"),
    ("scripts/new-lane.sh", "«у тілі немає шляхів у бектиках»"),
    ("scripts/new-lane.sh", "«ім'я неоднозначне»"),
    ("scripts/new-lane.sh", "«відмов»"),
)

EXPECTED_SCANNED = (
    "CONTRIBUTING.md",
    "docs/agents/local-environment.md",
    "docs/agents/gates.md",
    "docs/agents/issue-tracker.md",
    "scripts/guard_witnesses.py",
    "scripts/empty_shape_mutants.py",
    "scripts/verdict_field_mutants.py",
    "scripts/declared_fields.py",
    "scripts/issue_premise.py",
    "scripts/app-dependency-licenses.sh",
    "scripts/check-app-dns.sh",
    "scripts/check-base-sync.sh",
    "scripts/check-migration-slot.sh",
    "scripts/dependency-licenses.sh",
    "scripts/generate-keystore.sh",
    "scripts/merge-pr.sh",
    "scripts/mutate.sh",
    "scripts/new-lane.sh",
    "scripts/pre-release.sh",
    "scripts/review-clone.sh",
    "scripts/review-pass.sh",
    "scripts/run-instrumented-suites.sh",
    "scripts/source-budget-snapshot.sh",
    "scripts/telegram-login-spike.sh",
    "scripts/test-all.sh",
    "scripts/test-changed.sh",
    "scripts/wait-for-android-package-service.sh",
    "scripts/wide-window.sh",
    "scripts/wizard-spec40-rules-matrix.sh",
)

# Файли, з яких складається підставлене дерево. Механізм читає їх як текст і
# розв'язує ЗГАДАНІ в них команди, тож копіюється все `scripts/` — інакше кожна
# цитата читалася б як «команди немає в дереві» (це і є перша версія фікстури,
# яка на цьому й спіткнулася).
FIXTURE_FILES = tuple(sorted(set(EXPECTED_SCANNED)))


def count_phrase():
    """«22 записи» — число й іменник РІВНО так, як їх пише шапка `guard_witnesses`.

    Українська форма залежить від числа: 21 — «запис», 22–24 — «записи»,
    далі — «записів». Механізм знає всі три класи (`NOUNS_DATA` і
    `grammatical_class` у `doc_numbers.py`, #1315), тож шапка пише ту форму, яка
    граматична, а тест мусить брати ту саму — інакше він ламався б на кожному
    новому сторожі (живий приклад: двадцять другий сторож `GUARDS` у #1299
    перевів шапку на «22 записи», і два тести, що знали форму «записів», упали;
    другий живий приклад — #1315: реєстр на 41 вимагає «41 запис», і до #1315
    це була невідомість, а не число). Число тут не хардкодиться: його звіряє сам
    механізм (`FIGURES`: `("count", "guards")`), а не цей рядок.
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
        self.edit("docs/agents/gates.md",
                  "The eighth case of", "The seventh case of")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «seventh»", out)
        self.assertIn("«eighth»", out)
        # Рядок фігури — 33 у `gates.md`: абзац переїхав туди з `Mutations`
        # (#1366). Число тут — МІСЦЕ, і воно мусить збігатися з деревом; зсув
        # видно як знахідку, а не як тишу.
        self.assertIn("docs/agents/gates.md:33", out)

    def test_a_count_that_disagrees_with_the_registry_is_a_finding(self):
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом шість записів")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «шість»", out)
        self.assertIn(str(len(d.record("guards"))), out)

    def test_a_number_nobody_derives_is_a_finding(self):
        """Число на рядку, якого немає в реєстрі, проходить повз нього?"""
        self.edit("docs/agents/gates.md",
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
                  "«будь-який збіг-дерево» — 134", "«будь-який збіг-дерево» — 135")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «135»", out)
        self.assertIn("134", out)

    def test_the_same_price_in_the_tracker_is_a_finding_too(self):
        """Другий файл тієї самої щілини: `docs/agents/issue-tracker.md`."""
        self.edit("docs/agents/issue-tracker.md",
                  "«без крапки, будь-який збіг-дерево» — 133",
                  "«без крапки, будь-який збіг-дерево» — 132")
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «132»", out)
        self.assertIn("132", out)

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
        self.edit("docs/agents/gates.md",
                  "gate is about the guards themselves", "gate is about something else")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("якір мусить траплятися рівно раз", err)

    def test_an_anchor_that_is_not_alone_is_an_unknown(self):
        self.edit("docs/agents/gates.md",
                  "The eighth case of",
                  "The eighth case of ... and the gate is about the guards "
                  "themselves, and the eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("якір мусить траплятися рівно раз", err)

    def test_a_line_that_no_longer_carries_the_number_is_an_unknown(self):
        self.edit("docs/agents/gates.md",
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
        self.edit("docs/agents/gates.md",
                  "(`#1270`). The eighth case of", "(`#1233`). The eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("називає інший ключ (#1233)", err)

    def test_a_position_line_that_names_no_key_is_an_unknown(self):
        self.edit("docs/agents/gates.md",
                  "(`#1270`). The eighth case of", ". The eighth case of")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("не називає ключа серії", err)

    def test_a_line_with_two_numbers_of_the_same_source_is_an_unknown(self):
        """Шапка обіцяє «рівно одне число» — і CLI мусить це тримати, не лише тест."""
        self.edit("docs/agents/gates.md",
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
        self.edit("docs/agents/gates.md",
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
                  "«ціна правила про теку» — 77", "«ціна правила про теку» — 77 і 200")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("а мусить рівно одне", err)


class ShellStepTest(FixtureMixin):
    """Кроки серії на shell теж скануються (#1328).

    До #1328 `SCANNED` не містив жодного `scripts/*.sh`, тож числа класу в
    `new-lane.sh` — найновішому кроці (резервує тікет, бере свіжий `main`, друкує
    факти для брифа) — не бачив ЖОДЕН гейт: зіпсоване число лишалося зеленим і в
    `doc_numbers`, і в усьому наборі. Прогін на незміненому дереві дав 0 знахідок
    і 0 невідомостей на всіх 20 верхньорівневих кроках, тож список розширено на
    ВСІ, а не лише на цитовані: «цитований» список залежав би від себе самого
    (`cited_scripts` читає `SCANNED`), а нецитований крок лишився б невидимим
    рівно там, де діра й була.
    """

    def test_every_top_level_shell_step_is_enrolled(self):
        """Крок, який не зарахували, знову стане невидимим — це видно тестом."""
        on_disk = sorted(path.name for path in (REPO_ROOT / "scripts").glob("*.sh"))
        enrolled = sorted(
            Path(one).name for one in d.SCANNED if one.endswith(".sh")
        )
        self.assertEqual(
            on_disk, enrolled,
            "кожен верхньорівневий `scripts/*.sh` мусить бути в `SCANNED`",
        )

    def test_a_number_in_a_shell_step_is_a_finding(self):
        """Сторож: число класу в `.sh` мусить виводитися, а не бути невидимим.

        Це той бік межі, який #1328 закриває: файл у списку — і незараховане
        число в ньому вже знахідка.
        """
        self.edit("scripts/new-lane.sh",
                  "# Коди виходу:",
                  "# шість випадків класу\n# Коди виходу:")
        code, out, err = self.check()
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("шість випадків", out)

    def test_a_gh_window_number_in_a_shell_step_is_a_finding(self):
        """#1328: числа `--scan 200` пінить ЗАПИС `SCAN_WINDOW`, а не прогін.

        Розширення `SCANNED` зробило файл ВИДИМИМ, але числа — не пінованими:
        за ними не йде жоден іменник із `NOUNS_DATA`, а джерело їхнє — `gh`.
        Пінить їх джерело `("record", "scan_window", …)` — той самий прийом, що
        `PRICES`. Тест стверджує це прогоном: `102` → `999` — знахідка, а не
        зелений (як було до #1328 і як лишалося після самого лише розширення).
        """
        self.edit("scripts/new-lane.sh", "«премиса є» — 102", "«премиса є» — 999")
        code, out, err = self.check()
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("scan_window", out)


class ScanWindowTest(unittest.TestCase):
    """Запис `gh`-вікна — сторож, а не проза (#1328).

    Числа кроку премиси в `new-lane.sh` до #1328 не пінив жоден гейт: файл не
    був у `SCANNED`, а розширення списку їх не пінить — за ними не йде жоден
    іменник із `NOUNS_DATA`. Пінить їх ЗАПИС `SCAN_WINDOW` (як `PRICES`), і
    мутація саме реєстру мусить почервонити цей сторож: інакше число в прозі
    знову нічим не тримається.
    """

    def test_the_recorded_window_pins_the_shell_step_numbers(self):
        findings, unknowns = d.check()
        self.assertEqual([], unknowns, "невідомість — не «число виводиться»")
        self.assertEqual([], findings, "чотири числа вікна мусять виводитися із запису")
        enrolled = {
            source[2]
            for _, _, source in d.FIGURES
            if source[0] == "record" and source[1] == "scan_window"
        }
        self.assertEqual(
            4, len(enrolled),
            "усі чотири класи вікна мусять бути зараховані джерелом `record`",
        )
        self.assertEqual(
            set(d.measured("scan_window")), enrolled,
            "зараховані класи мусять бути ключами реєстру",
        )


class BoundaryTest(FixtureMixin):
    """Межа названа машинно: чого механізм НЕ бачить, тест це стверджує."""

    def test_a_figure_of_an_unlisted_shape_passes_silently(self):
        """Одиниці й секунди поза формами: 999 MiB нічого не червонить."""
        self.edit("docs/agents/gates.md",
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
        self.edit("docs/agents/gates.md",
                  "The eighth case of",
                  "The eighth case of\nThe text used to read `the seventh case of "
                  "\"a check that does not check\"`.")
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_count_in_the_singular_is_not_a_count(self):
        """«один запис» — вказівний зворот; слово, а не форма, його виключає.

        Сама одинична форма тепер У таблиці (#1315): її вимагає 21/41/101. Тихою
        цю фразу робить `DEMONSTRATIVES` — «один»/«one» перед одиничною формою є
        зворотом, а не кількістю; цифра «1 запис» — уже число
        (`SingularBoundaryTest`).
        """
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(),
                  "разом %s, і один запис окремо" % count_phrase())
        code, _, err = self.check()
        self.assertEqual(0, code, err)

    def test_a_gh_window_number_in_a_scanned_file_passes_silently(self):
        """Межа названа машинно: СТАРШІ знімки вікна гейт НЕ бачить — і не вдає.

        `--scan 200` питає `gh` про рухоме вікно останніх тікетів, тож джерела в
        `derive` для цих чисел немає — джерело тут ЗАПИС (`SCAN_WINDOW`). Числа
        91/90/10/9 (і 93/87/11/9) — знімки ІНШИХ ревізій, і в `FIGURES` вони не
        входять: джерело `record` читає голі числа, а ті абзаци ставлять по
        кілька на рядок (межа — у шапці `doc_numbers.py`). Тест стверджує це
        прогоном, а не замовчує: правка такого числа проходить зеленим.
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


class GrammaticalFormTest(FixtureMixin):
    """Форма іменника мусить бути формою САМЕ цього числа, а не будь-якою (#1315).

    До #1315 таблиця `NOUNS` знала лише множину, тож шапка реєстру, що переріс
    40 і вимагає ОДНИНИ («41 запис»), ставала невідомістю (код 2) — «рядок
    більше не несе числа». Це структурна стеля серії: кожен новий сторож
    лишався незарахованим, тобто недоведеним. Тут — прогоном, на підставленому
    дереві: реєстр на 41 мусить бути зеленим із «41 запис» і червоним, коли
    число не те, а «41 записів» — відмова з назвою форми.

    Друга половина — МОВА форми. Англійська після 21/41/101 хоче МНОЖИНИ
    («21 cases» — правильно), тож клас беруть з `required_classes`, а не з
    одного українського правила: інакше правильний англійський рядок ставав би
    хибною відмовою — найдорожчим різновидом червоного, бо блокує правильну
    роботу.
    """

    def registry(self, size):
        """Реєстр названого розміру — те, що виводить `("count", "guards")`."""
        return unittest.mock.patch.object(d, "guards", lambda: tuple(range(size)))

    def test_a_singular_header_for_a_number_ending_in_one_is_green(self):
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 41 запис")
        with self.registry(41):
            code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_a_singular_header_with_the_wrong_number_is_a_finding(self):
        """Контроль: те саме дерево, але число не те — мусить почервоніти."""
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 40 запис")
        with self.registry(41):
            code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «40»", out)

    def test_a_plural_form_for_a_number_ending_in_one_is_a_named_finding(self):
        """«41 записів» — число правильне, форма ні; відмова мусить назвати форму."""
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 41 записів")
        with self.registry(41):
            code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("«41 записів»", out)
        self.assertIn("однина", out)

    def test_a_few_form_for_a_number_ending_in_two_is_green(self):
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 22 записи")
        with self.registry(22):
            code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_a_compound_number_ending_in_one_is_green(self):
        """101 — складене число, і воно теж вимагає однини («101 запис»)."""
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 101 запис")
        with self.registry(101):
            code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def cases(self, size):
        """`CASES` названого розміру — англійські рядки серії теж мають клас.

        Дописи йдуть У КІНЕЦЬ, щоб місця `#1270`/`#1254`/`#1257` не поїхали.
        """
        extra = tuple(("#dummy%d" % n, "проба")
                      for n in range(size - len(d.CASES)))
        return unittest.mock.patch.object(d, "CASES", tuple(d.CASES) + extra)

    def test_an_english_plural_after_a_number_ending_in_one_is_green(self):
        """«21 cases» — ПРАВИЛЬНА англійська, і вона не сміє бути хибною відмовою.

        Це той бік, у який клас-таблиця брехала б, якби тримала одне українське
        правило для всіх мов: по-англійськи після 21/41/101 іде МНОЖИНА.
        """
        self.edit("docs/agents/gates.md",
                  "series has **eight** cases", "series has **21** cases")
        with self.cases(21):
            code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_an_english_singular_for_a_plural_number_is_a_named_finding(self):
        """Контроль: «21 case» — англійська форма не та, і це ВИДНО."""
        self.edit("docs/agents/gates.md",
                  "series has **eight** cases", "series has **21** case")
        with self.cases(21):
            code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("«21 case»", out)
        self.assertIn("множина", out)

    def test_an_english_singular_for_one_is_green(self):
        """Другий бік англійського правила: для 1 — однина («1 guard»)."""
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(), "разом 1 guard")
        with self.registry(1):
            code, out, err = self.check()
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))


class SingularBoundaryTest(FixtureMixin):
    """Одинична форма ЦИФРОЮ — число; зворот «один запис» словом тихий (#1315).

    Словесну половину цієї межі тримає `BoundaryTest`: там вона й була названа.
    """

    def test_a_digit_singular_is_a_number(self):
        """Та сама форма словом — зворот, а цифрою — твердження, і воно видно."""
        self.edit("scripts/guard_witnesses.py",
                  "разом %s" % count_phrase(),
                  "1 запис окремо.\nразом %s" % count_phrase())
        code, out, _ = self.check()
        self.assertEqual(1, code)
        self.assertIn("рядок каже «1»", out)


if __name__ == "__main__":
    unittest.main()
