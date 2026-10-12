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

Обидва рази локальна зелень цього не бачила. Тому тут — реєстр усіх фікстур
репозиторію, які запускають підставний інструмент, і дві перевірки на кожну:

  * **оточення**: свідок мусить лишитися зеленим і з порожнім оточенням
    (фікстура не читає зі спадку нічого), і з отруєним (затьмарені команди не
    доходять до інструмента). Обидва прогони — вкладені процеси `unittest`, тож
    перевіряється фікстура, а не те, що конкретний тест випадково зелений;
  * **форма**: якщо фікстура вдає інструмент, який у репозиторії Є, пін на форму
    мусить існувати й бути названим тут. Якщо інструмент зовнішній (`gh`, `adb`,
    Gradle), пінити нема проти чого: у джобі `script-tests` його немає — і це
    названа межа, а не мовчазний пропуск.

Дві фікстури (`verdict_field`, `empty_shape`) отруюють оточення самі, своїм
`EnvironmentTest`; тут вони в реєстрі, щоб перелік був повним, а їхній пін
перевіряється на існування, а не ганяється вдруге.
"""

import dataclasses
import importlib
import os
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


@dataclasses.dataclass(frozen=True)
class StandIn:
    """Один підставний інструмент: чим він є, чим піниться і де межа."""

    name: str
    where: tuple[str, ...]      # файли фікстури (відносно кореня репозиторію)
    witness: str                # module.Class.test, який його проводжує
    prefix: str                 # власний префікс змінних, які він читає
    real: str | None = None     # справжній інструмент у репозиторії, якщо є
    shape_pin: str | None = None  # module.Class.test — пін на форму CLI
    boundary: str = ""          # чому піна немає — названа межа
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
        prefix="(жодної)",
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
        prefix="(жодної)",
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
    ),
    StandIn(
        name="підставний тест-субʼєкт фікстури test_mutate_python",
        where=("scripts/tests/test_mutate_python.py",),
        witness=("test_mutate_python.PythonMutationRunTest"
                 ".test_runs_one_python_class_and_records_a_caught_mutation"),
        prefix="MUTATE_PYTHON_FIXTURE_",
        real="scripts/mutation_python_runner.py",
        boundary=("підставний тут — unittest-модуль, а не CLI: його форма — це "
                  "`python3 -m unittest <модуль>.<клас>`, і саме її пінить поле "
                  "`task` запису в тесті-свідку. Окремий argv-пін не має проти "
                  "чого стояти."),
    ),
    StandIn(
        name="підставний gh фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        witness=("test_merge_pr.MergePrTest"
                 ".test_merges_with_squash_and_never_deletes_the_branch"),
        prefix="FAKE_GH_",
        real=None,
        boundary=("`gh` — зовнішній CLI; у джобі `script-tests` його немає "
                  "(і не мусить бути: тести не ходять у мережу). Пін проти "
                  "живого `gh` тут неможливий; підставний падає кодом 3 на "
                  "незнайому форму, тож дрейф форми виклику не мовчить."),
    ),
    StandIn(
        name="підставний gh фікстури test_review_pass",
        where=("scripts/tests/test_review_pass.py",),
        witness=("test_review_pass.ReviewPassTest"
                 ".test_reissues_the_verdict_on_a_pr_that_already_has_the_label"),
        prefix="FAKE_GH_",
        real=None,
        boundary=("той самий зовнішній `gh`, що й у test_merge_pr; підставний "
                  "падає кодом 3 на незнайому форму."),
    ),
    StandIn(
        name="підставний adb фікстури test_wide_window",
        where=("scripts/tests/test_wide_window.py",),
        witness=("test_wide_window.DeviceRunTest"
                 ".test_derives_the_density_from_the_device_and_reports_the_width"),
        prefix="FAKE_ADB_",
        real=None,
        boundary=("`adb` — зовнішній CLI, у джобі його немає (пристрій не "
                  "потрібен). Підставний падає кодом 2 на незнайоме слово, тож "
                  "форма виклику не може розійтися мовчки."),
    ),
    StandIn(
        name="підставний adb фікстури test_android_emulator_readiness",
        where=("scripts/tests/test_android_emulator_readiness.py",),
        witness=("test_android_emulator_readiness.AndroidEmulatorReadinessTest"
                 ".test_waits_until_android_package_service_is_usable"),
        prefix="FAKE_ADB_",
        real=None,
        boundary=("той самий зовнішній `adb`; підставний падає на незнайоме "
                  "слово, тож форма виклику не може розійтися мовчки."),
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
    ),
)


class RegistryTest(unittest.TestCase):
    """Перелік мусить бути повним, а кожен пін — існувати."""

    def test_the_registry_names_every_tool_under_the_fixtures(self):
        """Жоден файл-інструмент у `fixtures/` не лишається поза реєстром.

        Підставні інструменти фікстур — це все, що лежить під `fixtures/` і не
        є свідком (`tests/`). Новий такий файл мусить бути названий тут, інакше
        він не має ні піна на оточення, ні піна на форму.
        """
        declared = {Path(where) for case in CASES for where in case.where}
        found = {
            path.relative_to(REPO_ROOT)
            for path in FIXTURES.rglob("*")
            if path.is_file()
            and "tests" not in path.relative_to(FIXTURES).parts
        }

        self.assertTrue(found, "під fixtures/ мусять бути підставні інструменти")
        self.assertEqual(sorted(found - declared), [], "ці інструменти не в реєстрі")

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
