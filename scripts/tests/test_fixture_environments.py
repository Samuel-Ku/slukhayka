"""Фікстура, яка вдає інструмент, не успадковує оточення раннера (#1265).

Два падіння CI за минулу ціль мали один корінь: підставний інструмент усередині
фікстури жив в оточенні, яке дісталося йому у спадок.

  1. Раннер приносить експортовану bash-функцію (`BASH_FUNC_<ім'я>%%`), і вона
     затіняє команду ВСЕРЕДИНІ інструмента — `dirname`, `cat`, `printf` — тож
     інструмент виходить із кодом 2 при незмінному файлі. Саме так упав CI
     #1260: `repo_root` підставного `mutate.sh` ставав `/`.
  2. Фікстура вдавала CLI не тієї форми, що справжній інструмент (#1260,
     `mutation_trace.py`: позиційний аргумент ПЕРЕД опцією замість підкоманди) —
     і це бачив лише CPython 3.12.3, тобто лише джоба.

Обидва рази локальна зелень цього не бачила. Тому тут — реєстр фікстур
репозиторію, які запускають підставний інструмент, і дві перевірки на кожну:

  * **оточення**: свідок мусить лишитися зеленим і з порожнім оточенням
    (фікстура не читає зі спадку нічого), і з отруєним (затьмарені команди не
    доходять до інструмента). Обидва прогони — вкладені процеси `unittest`, тож
    перевіряється фікстура, а не те, що конкретний тест випадково зелений.
    Виключити фікстуру з цих прогонів НІЧИМ не можна: поля-вимикача в реєстрі
    немає навмисно, бо «позначив текстом-даними і пройшов» — це та сама діра;
  * **форма**: якщо фікстура вдає інструмент, який у репозиторії Є, пін на форму
    мусить існувати й бути названим тут. Якщо інструмент зовнішній (`gh`, `adb`,
    Gradle), то живий пін вимагав би того, чого джоба `script-tests` не має:
    `GH_TOKEN`, пристрою, прогону збірки (у джобі — лише checkout і
    `python3 -m unittest`, `ci.yml:643-655`). Це названа межа, не мовчазний
    пропуск.

Перелік не тримається на чесному слові автора: покриття перевіряється
СТРУКТУРНО, з двох боків.

  * `fixtures/` сканується по файлах: жоден файл-інструмент там не лишається без
    запису (і жоден запис не вказує на неіснуючий файл);
  * `scripts/tests/**/*.py` сканується по AST: кожен ЛІТЕРАЛ, значення якого
    ПОЧИНАЄТЬСЯ шебангом, — це вбудована програма, і кожна мусить бути названа
    ЗА СВОЇМ МІСЦЕМ (`Program.at`), а не «хоч якимсь маркером модуля». Прив'язка
    до місця, а не до тексту, навмисна: найімовірніший спосіб написати третього
    підставного `git` — скопіювати сусіднього, і копія з тим самим текстом
    мусить бути ВИДНА як окрема програма. Маркер лишається як перевірка, що
    запис не збрехав про вміст.

Межі сканера названі прямо:

  * скан читає ЗНАЧЕННЯ літерала. Програма, зібрана так, що значення не
    починається шебангом (`textwrap.dedent('''\n#!/…''')`, продовження рядка
    `'''\⏎#!/…'''`), для нього невидима — і це єдина відома сліпа пляма форми;
  * докстрінг, що ПОЧИНАЄТЬСЯ шебангом, навпаки потрапляє в скан: це хибне
    спрацювання в безпечний бік (зайвий запис, а не пропущений);
  * скан обмежений `scripts/tests/**/*.py`. Програма поза цим деревом (у
    `app/**`, `web/**`, у хелпері поза тестами) не покрита жодним із двох сканів
    — це межа предмета (фікстури тестів), а не обіцянка «бачимо все».

Дві фікстури (`verdict_field`, `empty_shape`) отруюють оточення самі, своїм
`EnvironmentTest`; тут вони в реєстрі, щоб перелік був повним, а їхній пін
перевіряється на існування, а не ганяється вдруге.
"""

import ast
import dataclasses
import importlib
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TESTS_DIR = REPO_ROOT / "scripts" / "tests"
FIXTURES = TESTS_DIR / "fixtures"

# Скільки чекати на вкладений прогін. Таймаут — це провал, не «зелено».
NESTED_TIMEOUT_SECONDS = 600

# Отрута — рівно те, що приносить раннер: затьмарені команди, чужий `BASH_ENV`,
# `CDPATH` і `PYTHONPATH`. `BASH_FUNC_*` діє на bash-процеси, і саме тому ламає
# підставний інструмент, а не тест.
HOSTILE = {
    "BASH_FUNC_printf%%": "() { return 1; }",
    "BASH_FUNC_cat%%": "() { return 1; }",
    "BASH_FUNC_dirname%%": "() { return 1; }",
    "BASH_FUNC_pwd%%": "() { return 1; }",
    "BASH_FUNC_python3%%": "() { return 2; }",
    "BASH_ENV": "/nonexistent/rc",
    "CDPATH": "/tmp",
    "PYTHONPATH": "/nonexistent",
}

# Оточення без спадку взагалі: лише те, без чого не стартує жоден процес.
STRIPPED = {"PATH": "/usr/bin:/bin"}

# Шебанг — це шлях до інтерпретатора (`#!/usr/bin/env bash`), а не сама лише
# позначка: інакше скан ловив би власний рядок `"#!"` у цьому модулі.
SHEBANG = re.compile(r"^#!\S*/")


@dataclasses.dataclass(frozen=True)
class Program:
    """Вбудована програма: де вона лежить і який рядок її називає.

    `at` — місце, а не текст: `GRADLEW`, `Fixture.build: java`,
    `DeviceRunTest.setUp: self.adb`. Дві копії того самого тексту — дві різні
    програми, і кожна мусить мати свій `at`.
    """

    at: str
    marker: str


@dataclasses.dataclass(frozen=True)
class StandIn:
    """Один підставний інструмент: чим він є, чим піниться і де межа."""

    name: str
    where: tuple[str, ...]      # файли (відносно кореня репозиторію)
    witness: str                # module.Class.test, який його проводжує
    prefix: str = "(жодної)"    # власний префікс змінних, які він читає
    real: str | None = None     # справжній інструмент у репозиторії, якщо є
    shape_pin: str | None = None  # module.Class.test — пін на форму
    boundary: str = ""          # чому піна немає — названа межа
    programs: tuple[Program, ...] = ()  # вбудовані програми цього запису
    own_poison: bool = False    # фікстура отруює оточення своїм тестом


CASES = (
    StandIn(
        name="підставний recorder фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutation_trace.py",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutation_trace.py",
        ),
        witness=("test_verdict_field_mutants.EnvironmentTest"
                 ".test_a_poisoned_inherited_environment_does_not_reach_the_tool"),
        real="scripts/mutation_trace.py",
        shape_pin=("test_verdict_field_mutants.HistoricalStateTest"
                   ".test_the_fixture_cli_is_shaped_like_the_real_one"),
        own_poison=True,
    ),
    StandIn(
        name="підставний mutate.sh фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutate.sh",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutate.sh",
        ),
        witness=("test_verdict_field_mutants.EnvironmentTest"
                 ".test_a_poisoned_inherited_environment_does_not_reach_the_tool"),
        prefix="VERDICT_FIXTURE_",
        real="scripts/mutate.sh",
        boundary=("фікстура вдає ЛАНКУ інструмента (артефакт → лічильники → "
                  "вердикт → запис), а не його CLI: у неї нуль argv, у "
                  "справжнього `scripts/mutate.sh` на нуль argv — usage. Форму "
                  "саме цієї ланки пінить запис (schema/kind/outcome), а не argv."),
        own_poison=True,
    ),
    StandIn(
        name="підставний gate.sh фікстури empty_shape",
        where=(
            "scripts/tests/fixtures/empty_shape/pinned/scripts/gate.sh",
            "scripts/tests/fixtures/empty_shape/unpinned/scripts/gate.sh",
        ),
        witness=("test_empty_shape_mutants.EnvironmentTest"
                 ".test_a_poisoned_inherited_environment_does_not_reach_the_gate"),
        real="scripts/merge-pr.sh",
        shape_pin=("test_empty_shape_mutants.HistoricalStateTest"
                   ".test_the_fixture_gate_is_shaped_like_the_real_one"),
        own_poison=True,
    ),
    StandIn(
        name="підставний gradlew і jdk фікстури test_mutate",
        where=("scripts/tests/test_mutate.py",),
        witness=("test_mutate.MutationRunTest"
                 ".test_runs_the_named_class_and_records_a_caught_mutation"),
        prefix="MUTATE_TEST_",
        real="./gradlew, scripts/mutate.sh",
        boundary=("живий пін проти Gradle — це прогін збірки; джоба `script-tests` "
                  "робить лише `python3 -m unittest` (`ci.yml:643-655`), і ця "
                  "робота забороняє Gradle. Форму виклику пінять тести: журнал "
                  "інвокацій мусить мати `:app:testPureJvm` і "
                  "`-Ptest.selectedClasses=<FQCN>`."),
        programs=(
            Program("GRADLEW", "MUTATE_TEST_GRADLE_LOG"),
            Program("Fixture.build: java", "openjdk version"),
        ),
    ),
    StandIn(
        name="підставний тест-субʼєкт фікстури test_mutate_python",
        where=("scripts/tests/test_mutate_python.py",),
        witness=("test_mutate_python.PythonMutationRunTest"
                 ".test_runs_one_python_class_and_records_a_caught_mutation"),
        prefix="MUTATE_PYTHON_FIXTURE_",
        real="scripts/mutation_python_runner.py",
        boundary=("підставний тут — unittest-модуль, а не CLI: він не програма з "
                  "шебангом, і його форма — це `python3 -m unittest "
                  "<модуль>.<клас>`, яку пінить поле `task` запису в "
                  "тесті-свідку. Окремий argv-пін не має проти чого стояти."),
    ),
    StandIn(
        name="підставний gh фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        witness=("test_merge_pr.MergePrTest"
                 ".test_merges_with_squash_and_never_deletes_the_branch"),
        prefix="FAKE_GH_",
        boundary=("живий пін проти `gh` вимагає авторизації (`GH_TOKEN`), якої "
                  "джоба `script-tests` не передає (`ci.yml:643-655`). "
                  "Підставний падає кодом 3 на незнайомій формі, тож дрейф форми "
                  "виклику не мовчить."),
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний старий git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        witness=("test_merge_pr.MergePrTest"
                 ".test_fails_closed_when_the_working_copy_scan_fails"),
        real="git",
        shape_pin=("test_merge_pr.GitStandInShapeTest"
                   ".test_a_stub_refuses_an_option_the_real_git_has"),
        boundary=("argv у нього — не свій, а справжнього git: обгортка прозора "
                  "(`exec git \"$@\"`), відмова — рівно на `--path-format`, який "
                  "справжній git знає. Прозорість і справжність опціону пінить "
                  "`GitStandInShapeTest` проти встановленого git."),
        programs=(Program("FAKE_OLD_GIT", "unknown option"),),
    ),
    StandIn(
        name="підставний зламаний git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        witness=("test_merge_pr.MergePrTest"
                 ".test_fails_closed_when_the_patch_comparison_fails"),
        real="git",
        shape_pin=("test_merge_pr.GitStandInShapeTest"
                   ".test_the_patch_comparison_stub_refuses_a_real_git_revision_option"),
        boundary=("та сама прозора обгортка; ламає рівно `--cherry-mark`, який "
                  "справжній git знає."),
        programs=(Program("FAKE_BROKEN_GIT", "unable to read tree"),),
    ),
    StandIn(
        name="підставний gh фікстури test_review_pass",
        where=("scripts/tests/test_review_pass.py",),
        witness=("test_review_pass.ReviewPassTest"
                 ".test_reissues_the_verdict_on_a_pr_that_already_has_the_label"),
        prefix="FAKE_GH_",
        boundary=("той самий зовнішній `gh`, що й у test_merge_pr: без `GH_TOKEN` "
                  "живого піна немає. Підставний падає кодом 3 на незнайомій "
                  "формі."),
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний adb фікстури test_wide_window",
        where=("scripts/tests/test_wide_window.py",),
        witness=("test_wide_window.DeviceRunTest"
                 ".test_derives_the_density_from_the_device_and_reports_the_width"),
        prefix="FAKE_ADB_",
        boundary=("живий пін проти `adb` вимагає пристрою або емулятора; джоба "
                  "`script-tests` його не піднімає (`ci.yml:643-655`). "
                  "Підставний падає кодом 2 на незнайоме слово, тож форма виклику "
                  "не може розійтися мовчки."),
        programs=(Program("DeviceRunTest.setUp: self.adb", "FAKE_ADB_LOG"),),
    ),
    StandIn(
        name="підставний adb фікстури test_android_emulator_readiness",
        where=("scripts/tests/test_android_emulator_readiness.py",),
        witness=("test_android_emulator_readiness.AndroidEmulatorReadinessTest"
                 ".test_waits_until_android_package_service_is_usable"),
        prefix="FAKE_ADB_",
        boundary=("той самий зовнішній `adb`; підставний падає на незнайоме "
                  "слово, тож форма виклику не може розійтися мовчки."),
        programs=(Program("AndroidEmulatorReadinessTest.setUp: self.fake_adb",
                          "FAKE_ADB_STATE"),),
    ),
    StandIn(
        name="підставні gradlew і java фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        witness=("test_test_wrappers.FullSuiteWrapperTest"
                 ".test_uses_jdk_21_and_a_fresh_temp_then_cleans_it"),
        prefix="WRAPPER_TEST_",
        real="./gradlew, JDK 21",
        boundary=("живий пін проти Gradle і JDK — це прогін збірки; джоба "
                  "`script-tests` його не робить (`ci.yml:643-655`). Журнал "
                  "інвокацій пінить форму (шість партицій і "
                  "`-Ptest.selectedClasses=`), а `SLUKHAYKA_JAVA_HOME` — те, що "
                  "читає справжній wrapper."),
        programs=(
            Program("FullSuiteWrapperTest.setUp: self.java", "21.0.8"),
            Program("FullSuiteWrapperTest.write_gradle: self.fake_gradle",
                    "WRAPPER_TEST_LOG"),
            Program("FullSuiteWrapperTest"
                    ".test_rejects_a_non_21_override_with_an_actionable_error: self.java",
                    "17.0.12"),
            Program("FullSuiteWrapperTest"
                    ".test_cleans_temp_when_interrupted: self.fake_gradle",
                    "kill -TERM"),
        ),
    ),
    StandIn(
        name="підставні gradlew, npm і npx фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        witness=("test_test_wrappers.ChangedTestWrapperWebCheckTest"
                 ".test_web_path_runs_the_web_check_and_no_android_partition"),
        prefix="WRAPPER_TEST_",
        real="./gradlew, npm, npx",
        boundary=("Gradle і npm — зовнішні; журнал пінить точні три команди "
                  "веб-перевірки (`npm ci`, `tsc --noEmit`, `vitest run`)."),
        programs=(
            Program("ChangedTestWrapperWebCheckTest.setUp: self.java", "21.0.8"),
            Program("ChangedTestWrapperWebCheckTest.write_web_command: target",
                    "cwd=%s"),
            Program("ChangedTestWrapperWebCheckTest.write_gradle: self.gradle",
                    "gradle %s"),
        ),
    ),
    StandIn(
        name="SAMPLE у test_empty_shape_mutants — вхідний текст, не інструмент",
        where=("scripts/tests/test_empty_shape_mutants.py",),
        witness=("test_empty_shape_mutants.ScannerTest"
                 ".test_only_an_empty_pattern_beside_a_named_one_is_a_shape"),
        boundary=("це не підставний інструмент, а вхідний текст для "
                  "`empty_shape_mutants.shapes()`: жоден тест його не запускає. "
                  "Названий тут, бо він виглядає як програма (шебанг), і мовчазне "
                  "виключення було б дірою в покритті: поля-вимикача в реєстрі "
                  "немає навмисно."),
        programs=(Program("SAMPLE", "detached HEAD"),),
    ),
)


def _program_text(node: ast.AST) -> str | None:
    """Текст програми, якщо літерал нею є: значення ПОЧИНАЄТЬСЯ шебангом."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value if SHEBANG.match(node.value) else None
    if isinstance(node, ast.JoinedStr) and node.values:
        first = node.values[0]
        if (isinstance(first, ast.Constant) and isinstance(first.value, str)
                and SHEBANG.match(first.value)):
            return "".join(
                part.value
                if isinstance(part, ast.Constant) and isinstance(part.value, str)
                else "\x00"
                for part in node.values
            )
    return None


def _location(node: ast.AST, parents: dict, source: str) -> str:
    """Місце програми: `Клас.метод: ціль` або ім'я константи.

    Саме місце, а не текст, робить копію сусіда окремою програмою: копія з тим
    самим текстом дістає свій `at` і без запису лишається неназваною.
    """
    scopes: list[str] = []
    target = ""
    current = node
    while current in parents:
        current = parents[current]
        if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            scopes.append(current.name)
        if not target and isinstance(current, ast.Assign):
            target = ast.get_source_segment(source, current.targets[0]) or ""
            break
        if not target and isinstance(current, ast.Call):
            function = current.func
            if isinstance(function, ast.Attribute):
                target = ast.get_source_segment(source, function.value) or ""
    prefix = ".".join(reversed(scopes))
    return f"{prefix}: {target}" if prefix else target


def embedded_programs(module: Path) -> dict[str, str]:
    """Вбудовані програми модуля: `at` → текст.

    AST, а не пошук рядка: склеєні літерали (`"a" "b" f"c"`) видно цілком, а
    частини склеєного літерала не рахуються двічі. Значення, а не текст
    вихідника: `"#!"` у коді програмою не стає.
    """
    source = module.read_text(encoding="utf-8")
    tree = ast.parse(source)
    parents = {child: parent for parent in ast.walk(tree)
               for child in ast.iter_child_nodes(parent)}
    found: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(parents.get(node), ast.JoinedStr):
            continue
        text = _program_text(node)
        if text is None:
            continue
        key = _location(node, parents, source)
        unique, index = key, 2
        while unique in found:
            unique, index = f"{key}#{index}", index + 1
        found[unique] = text
    return found


def all_embedded_programs() -> dict[str, dict[str, str]]:
    """Усі вбудовані програми під `scripts/tests`, за модулями."""
    return {
        str(module.relative_to(REPO_ROOT)): programs
        for module in sorted(TESTS_DIR.rglob("*.py"))
        if (programs := embedded_programs(module))
    }


class RegistryTest(unittest.TestCase):
    """Перелік мусить бути повним з обох боків — і кожен пін існувати."""

    def test_the_registry_names_every_tool_under_the_fixtures(self):
        """Жоден файл-інструмент у `fixtures/` не лишається поза реєстром.

        Підставні інструменти фікстур — це все, що лежить під `fixtures/` і не
        є свідком (`tests/`). Рівність у два боки: і зайвий файл у дереві без
        запису, і запис на файл, якого немає, — червоні.
        """
        declared = {
            Path(where)
            for case in CASES for where in case.where
            if where.startswith("scripts/tests/fixtures/")
        }
        found = {
            path.relative_to(REPO_ROOT)
            for path in FIXTURES.rglob("*")
            if path.is_file()
            and "tests" not in path.relative_to(FIXTURES).parts
        }

        self.assertTrue(found, "під fixtures/ мусять бути підставні інструменти")
        self.assertEqual(sorted(found), sorted(declared))

    def test_every_registered_path_exists(self):
        """Запис на неіснуючий файл — це не «менше роботи», а діра в покритті."""
        for case in CASES:
            for where in case.where:
                with self.subTest(stand_in=case.name, path=where):
                    self.assertTrue((REPO_ROOT / where).is_file(), where)

    def test_every_embedded_program_is_claimed_at_its_own_place(self):
        """Кожна вбудована програма названа ЗА СВОЇМ МІСЦЕМ — і навпаки.

        Прив'язка до місця (`Program.at`), а не до маркера модуля: копія сусіда з
        тим самим текстом (найімовірніший спосіб написати третього підставного
        `git`) дістає власний `at` і без запису червонить цей тест. Маркер
        перевіряється окремо — він мусить лежати в ТІЙ програмі, яку запис називає.
        """
        for path, programs in all_embedded_programs().items():
            with self.subTest(module=path):
                claimed: dict[str, str] = {}
                for case in CASES:
                    if case.where != (path,):
                        continue
                    for program in case.programs:
                        self.assertIn(program.at, programs,
                                      "запис вказує на програму, якої немає")
                        self.assertNotIn(program.at, claimed,
                                         "програму названо двічі")
                        self.assertIn(program.marker, programs[program.at],
                                      "маркер не лежить у своїй програмі")
                        claimed[program.at] = case.name

                self.assertEqual(
                    sorted(programs), sorted(claimed),
                    "є програми без запису в реєстрі: %s"
                    % (sorted(set(programs) - set(claimed)),),
                )

    def test_the_scan_finds_the_programs_it_is_supposed_to(self):
        """Скан не порожній і бачить відомі місця — інакше він «зелений» ні на чому."""
        found = all_embedded_programs()

        self.assertEqual(
            ["scripts/tests/test_android_emulator_readiness.py",
             "scripts/tests/test_empty_shape_mutants.py",
             "scripts/tests/test_merge_pr.py",
             "scripts/tests/test_mutate.py",
             "scripts/tests/test_review_pass.py",
             "scripts/tests/test_test_wrappers.py",
             "scripts/tests/test_wide_window.py"],
            sorted(found),
        )
        self.assertIn("FAKE_OLD_GIT", found["scripts/tests/test_merge_pr.py"])

    def test_every_registered_witness_exists(self):
        for case in CASES:
            with self.subTest(stand_in=case.name):
                self.assertTrue(callable(self.resolve(case.witness)), case.witness)

    def test_every_stand_in_has_a_shape_pin_or_a_named_boundary(self):
        """Форма або піниться, або межа названа — мовчазного пропуску немає."""
        for case in CASES:
            with self.subTest(stand_in=case.name):
                if case.shape_pin is None:
                    self.assertTrue(case.boundary, "немає ні піна, ні межі")
                else:
                    self.assertTrue(case.real, "пін на форму без справжнього інструмента")
                    self.assertTrue(callable(self.resolve(case.shape_pin)), case.shape_pin)

    def test_the_two_fixtures_poison_their_own_environment(self):
        """Фікстури з власним отруєнням мусять мати його, а не обіцянку."""
        owned = [case for case in CASES if case.own_poison]

        self.assertTrue(owned, "жодна фікстура не отруює оточення сама")
        for case in owned:
            with self.subTest(stand_in=case.name):
                name = case.witness.rpartition(".")[2]
                self.assertIn("poisoned_inherited_environment", name, case.witness)
                self.assertTrue(callable(self.resolve(case.witness)), case.witness)

    def resolve(self, target: str):
        """`module.Class.test` → сам обʼєкт тесту (імпорт із `scripts/tests`)."""
        module_name, _, path = target.partition(".")
        value = importlib.import_module(module_name)
        for part in path.split("."):
            value = getattr(value, part)
        return value


class EnvironmentTest(unittest.TestCase):
    """Кожен свідок мусить бути зелений і без спадку, і під отрутою."""

    def run_witnesses(self, environment: dict) -> subprocess.CompletedProcess:
        targets = [case.witness for case in CASES if not case.own_poison]

        return subprocess.run(
            [sys.executable, "-m", "unittest", *targets],
            cwd=str(TESTS_DIR), check=False, capture_output=True, text=True,
            env=environment, timeout=NESTED_TIMEOUT_SECONDS,
        )

    def test_a_stripped_environment_is_enough_for_every_stand_in(self):
        """Порожнє оточення: фікстура не читає зі спадку нічого.

        Якщо свідок зеленіє лише тоді, коли йому дісталися змінні раннера, —
        він читає спадок, і CI це побачить, а локальна зелень ні.
        """
        result = self.run_witnesses(dict(STRIPPED))

        self.assertEqual(0, result.returncode, result.stderr[-3000:])

    def test_a_poisoned_inherited_environment_does_not_reach_any_stand_in(self):
        """Отруєне оточення: затьмарені команди не доходять до інструмента.

        `BASH_FUNC_printf%%` і решта роблять підставний інструмент червоним, якщо
        той успадковує оточення. Саме так упав CI #1260 — шість падінь із кодом
        2 при незмінних файлах фікстури.
        """
        result = self.run_witnesses({**os.environ, **HOSTILE})

        self.assertEqual(0, result.returncode, result.stderr[-3000:])


if __name__ == "__main__":
    unittest.main()
