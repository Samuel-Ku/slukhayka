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
    перевіряється фікстура, а не те, що конкретний тест випадково зелений;
  * **форма**: якщо фікстура вдає інструмент, який у репозиторії Є, пін на форму
    мусить існувати й бути названим тут. Якщо інструмент зовнішній (`gh`, `adb`,
    Gradle), пінити нема проти чого: у джобі `script-tests` немає ні мережі, ні
    авторизації — і це названа межа, а не мовчазний пропуск.

Перелік не тримається на чесному слові автора: покриття перевіряється
СТРУКТУРНО, з двох боків.

  * `fixtures/` сканується по файлах: жоден файл-інструмент там не лишається без
    запису (і жоден запис не вказує на неіснуючий файл);
  * тести скануються по AST: кожен ЛІТЕРАЛ, що починається шебангом, — це
    вбудована програма, і кожна мусить бути названа маркером у реєстрі. Саме
    цього не бачив скан `fixtures/`: підставні `FAKE_OLD_GIT`, `FAKE_BROKEN_GIT`,
    `FAKE_GH`, `adb` і `gradlew` живуть у тілі тестів, а не у файлах.

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
# позначка: інакше сканер ловив би власний рядок `"#!"` у цьому модулі.
SHEBANG = re.compile(r"^#!\S*/")


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
    markers: tuple[str, ...] = ()  # рядки, якими названо вбудовану програму
    own_poison: bool = False    # фікстура отруює оточення своїм тестом
    runs: bool = True           # False — це текст-дані, а не підставний інструмент


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
        boundary=("Gradle — зовнішній для джоби `script-tests` інструмент, а "
                  "`./gradlew` без дистрибутива не запускається (і заборонений "
                  "у цій роботі). Форму виклику пінять тести: журнал інвокацій "
                  "мусить мати `:app:testPureJvm` і "
                  "`-Ptest.selectedClasses=<FQCN>`."),
        markers=("MUTATE_TEST_GRADLE_LOG", "openjdk version"),
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
        boundary=("`gh` — зовнішній CLI: у джобі `script-tests` немає ні мережі, ні "
                  "авторизації (`GH_TOKEN`), тож живий пін проти нього "
                  "неможливий, навіть якщо бінарник є в образі раннера. "
                  "Підставний падає кодом 3 на незнайомій формі, тож дрейф форми "
                  "виклику не мовчить."),
        markers=("FAKE_GH_DIR",),
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
        markers=("unknown option",),
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
        markers=("unable to read tree",),
    ),
    StandIn(
        name="підставний gh фікстури test_review_pass",
        where=("scripts/tests/test_review_pass.py",),
        witness=("test_review_pass.ReviewPassTest"
                 ".test_reissues_the_verdict_on_a_pr_that_already_has_the_label"),
        prefix="FAKE_GH_",
        boundary=("той самий зовнішній `gh`, що й у test_merge_pr: без мережі й "
                  "авторизації живого піна немає. Підставний падає кодом 3 на "
                  "незнайомій формі."),
        markers=("FAKE_GH_DIR",),
    ),
    StandIn(
        name="підставний adb фікстури test_wide_window",
        where=("scripts/tests/test_wide_window.py",),
        witness=("test_wide_window.DeviceRunTest"
                 ".test_derives_the_density_from_the_device_and_reports_the_width"),
        prefix="FAKE_ADB_",
        boundary=("`adb` — зовнішній CLI, у джобі його немає (пристрій не "
                  "потрібен). Підставний падає кодом 2 на незнайоме слово, тож "
                  "форма виклику не може розійтися мовчки."),
        markers=("FAKE_ADB_LOG",),
    ),
    StandIn(
        name="підставний adb фікстури test_android_emulator_readiness",
        where=("scripts/tests/test_android_emulator_readiness.py",),
        witness=("test_android_emulator_readiness.AndroidEmulatorReadinessTest"
                 ".test_waits_until_android_package_service_is_usable"),
        prefix="FAKE_ADB_",
        boundary=("той самий зовнішній `adb`; підставний падає на незнайоме "
                  "слово, тож форма виклику не може розійтися мовчки."),
        markers=("FAKE_ADB_STATE",),
    ),
    StandIn(
        name="підставні gradlew і java фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        witness=("test_test_wrappers.FullSuiteWrapperTest"
                 ".test_uses_jdk_21_and_a_fresh_temp_then_cleans_it"),
        prefix="WRAPPER_TEST_",
        real="./gradlew, JDK 21",
        boundary=("Gradle і JDK — зовнішні для джоби; журнал інвокацій пінить "
                  "форму (шість партицій і `-Ptest.selectedClasses=`), а "
                  "`SLUKHAYKA_JAVA_HOME` — те, що читає справжній wrapper."),
        markers=("openjdk version", "WRAPPER_TEST_LOG", "kill -TERM"),
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
        markers=("cwd=%s", "gradle %s"),
    ),
    StandIn(
        name="текст-дані SAMPLE у test_empty_shape_mutants",
        where=("scripts/tests/test_empty_shape_mutants.py",),
        witness=("test_empty_shape_mutants.ScannerTest"
                 ".test_only_an_empty_pattern_beside_a_named_one_is_a_shape"),
        boundary=("це не підставний інструмент, а вхідний текст для "
                  "`empty_shape_mutants.shapes()`: жоден тест його не запускає. "
                  "Названий тут, бо він виглядає як програма (шебанг), і мовчазне "
                  "виключення було б дірою в покритті."),
        markers=("detached HEAD",),
        runs=False,
    ),
)


def embedded_programs(module: Path) -> list[str]:
    """Тексти вбудованих програм модуля: літерали, що ПОЧИНАЮТЬСЯ шебангом.

    AST, а не пошук рядка: склеєні літерали (`"a" "b" f"c"`) видно цілком, а
    шебанг у докстрінгу чи коментарі сюди не потрапляє. Частини склеєного
    літерала відкидаються — лишається найбільший відрізок.
    """
    source = module.read_text(encoding="utf-8")
    segments = set()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Constant) and not isinstance(node.value, str):
            continue
        if not isinstance(node, (ast.Constant, ast.JoinedStr)):
            continue
        segment = ast.get_source_segment(source, node)
        if segment and SHEBANG.match(segment.lstrip("rbfu\"'")):
            segments.add(segment)

    found: list[str] = []
    for segment in sorted(segments, key=len, reverse=True):
        if not any(segment in bigger for bigger in found):
            found.append(segment)
    return found


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

    def test_every_embedded_program_is_claimed(self):
        """Кожна вбудована програма названа в реєстрі — і навпаки.

        Правило структурне, а не «сьогодні цих двох випадково пінить сусід»:
        програму знаходить AST, тож наступний підставний інструмент, доданий у
        будь-який тест, з'явиться тут сам — і без маркера в реєстрі тест
        почервоніє. Саме цієї половини не бачив скан `fixtures/`: підставні
        `FAKE_OLD_GIT`, `FAKE_BROKEN_GIT`, `FAKE_GH`, `adb` і `gradlew` живуть у
        тілі тестів.
        """
        embedded = {
            str(module.relative_to(REPO_ROOT)): embedded_programs(module)
            for module in sorted(TESTS_DIR.glob("test_*.py"))
        }
        embedded = {path: programs for path, programs in embedded.items() if programs}

        self.assertTrue(embedded, "вбудованих програм не знайдено — сканер зламався")
        for path, programs in embedded.items():
            with self.subTest(module=path):
                markers = [marker for case in CASES if case.where == (path,)
                           for marker in case.markers]

                self.assertTrue(markers, "програми є, а реєстр їх не називає")
                for marker in markers:
                    self.assertTrue(
                        any(marker in program for program in programs),
                        "маркер %r не належить жодній програмі модуля" % marker,
                    )
                for program in programs:
                    self.assertTrue(
                        any(marker in program for marker in markers),
                        "програма не названа в реєстрі:\n%s" % program[:200],
                    )

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
        targets = [case.witness for case in CASES if case.runs and not case.own_poison]

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
