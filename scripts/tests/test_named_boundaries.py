"""Межа мусить мати ПРОГІН — і кожна межа цього модуля названа в коді (#1278).

`scripts/named_boundaries.py` вимагає від межі двох прогонів: свідок сліпоти
зелений БЕЗ мутації і червоний ПІД мутацією, яка межу закриває. Тут те саме з
другого боку, і кожна перевірка має прогоновий бік:

  * `RegistryTest` жене механізм на СПРАВЖНЬОМУ дереві — тим самим викликом, що
    CI: кожна межа реєстру мусить бути названа в коді, мати свідка й бути
    показані прогоном;
  * `BlindnessTest` — САМІ СВІДКИ: кожен показує, що механізм сліпий до форми,
    яку межа називає, і що форма справжня (для читання AST — рантайм бачить
    поля, яких пін не бачить). Це ті цілі, які жене `named_boundaries`;
  * `SectionTest` — та половина, якої не було в #1265: межа, написана в коді
    БЕЗ свідка, — знахідка; та сама межа, записана лише в звіті (поза коренем
    або поза покритим розділом), невидима ЖОДНОМУ механізму — і це показано
    прогоном, а не обіцяно;
  * `RunTest` — підставлене дерево: жива межа проходить, свідок, зелений під
    своїм закриттям, — відмова з назвою межі; закриття, якого більше нема де
    застосувати, — невідомість.

`SectionTest` тримає і кожну відмову покриття: пункт без запису, запис без
мутації, запис без пункту, закриття в модулі свідка, зниклий якір, зниклий
розділ.

Межі цього модуля, названі тут, а не лише в звіті:

  * `SECTIONS` і `BOUNDARIES` механізму піняться ЛІТЕРАЛЬНО нижче: звузити
    реєстр мовчки не можна, це видно як правку тесту;
  * те, що мутація ЗАКРИВАЄ саму форму межі, а не ламає щось інше, механізм не
    перевіряє — це судження автора, і мутація лежить у дифі поряд із межею.
    Одну форму закрито машинно, і вона перевірена тут: закриття не сміє правити
    модуль свідка
    (`test_a_closing_mutation_that_edits_the_witness_module_is_refused`);
  * зарахування нового розділу — руками (межа механізму, шапка).
"""

import ast
import contextlib
import dataclasses
import importlib.util
import io
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "named_boundaries.py"
sys.path.insert(0, str(MECHANISM.parent))

import declared_fields as d  # noqa: E402
import named_boundaries as n  # noqa: E402

# Друге джерело правди: реєстр записаний тут буквально, тож зникнення межі —
# правка цього рядка, а не тиха згода.
EXPECTED_SECTIONS = (("scripts/declared_fields.py",
                      "Межа названа тут, у коді, а не лише в звіті:"),)
# Розділи меж, яких механізм НЕ читає (інша форма пункту), і скільки заголовків
# меж у дереві взагалі: числа зі шапки, виведені цим тестом — новий заголовок
# червонить його, і шапку правлять разом із ним.
EXPECTED_UNREADABLE_SECTIONS = (
    ("scripts/empty_shape_mutants.py", "Межі, названі прямо:"),
    ("scripts/issue_premise.py", "Межі, названі прямо:"),
    ("scripts/tmp_budget.py", "Межі, названі прямо:"),
    ("scripts/verdict_field_mutants.py",
     "Межі, названі прямо (кожну доведено тестом або виміром, а не обіцянкою):"),
)
EXPECTED_BOUNDARY_HEADINGS = 11
EXPECTED_BOUNDARY_IDS = (
    "#1276-list",
    "#1276-run-built",
    "#1276-appearance",
    "#1276-prose",
    "#1276-constants",
    "#1276-reader",
)

# Файли, з яких складається підставлене дерево для перевірок покриття.
RECORD_FILES = (
    "scripts/declared_fields.py",
    "scripts/guard_witnesses.py",
    "scripts/verdict_field_mutants.py",
    "scripts/named_boundaries.py",
    "scripts/tests/test_fixture_environments.py",
)

# Межа, яку смуга #1265 назвала ЛИШЕ В ЗВІТІ («програму, присвоєну в хелпері,
# який кличе `setUp`, ця форма ще не ловить»). Тут вона — цитата для прогону:
# у звіті її не бачить ніщо, у коді її ловить покриття нижче.
NAMED_ONLY_IN_THE_REPORT = ("програму, присвоєну в хелпері, який кличе `setUp`, "
                            "ця форма ще не ловить")


def boundary(identifier: str, claim: str, where: str, witness: str,
             edits=()) -> n.Boundary:
    return n.Boundary(id=identifier, claim=claim, where=where, witness=witness,
                      edits=tuple(edits))


class TreeTest(unittest.TestCase):
    """Підставлене дерево: копії записів і правки, які мусять бути видно."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="named-boundaries-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / "tree"
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

    def coverage(self, **substitutes):
        report = n.coverage(self.root, **substitutes)
        return report


class BlindnessTest(unittest.TestCase):
    """Свідки меж: механізм сліпий до названої форми — і форма справжня."""

    def test_a_record_that_a_run_builds_is_outside_the_list_deliberately(self):
        """`NestedRun` будує ПРОГІН, і в переліку записів його немає навмисно.

        Межа #1276 каже: записи, які виробляє код, у `RECORDS` не входять —
        оголошувати в них нічого. Прогін: механізм читає клас, коли його
        назвати, і не бачить його, поки не названо; `check` при цьому зелений.
        """
        where = "scripts/tests/test_fixture_environments.py"
        self.assertEqual(
            ("name", "status", "tests", "skipped", "output", "skipped_ids"),
            d.declared_fields(REPO_ROOT, where, "NestedRun"),
        )
        self.assertNotIn((where, "NestedRun"),
                         [(entry_where, class_name) for entry_where, class_name, _fields
                          in d.RECORDS])
        report = d.check(REPO_ROOT)
        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings])

    def test_a_pinned_field_that_nothing_holds_still_passes(self):
        """Пін ловить ПОЯВУ поля: поле, якого не тримає ніщо, проходить зелено.

        Підставлене дерево: запис оголошує поле, воно перелічене в `RECORDS` —
        і більше про нього ніде ні слова (жоден рядок його не читає). Для піна
        цього досить, і саме це межа називає: довести, що поле щось тримає, —
        робота автора й оглядача, а не статичного механізму.
        """
        shape = (
            "import dataclasses\n\n\n"
            "@dataclasses.dataclass(frozen=True)\n"
            "class Record:\n"
            "    name: str\n"
            "    owner: str = \"\"\n"
        )
        with tempfile.TemporaryDirectory(prefix="unheld-field-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "scripts" / "record.py").write_text(shape, encoding="utf-8")
            records = (("scripts/record.py", "Record", ("name", "owner")),)

            report = d.check(root, records=records)

            source = (root / "scripts" / "record.py").read_text(encoding="utf-8")
        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings],
                         "пін мусить бути сліпий до того, чи поле хтось тримає")
        self.assertEqual(1, source.count("owner"),
                         "поле мусить бути назване РІВНО раз — в оголошенні")

    def test_prose_beside_the_registry_is_read_by_nothing(self):
        """Коментар поруч із `RECORDS` — проза: брехня в ньому не червонить ніщо.

        Підставлене дерево: у шапці поруч із переліком з'являється рядок, який
        називає полем те, чого немає. Механізм зелений — бо прозу він не читає
        і не обіцяє читати; межа названа саме тому.
        """
        with tempfile.TemporaryDirectory(prefix="prose-near-registry-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            shutil.copy(REPO_ROOT / "scripts" / "guard_witnesses.py",
                        root / "scripts" / "guard_witnesses.py")
            text = (REPO_ROOT / "scripts" / "declared_fields.py").read_text(encoding="utf-8")
            self.assertEqual(1, text.count("# Хто що тримає"),
                             "проза мусить мати одне місце, куди її дописати")
            (root / "scripts" / "declared_fields.py").write_text(
                text.replace("# Хто що тримає",
                             "#   ghost — пінить RegistryTest\n# Хто що тримає", 1),
                encoding="utf-8",
            )
            records = tuple(entry for entry in d.RECORDS
                            if entry[0] == "scripts/guard_witnesses.py")

            report = d.check(root, records=records)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings],
                         "проза не перевіряється нічим — це і є межа")

    def test_a_registry_outside_a_dataclass_is_not_a_record(self):
        """Константа-реєстр поза датакласом — не поле запису, і невидима.

        Підставлене дерево: модульна константа з іменами, схожа на перелік
        полів. Механізм її не бачить: він читає датакласи, а не константи.
        """
        shape = (
            "EXTRA_FIELDS = (\"name\", \"owner\")\n\n\n"
            "class NotARecord:\n"
            "    pass\n"
        )
        with tempfile.TemporaryDirectory(prefix="constant-registry-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "scripts" / "constants.py").write_text(shape, encoding="utf-8")
            shutil.copy(REPO_ROOT / "scripts" / "guard_witnesses.py",
                        root / "scripts" / "guard_witnesses.py")
            records = tuple(entry for entry in d.RECORDS
                            if entry[0] == "scripts/guard_witnesses.py")

            report = d.check(root, records=records)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings],
                         "константа поза датакласом у перелік не попадає — це межа")

    def test_a_field_in_a_base_class_and_one_under_if_are_invisible_to_the_pin(self):
        """Дві сліпі плями читання AST — і РАНТАЙМ бачить те, чого пін не бачить.

        Підставлене дерево: у базовому класі поле `owner`, у тілі запису під
        `if True` — поле `ghost`. Пін (читач AST) бачить лише `name`, і зелений;
        `dataclasses.fields` у рантаймі бачить усі три. Це та сама форма, що
        «модуль зелений, тест-користувач червоний»: діра не вигадана, вона
        виміряна.
        """
        shape = (
            "import dataclasses\n\n\n"
            "@dataclasses.dataclass\n"
            "class Base:\n"
            "    owner: str = \"\"\n\n\n"
            "@dataclasses.dataclass\n"
            "class Record(Base):\n"
            "    name: str = \"\"\n\n"
            "    if True:\n"
            "        ghost: str = \"\"\n"
        )
        with tempfile.TemporaryDirectory(prefix="reading-blind-spots-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            module = root / "scripts" / "record_forms.py"
            module.write_text(shape, encoding="utf-8")
            records = (("scripts/record_forms.py", "Base", ("owner",)),
                       ("scripts/record_forms.py", "Record", ("name",)))

            report = d.check(root, records=records)
            spec = importlib.util.spec_from_file_location("record_forms", module)
            loaded = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(loaded)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings],
                         "пін мусить бути сліпий до бази й до анотації під `if`")
        runtime = {field.name for field in dataclasses.fields(loaded.Record)}
        self.assertEqual({"owner", "name", "ghost"}, runtime,
                         "рантайм бачить рівно те, чого пін не бачить")


class SectionTest(TreeTest):
    """Межа в коді без свідка — відмова; поза покритим розділом — невидима."""

    def test_a_boundary_named_in_code_without_a_witness_is_refused(self):
        """ТА САМА межа #1265, але названа В КОДІ: покриття її ловить.

        Живий приклад тікета: межа, названа лише в звіті, не існує. Тут вона
        дописана туди, де її мала б назвати смуга, — і механізм відмовляє з її
        текстом: межа в коді без свідка обіцяє більше, ніж робить код.
        """
        self.edit("scripts/declared_fields.py",
                  "  * механізм бачить ЛИШЕ перелічені записи",
                  "  * %s;\n  * механізм бачить ЛИШЕ перелічені записи"
                  % NAMED_ONLY_IN_THE_REPORT)

        report = self.coverage()

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual(1, len(report.findings), report.findings)
        self.assertIn(NAMED_ONLY_IN_THE_REPORT, report.findings[0].detail)
        self.assertIn("БЕЗ СВІДКА", report.findings[0].detail)

    def test_a_boundary_appended_to_a_bullet_or_written_as_prose_is_not_seen(self):
        """Межа в ХВОСТІ пункту або прозою-абзацом — невидима: це названа межа.

        Механізм читає ПУНКТИ, а не прозу: він бачить пункт за його ПОЧАТКОМ
        (якір), а текст, дописаний у хвіст пункту чи покладений абзацом у
        покритий розділ, для нього не існує. Закрити це можна було б лише
        читанням прози — тобто тим, чого механізм не робить і не обіцяє.
        Обидві форми названі в шапці й показані тут, а не замовчані (знахідка
        огляду PR #1296: доки їх не названо, вони були тихою дірою).
        """
        self.edit("scripts/declared_fields.py",
                  "    оглядачеві, куди дивитися, і саме тому вона коментар, "
                  "а не поле запису;\n",
                  "    оглядачеві, куди дивитися, і саме тому вона коментар, "
                  "а не поле запису. %s;\n" % NAMED_ONLY_IN_THE_REPORT)

        appended = self.coverage()
        self.assertEqual([], appended.blocked, appended.blocked)
        self.assertEqual([], [finding.detail for finding in appended.findings],
                         "межа в хвості пункту мусить бути невидима — це названа межа")

        self.edit("scripts/declared_fields.py",
                  "\nКоди виходу: 0 — набір полів",
                  "\nМежі, названі прозою-абзацом:\n\n  %s.\n\nКоди виходу: 0 — набір полів"
                  % NAMED_ONLY_IN_THE_REPORT)

        prose = self.coverage()
        self.assertEqual([], prose.blocked, prose.blocked)
        self.assertEqual([], [finding.detail for finding in prose.findings],
                         "межа прозою-абзацом мусить бути невидима — це названа межа")

    def test_a_bullet_that_only_mentions_a_claim_is_refused(self):
        """Пункт, який лише ЗГАДУЄ якір, — не пункт тієї межі: якір мусить бути початком.

        Інакше чужий текст перед якорем у пункті (цитата, «див. також», хвіст)
        сходив би за запис, і межа жила б у чужому пункті, а її власний пункт
        лишався б без господаря.
        """
        self.edit("scripts/declared_fields.py",
                  "  * пін ловить ПОЯВУ поля, а не його правду",
                  "  * Див. також: пін ловить ПОЯВУ поля, а не його правду")

        report = self.coverage()

        self.assertEqual([], report.blocked, report.blocked)
        mentioned = [finding.detail for finding in report.findings
                     if "ЗГАДАНА" in finding.detail]
        self.assertEqual(1, len(mentioned), [finding.detail for finding in report.findings])
        self.assertIn("#1276-appearance", mentioned[0])

    def test_a_boundary_outside_the_covered_section_is_not_seen(self):
        """Межа ПОЗА покритим розділом — невидима: це ціна, показана прогоном.

        Та сама межа #1265, дописана в ІНШОМУ місці файлу (і в іншому файлі
        дерева), не червонить нічого: механізм бачить лише перелічені розділи.
        Довести протилежне статично не можна — «схоже на розділ меж» і «розділ
        меж» розрізняє оглядач, не AST (ціну спроби вже сплачено, #1279).
        """
        self.edit("scripts/declared_fields.py",
                  "Коди виходу: 0 — набір полів",
                  "Межі, названі прозою:\n\n  * %s;\n\nКоди виходу: 0 — набір полів"
                  % NAMED_ONLY_IN_THE_REPORT)

        report = self.coverage()

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings],
                         "межа поза покритим розділом невидима — і це названо")

    def test_a_boundary_that_exists_only_in_the_report_is_invisible_entirely(self):
        """Звіт не в дереві: файл ПОЗА коренем не змінює вердикту взагалі."""
        before = self.coverage()
        report_path = self.base / "report-of-the-band.md"
        report_path.write_text("# Звіт\n\nМежа: %s.\n" % NAMED_ONLY_IN_THE_REPORT,
                               encoding="utf-8")

        after = self.coverage()

        self.assertEqual([], before.blocked, before.blocked)
        self.assertEqual([f.detail for f in before.findings],
                         [f.detail for f in after.findings])
        self.assertEqual([], [finding.detail for finding in after.findings],
                         "звіт поза коренем не читає жоден механізм — це причина, "
                         "чому межа мусить бути в коді")

    def test_a_record_without_a_mutation_is_refused(self):
        """Межа без мутації — обіцянка в реєстрі: зелений свідок нічого не доводить."""
        records = tuple(dataclasses.replace(entry, edits=()) for entry in n.BOUNDARIES)

        report = self.coverage(boundaries=records)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual(len(n.BOUNDARIES), len(report.findings), report.findings)
        self.assertIn("межа без мутації", report.findings[0].detail)

    def test_a_claim_that_is_no_longer_in_the_code_is_an_unknown(self):
        """Межа, чий текст викинули, — невідомість, а не «межі вже немає».

        Дві правди разом: пункт, який тепер не належить жодному запису, — це
        межа в коді без свідка, і якір запису, якого в тексті немає, — це
        невідомість. Прогонів при цьому не роблять: перевіряти нічого.
        """
        self.edit("scripts/declared_fields.py",
                  "пін ловить ПОЯВУ поля, а не його правду",
                  "пін тримає появу поля, а не його правду")

        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = n.main(["--root", str(self.root)])

        self.assertEqual(2, status)
        self.assertIn("#1276-appearance", errors.getvalue())
        self.assertIn("трапляється", errors.getvalue())

    def test_a_closing_mutation_that_edits_the_witness_module_is_refused(self):
        """«Зламай свідка, щоб довести, що межу видно» — не свідчення.

        Закриття мусить правити МЕХАНІЗМ, який межу не ловить. Правило машинне,
        бо інакше мутація в хелпері модуля свідка червонила б його, не закривши
        нічого, — а `guard_witnesses` таку правку в САМОМУ тесті лише звужує.
        """
        records = tuple(
            dataclasses.replace(
                entry,
                edits=(n.witnesses.Edit(
                    file="scripts/tests/test_named_boundaries.py",
                    find='TARGET = "test_witness.WitnessTest'
                         '.test_the_subject_still_answers_42"\n',
                    replace="TARGET = \"test_witness.WitnessTest.test_always_green\"\n",
                ),),
            ) if entry.id == "#1276-prose" else entry
            for entry in n.BOUNDARIES
        )

        report = self.coverage(boundaries=records)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual(1, len(report.findings), report.findings)
        self.assertEqual("#1276-prose", report.findings[0].where)
        self.assertIn("МОДУЛЬ СВІДКА", report.findings[0].detail)

    def test_a_record_for_a_boundary_that_no_section_carries_is_a_finding(self):
        """Запис на межу, названу поза покритим розділом, тримав би обіцянку."""
        report = self.coverage(sections=())

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual(len(n.BOUNDARIES), len(report.findings), report.findings)
        self.assertIn("межі в коді немає", report.findings[0].detail)
        self.assertEqual(0, report.covered_bullets)

    def test_a_section_that_disappeared_is_an_unknown(self):
        """Зниклий заголовок — невідомість: «пунктів немає» і «розділ зник» різні."""
        self.edit("scripts/declared_fields.py",
                  "Межа названа тут, у коді, а не лише в звіті:",
                  "Межа названа тут: ")

        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = n.main(["--root", str(self.root)])

        self.assertEqual(2, status)
        self.assertIn("заголовок", errors.getvalue())


# --- прогони на підставленому дереві -----------------------------------------

SUBJECT = "ANSWER = 42\nOTHER = 1\n"

WITNESS_MODULE = '''"""Підставлений свідок межі: тримає СТРУКТУРУ субʼєкта, а не підрядок."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SUBJECT = ROOT / "scripts" / "subject.py"


class WitnessTest(unittest.TestCase):
    def test_the_subject_still_answers_42(self):
        self.assertIn("ANSWER = 42", SUBJECT.read_text(encoding="utf-8"))

    def test_the_subject_still_names_other(self):
        self.assertIn("OTHER = 1", SUBJECT.read_text(encoding="utf-8"))
'''

PROSE = '''"""Підставлена шапка з покритим розділом меж.

Межі, названі прямо:

  * межа: перелік не бачить нового запису;
"""

VALUE = 1
'''

# Та сама мутація, що в підставленому дереві: закриває межу (свідок червоніє) і
# не закриває нічого (свідок зелений — і тоді межа не доведена).
CLOSES = n.witnesses.Edit(file="scripts/subject.py", find="ANSWER = 42\n",
                          replace="ANSWER = 0\n")
HARMLESS = n.witnesses.Edit(file="scripts/subject.py", find="OTHER = 1\n",
                            replace="OTHER = 1  # mutated\n")

CLAIM = "межа: перелік не бачить нового запису"
TARGET = "test_witness.WitnessTest.test_the_subject_still_answers_42"


class RunTest(unittest.TestCase):
    """Підставлене дерево, на якому механізм перевіряють, а не вдають."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="named-boundary-run-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "root"
        scripts = self.root / "scripts"
        (scripts / "tests").mkdir(parents=True)
        (scripts / "subject.py").write_text(SUBJECT, encoding="utf-8")
        (scripts / "prose.py").write_text(PROSE, encoding="utf-8")
        (scripts / "tests" / "test_witness.py").write_text(WITNESS_MODULE, encoding="utf-8")
        shutil.copy(REPO_ROOT / n.witnesses.RUNNER, scripts / n.witnesses.RUNNER.name)
        self.sections = (n.Section(where="scripts/prose.py", heading="Межі, названі прямо:"),)

    def check(self, edits) -> n.Report:
        entry = boundary("T", CLAIM, "scripts/prose.py", TARGET, edits)
        return n.check(self.root, 1, (entry,), self.sections)

    def test_a_live_boundary_passes_and_a_witness_green_under_closure_is_refused(self):
        """Жива межа проходить; свідок, який не розрізняє закриття, — відмова.

        Це та сама вимога, що варіант (в) тікета: межа мусить мати мутацію, яка
        її підтверджує. Друга половина — відмова: зелений під закриттям свідок
        означає, що межа не перевіряється нічим.
        """
        live = self.check((CLOSES,))
        self.assertEqual([], live.blocked, live.blocked)
        self.assertEqual([], [finding.detail for finding in live.findings])
        self.assertEqual(["T"], [shown.split(" — ")[0] for shown in live.shown])

        vacuous = self.check((HARMLESS,))
        self.assertEqual([], vacuous.blocked, vacuous.blocked)
        self.assertEqual(1, len(vacuous.findings), vacuous.findings)
        self.assertIn("T", vacuous.findings[0].detail)
        self.assertIn("не тримається прогоном", vacuous.findings[0].detail)
        # І благополуччя про ВІДКИНУТУ межу не друкують: OK-рядок — лише для
        # вбитих вердиктів (`guard_witnesses.Report.killed`). Без цього піна
        # механізм брехав про власну відмову на stdout (знахідка огляду #1296).
        self.assertEqual([], vacuous.shown, vacuous.shown)

    def test_a_mutation_that_no_longer_applies_is_an_unknown(self):
        """Закриття, якого більше нема де застосувати, — невідомість (код 2)."""
        stale = n.witnesses.Edit(file="scripts/subject.py", find="ANSWER = 7\n",
                                 replace="ANSWER = 0\n")

        report = self.check((stale,))

        self.assertEqual([], [finding.detail for finding in report.findings])
        self.assertTrue(report.blocked, "застаріле закриття мусить спинити перевірку")
        self.assertIn(TARGET, report.blocked[0])

    def test_an_unknown_boundary_name_is_refused_without_running_anything(self):
        """Невідоме ім'я — код 2 із переліком записаних: `--boundary` не вгадує.

        Це та сама відмова, що в `guard_witnesses`: звуження не сміє мовчки
        відповідати на межу, якої реєстр не знає.
        """
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            status = n.main(["--boundary", "немає"])

        self.assertEqual(2, status)
        self.assertIn("немає в реєстрі", errors.getvalue())
        self.assertIn("#1276-list", errors.getvalue())

    def test_the_list_names_every_registered_boundary_and_its_section(self):
        """`--list` — це те, що читає оглядач: межі, розділи й закриття."""
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = n.main(["--list"])

        self.assertEqual(0, status)
        for identifier in EXPECTED_BOUNDARY_IDS:
            self.assertIn(identifier, output.getvalue())
        self.assertIn(EXPECTED_SECTIONS[0][1], output.getvalue())
        self.assertIn("закриває:", output.getvalue())


class RegistryTest(unittest.TestCase):
    """Реєстр і покриття — рівно назване, і жодна межа не тримається словом."""

    def test_the_registry_is_the_named_set(self):
        """Реєстр і розділи — рівно те, що записано буквально: звуження видно."""
        self.assertEqual(EXPECTED_BOUNDARY_IDS, tuple(entry.id for entry in n.BOUNDARIES))
        self.assertEqual(EXPECTED_SECTIONS,
                         tuple((section.where, section.heading) for section in n.SECTIONS))
        self.assertEqual(len(EXPECTED_BOUNDARY_IDS), len(set(EXPECTED_BOUNDARY_IDS)))

    def test_every_named_boundary_is_carried_by_a_run(self):
        """Справжнє дерево, той самий виклик, що CI: кожна межа — два прогони.

        Кожна межа мусить бути названа в коді (якір рівно один), мати свідка,
        який БЕЗ мутації зелений, а ПІД закриттям червоний. Це найдорожча
        перевірка набору, і саме тому вона тут одна: вкладені прогони в копіях
        дерева — ціна, названа в шапці механізму.
        """
        report = n.check(REPO_ROOT)

        self.assertEqual([], report.blocked, report.blocked)
        self.assertEqual([], [finding.detail for finding in report.findings])
        self.assertEqual(len(n.BOUNDARIES), len(report.shown), report.shown)
        self.assertEqual(6, report.covered_bullets)

    def test_narrowing_runs_one_boundary_without_narrowing_the_coverage(self):
        """`--boundary` звужує ПРОГОНИ, а не покриття: решта пунктів — не «без свідка».

        Знайдено живим прикладом: якщо покриття звузити разом із прогонами, решта
        меж реєстру виглядає як «названа в коді без свідка», і вердикт стає
        знахідкою на рівному місці. Тому `check` бере покриття ПОВНИМ реєстром, а
        `run` — названим.
        """
        errors = io.StringIO()
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            status = n.main(["--root", str(REPO_ROOT), "--boundary", "#1276-prose"])

        self.assertEqual(0, status, errors.getvalue())
        self.assertEqual("", errors.getvalue())
        self.assertIn("#1276-prose", output.getvalue())
        self.assertEqual(1, output.getvalue().count("#1276-"),
                         "звужений прогін мусить показати РІВНО одну межу:\n"
                         + output.getvalue())

    def test_the_unreadable_boundary_sections_are_the_named_ones(self):
        """«Чотири з одинадцяти» зі шапки — число, яке пінить ЦЕЙ тест, а не проза.

        Механізм читає лише пункти форми `  * `; розділ іншої форми він
        відмовляється читати (код 2), і такий розділ у `SECTIONS` стояти не
        може. Скільки їх у дереві — не дрібниця: це ціна зарахування нового
        розділу. Тому тут — пін на множину й на кількість: новий заголовок у
        дереві або переписаний розділ червонять тест, і число в шапці правлять
        разом із ним, а не тихо.
        """
        headings = []
        for path in sorted((REPO_ROOT / "scripts").rglob("*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if not isinstance(node, (ast.Module, ast.ClassDef,
                                         ast.FunctionDef, ast.AsyncFunctionDef)):
                    continue
                for line in (ast.get_docstring(node, clean=False) or "").splitlines():
                    stripped = line.strip()
                    if re.match(r"^(Меж|МЕЖ|ЧЕСНА МЕЖ)", stripped) and stripped.endswith(":"):
                        headings.append((str(path.relative_to(REPO_ROOT)), stripped))

        unreadable = []
        for where, heading in headings:
            try:
                n.section_bullets(REPO_ROOT, n.Section(where, heading))
            except n.BoundaryError:
                unreadable.append((where, heading))

        self.assertEqual(EXPECTED_UNREADABLE_SECTIONS, tuple(unreadable))
        self.assertEqual(EXPECTED_BOUNDARY_HEADINGS, len(headings))

    def test_the_boundaries_are_the_records_the_machinery_declares(self):
        """Записи межі-оголошення пінить `declared_fields` — і це видно тут.

        `Boundary` і `Section` — записи-оголошення машинерії перевірок, тож вони
        мусять бути в `RECORDS` того механізму: нове поле в них червонить
        `test_declared_fields`, а не проходить тихо.
        """
        self.assertIn(("scripts/named_boundaries.py", "Section", ("where", "heading")),
                      d.RECORDS)
        self.assertIn(("scripts/named_boundaries.py", "Boundary",
                       ("id", "claim", "where", "witness", "edits")), d.RECORDS)


if __name__ == "__main__":
    unittest.main()
