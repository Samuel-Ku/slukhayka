"""Розклад часу набору по модулях (#1312) — і тут це доводиться прогоном.

`scripts/suite_time.py` читає команду, теку й шаблон із блоку `script-tests` у
`ci.yml`, бере модулі з теки за шаблоном і міряє КОЖЕН інтервал: і тест, і
проміжок перед ним. Останнє — не прикраса: час `setUpClass` у `--durations` не
входить (це перевірено прогоном, а не прочитано), а саме там біжать гейти. Тому
підставне дерево тут будує САМЕ той випадок, який інакше не видно: модуль, чий
`setUpClass` дорожчий за всі його тести.

Що тут піниться:

  * `DecompositionTest` — на підставному дереві, СПРАВЖНІМ прогоном: модуль із
    дорогим `setUpClass` мусить бути найбільшим споживачем, і його секунда ПОЗА
    тестами мусить бути названа; топ-3 друкуються числом; `--module` дає те саме
    число незалежним прогоном;
  * `FindingTest` — порожній модуль: файл за шаблоном без жодного тесту мусить
    стати знахідкою (код 1) і бути названим, а не випасти з розкладу тихо;
  * `RefusalTest` — невідомість коштує код 2, а не «розклад із нулів»: команда
    не `discover`, невідомий прапорець, позиційний аргумент, немає теки, шаблон
    не називає модуля, `--module` не з теки, червоний прогін;
  * `DerivationTest` — команда, тека й шаблон ВИВОДЯТЬСЯ з `ci.yml`: інший
    шаблон у воркфлоу міняє те, що міряється, не чіпаючи код;
  * `ListingTest` — `--list` називає команду, теку, шаблон і модулі й НІЧОГО не
    міряє;
  * `UnitTest` — `decomposition` на підставлених інтервалах: проміжок перед
    тестом належить модулю цього тесту, а тест поза текою — не тиха згода, а
    чужий;
  * `VerdictTest` — коди виходу там, де прогін підставлений: чужий тест — код 1,
    навантаження машини не прочитано — код 2.

Зарахований у `scripts/guard_witnesses.py` — один сторож:
`DecompositionTest.test_the_module_with_the_slow_setup_is_the_top_consumer`.
Його мутація знімає саме те правило, яким цей механізм і потрібен: проміжок
перед тестом перестає належати модулю — і гейт, який біжить у `setUpClass`,
знову стає невидимим. Решта перевіряють сам механізм і в реєстрі не стоять:
реєстр покриває НАЗВАНИХ, а не всіх.
"""

import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "suite_time.py"
sys.path.insert(0, str(MECHANISM.parent))

import ci_job_time as cj  # noqa: E402
import suite_time as s  # noqa: E402

# Друге джерело правди: скількох споживачів механізм називає поіменно. Звузити
# це мовчки не можна — це видно як правку тесту.
EXPECTED_TOP = 3

# Підставні модулі. `alpha` тримає дорогий `setUpClass` — рівно той випадок, який
# `--durations` не бачить: його тести коротші за `beta`, а модуль — довший.
ALPHA = (
    "import time\n"
    "import unittest\n"
    "\n"
    "\n"
    "class AlphaTest(unittest.TestCase):\n"
    "    @classmethod\n"
    "    def setUpClass(cls):\n"
    "        time.sleep(0.25)\n"
    "\n"
    "    def test_one(self):\n"
    "        time.sleep(0.02)\n"
    "\n"
    "    def test_two(self):\n"
    "        time.sleep(0.02)\n"
)
BETA = (
    "import time\n"
    "import unittest\n"
    "\n"
    "\n"
    "class BetaTest(unittest.TestCase):\n"
    "    def test_one(self):\n"
    "        time.sleep(0.12)\n"
)
GAMMA = (
    "import time\n"
    "import unittest\n"
    "\n"
    "\n"
    "class GammaTest(unittest.TestCase):\n"
    "    def test_one(self):\n"
    "        time.sleep(0.04)\n"
)
DELTA = (
    "import unittest\n"
    "\n"
    "\n"
    "class DeltaTest(unittest.TestCase):\n"
    "    def test_one(self):\n"
    "        self.assertTrue(True)\n"
)
RED = (
    "import unittest\n"
    "\n"
    "\n"
    "class RedTest(unittest.TestCase):\n"
    "    def test_one(self):\n"
    "        self.fail('червоний прогін')\n"
)
# Модуль, який є в теці за шаблоном, але не виконує жодного тесту: він мусить
# стати знахідкою, а не випасти з розкладу тихо.
EMPTY = (
    "import unittest  # noqa: F401\n"
    "\n"
    "HELPER = 1\n"
)

# Допуск на незалежну перевірку `--module`: окремий прогін тієї самої команди
# міряє те саме, але інтерпретатор і сторінка кешу щоразу свої. Число назване
# тут, а не підібране в тесті.
MODULE_TOLERANCE = 0.2


def workflow_text(run: str | None = None, minutes: str | None = "5") -> str:
    """Мінімальний воркфлоу з тим самим розкладом відступів, що `ci.yml`."""
    step = run or ('run: python3 -m unittest discover -s scripts/tests '
                   '-p "test_*.py"')
    body = (
        "  script-tests:\n"
        "    name: Script unit tests\n"
        "    runs-on: ubuntu-latest\n"
        "%s"
        "\n"
        "    steps:\n"
        "      - name: Checkout\n"
        "        uses: actions/checkout@v5\n"
        "\n"
        "      - name: Run the script unit tests\n"
        "        %s\n"
        % ("    timeout-minutes: %s\n" % minutes if minutes else "", step)
    )
    return "name: CI\n\non: [push]\n\njobs:\n" + body + "\n  timing-start:\n    name: x\n"


def table_rows(stdout: str) -> list[tuple[str, float, float, float]]:
    """Рядки таблиці: (модуль, усього, у тестах, поза тестами) — з виводу."""
    rows = []
    for line in stdout.splitlines():
        stripped = line.strip()
        if not stripped or not stripped[0].isdigit() or "." not in stripped:
            continue
        if "·" not in stripped:
            continue
        head, tail = stripped.split("·", 1)
        parts = head.split()
        module = parts[1]
        seconds = float(parts[2])
        inside = float(tail.split("у тестах")[1].split("с")[0])
        outside = float(tail.split("поза тестами")[1].split("с")[0])
        rows.append((module, seconds, inside, outside))
    return rows


class Fixture(unittest.TestCase):
    """Підставне дерево з воркфлоу, текою й модулями — те, що механізм читає."""

    MODULES = {"test_alpha.py": ALPHA, "test_beta.py": BETA}

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="suite-time-test-")
        self.addCleanup(self.temporary.cleanup)
        self.tree = Path(self.temporary.name) / "tree"
        (self.tree / ".github" / "workflows").mkdir(parents=True)
        (self.tree / "scripts" / "tests").mkdir(parents=True)
        self.workflow = self.tree / ".github" / "workflows" / "ci.yml"
        self.write_workflow()
        for name, body in self.MODULES.items():
            self.write_module(name, body)

    def write_workflow(self, run: str | None = None, minutes: str | None = "5") -> None:
        self.workflow.write_text(workflow_text(run, minutes), encoding="utf-8")

    def write_module(self, name: str, body: str) -> None:
        (self.tree / "scripts" / "tests" / name).write_text(body, encoding="utf-8")

    def run_mechanism(self, *args) -> tuple[int, str, str]:
        """Механізм на підставному дереві — СПРАВЖНІМ прогоном, як CI."""
        result = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(self.tree), *args],
            capture_output=True, text=True, check=False, timeout=300,
        )
        return result.returncode, result.stdout, result.stderr


class DecompositionTest(Fixture):
    """Розклад — на підставному дереві, і кожне число виводиться."""

    def test_the_module_with_the_slow_setup_is_the_top_consumer(self):
        """Сторож: секунда `setUpClass` мусить належати СВОЄМУ модулю.

        Мутація (див. `guard_witnesses.py`) лишає в числі модуля тільки тести —
        і `test_alpha.py` (0.04 с у тестах) падає нижче за `test_beta.py`
        (0.10 с). Без цього правила гейт, який біжить у `setUpClass`, знову
        невидимий — рівно те, за що серія й платила.
        """
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        rows = table_rows(out)
        self.assertEqual("test_alpha.py", rows[0][0],
                         "модуль із дорогим setUpClass мусить бути першим:\n%s" % out)
        self.assertEqual("test_beta.py", rows[1][0])
        self.assertGreater(rows[0][3], 0.2,
                           "секунда setUpClass мусить бути названа як «поза тестами»")

    def test_the_top_three_are_named_with_their_numbers(self):
        self.write_module("test_gamma.py", GAMMA)
        self.write_module("test_delta.py", DELTA)
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        rows = table_rows(out)
        self.assertGreaterEqual(len(rows), 4)
        line = [line for line in out.splitlines()
                if line.startswith("найбільші %d:" % EXPECTED_TOP)]
        self.assertEqual(1, len(line), out)
        self.assertTrue(line[0].startswith("найбільші %d:" % EXPECTED_TOP), line[0])
        for module, seconds, _inside, _outside in rows[:EXPECTED_TOP]:
            self.assertIn("%s %.1f с" % (module, seconds), line[0],
                          "топ мусить називати модуль і його число:\n%s" % line[0])
        self.assertNotIn(rows[EXPECTED_TOP][0], line[0],
                         "четвертий модуль у топ не входить")

    def test_the_gap_is_named_as_the_setup_of_the_class_that_follows_it(self):
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        self.assertIn("перед test_alpha.AlphaTest.test_one", out,
                      "проміжок мусить бути названий перед тим тестом, за яким він іде")

    def test_the_label_is_the_same_one_the_documents_rule_accepts(self):
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        labels = [line for line in out.splitlines() if line.startswith("ЛОКАЛЬНО")]
        self.assertEqual(1, len(labels), out)
        self.assertTrue(cj.labelled_local(labels[0]),
                        "позначку мусить приймати те саме правило, що й `ci_job_time`: %s"
                        % labels[0])
        self.assertIn("НЕ час джоби", out)

    def test_the_module_is_measured_again_by_its_own_run(self):
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        suite_seconds = table_rows(out)[0][1]
        code, out, err = self.run_mechanism("--module", "test_alpha.py")
        self.assertEqual(0, code, err)
        rows = table_rows(out)
        self.assertEqual(1, len(rows), out)
        self.assertEqual("test_alpha.py", rows[0][0])
        self.assertAlmostEqual(suite_seconds, rows[0][1], delta=MODULE_TOLERANCE,
                               msg="незалежний прогін мусить дати те саме число")
        self.assertIn("незалежна перевірка", out)


class DerivationTest(Fixture):
    """Команда, тека й шаблон — з `ci.yml`, а не зі списку в коді."""

    def test_another_pattern_in_the_workflow_measures_another_module(self):
        self.write_workflow(run='run: python3 -m unittest discover '
                                '-s scripts/tests -p "test_beta.py"')
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        self.assertEqual(["test_beta.py"], [row[0] for row in table_rows(out)])
        self.assertIn("шаблон: test_beta.py", out)

    def test_another_start_directory_is_read_from_the_workflow(self):
        (self.tree / "elsewhere").mkdir()
        (self.tree / "elsewhere" / "test_other.py").write_text(DELTA, encoding="utf-8")
        self.write_workflow(run='run: python3 -m unittest discover '
                                '-s elsewhere -p "test_*.py"')
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        self.assertEqual(["test_other.py"], [row[0] for row in table_rows(out)])


class ListingTest(Fixture):
    """`--list` називає те, що механізм покриває, і НІЧОГО не міряє."""

    def test_the_list_names_the_command_the_modules_and_the_top(self):
        code, out, err = self.run_mechanism("--list")
        self.assertEqual(0, code, err)
        self.assertIn("python3 -m unittest discover -s scripts/tests", out)
        self.assertIn("тека модулів: scripts/tests", out)
        self.assertIn("шаблон: test_*.py", out)
        self.assertIn("test_alpha.py, test_beta.py", out)
        self.assertIn("найбільших споживачів називається: %d" % EXPECTED_TOP, out)

    def test_the_list_measures_nothing(self):
        code, out, err = self.run_mechanism("--list")
        self.assertEqual(0, code, err)
        self.assertNotIn("ЛОКАЛЬНО", out)
        self.assertNotIn("найбільші ", out)

    def test_a_broken_tree_is_named_unknown_in_the_list_instead_of_crashing(self):
        self.write_workflow(run='run: python3 -m unittest discover -p "test_*.py"')
        code, out, err = self.run_mechanism("--list")
        self.assertEqual(0, code, err)
        self.assertIn("НЕВІДОМО", out)


class FindingTest(Fixture):
    """Порожній модуль — знахідка (1), а не тихе випадання з розкладу.

    Модуль, який є в теці за шаблоном, але не виконав жодного тесту, не має
    жодної секунди — і саме тому його легко не побачити. Наступний крок серії
    шукає секунди, тож модуль, який випав, сховав би свої.
    """

    def test_a_module_without_tests_is_a_finding_not_a_silent_drop(self):
        self.write_module("test_empty.py", EMPTY)
        code, out, err = self.run_mechanism()
        self.assertEqual(1, code, out)
        self.assertIn("test_empty.py", err)
        self.assertIn("без жодного тесту", out)
        self.assertIn("test_empty.py", out)
        self.assertIn("test_alpha.py", out, "решта розкладу мусить лишитися видимою")

    def test_a_tree_without_empty_modules_stays_green(self):
        code, out, err = self.run_mechanism()
        self.assertEqual(0, code, err)
        self.assertNotIn("без жодного тесту", out)


class RefusalTest(Fixture):
    """Невідомість — код 2, а не розклад із нулів."""

    def assert_unknown(self, out: str, err: str, code: int) -> None:
        self.assertEqual(2, code, out)
        self.assertIn("НЕВІДОМО", err)
        self.assertNotIn("ЛОКАЛЬНО", out)

    def test_a_command_that_is_not_discover_is_an_unknown(self):
        self.write_workflow(run="run: python3 -m pytest scripts/tests")
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_an_unknown_flag_is_an_unknown(self):
        self.write_workflow(run='run: python3 -m unittest discover -s scripts/tests '
                                '-p "test_*.py" -k alpha')
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_a_positional_argument_is_an_unknown(self):
        self.write_workflow(run='run: python3 -m unittest discover -s scripts/tests '
                                '-p "test_*.py" test_alpha')
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_a_missing_start_directory_is_an_unknown(self):
        self.write_workflow(run='run: python3 -m unittest discover -s nowhere '
                                '-p "test_*.py"')
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_a_pattern_that_names_no_module_is_an_unknown(self):
        self.write_workflow(run='run: python3 -m unittest discover -s scripts/tests '
                                '-p "test_nothing_*.py"')
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_a_module_outside_the_directory_is_an_unknown(self):
        code, out, err = self.run_mechanism("--module", "test_nothing.py")
        self.assert_unknown(out, err, code)

    def test_a_red_run_is_an_unknown_not_a_number(self):
        self.write_module("test_red.py", RED)
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)

    def test_a_missing_workflow_is_an_unknown(self):
        self.workflow.unlink()
        code, out, err = self.run_mechanism()
        self.assert_unknown(out, err, code)


class UnitTest(unittest.TestCase):
    """`decomposition` на підставлених інтервалах — без прогону й без секунд."""

    NAMES = ["test_alpha.py", "test_beta.py"]

    def record(self, *intervals, discover_end: float = 0.0, finished: float = 0.0):
        return {"started": 0.0, "discover_end": discover_end, "finished": finished,
                "tests": len(intervals), "intervals": list(intervals)}

    def test_the_gap_before_a_test_belongs_to_the_module_of_that_test(self):
        report = s.decomposition(
            self.record(("test_alpha.AlphaTest.test_one", 0.5, 0.6, "ok"),
                        ("test_beta.BetaTest.test_one", 1.0, 1.1, "ok"),
                        discover_end=0.0, finished=1.1),
            self.NAMES,
        )
        self.assertEqual(["test_alpha.py", "test_beta.py"],
                         [row.module for row in report.rows])
        self.assertAlmostEqual(0.6, report.rows[0].seconds, places=6)
        self.assertAlmostEqual(0.5, report.rows[0].outside, places=6)
        self.assertAlmostEqual(0.5, report.rows[1].seconds, places=6)
        self.assertAlmostEqual(0.4, report.rows[1].outside, places=6)
        # Проміжок перед першим тестом — це `setUpClass` першого класу; проміжок
        # перед `beta` — 0.4 с. Список іде від більшого, тож перший там alpha.
        self.assertEqual("test_alpha.AlphaTest.test_one", report.gaps[0].test)
        beta_gap = [gap for gap in report.gaps if gap.test.startswith("test_beta")]
        self.assertAlmostEqual(0.4, beta_gap[0].seconds, places=6)
        self.assertEqual((), report.empty, "усі модулі виконали хоч один тест")

    def test_a_module_without_tests_is_named_empty_not_dropped(self):
        report = s.decomposition(
            self.record(("test_alpha.AlphaTest.test_one", 0.0, 0.1, "ok"),
                        ("test_beta.BetaTest.test_one", 0.1, 0.2, "ok"),
                        discover_end=0.0, finished=0.2),
            self.NAMES + ["test_empty.py"],
        )
        self.assertEqual(("test_empty.py",), report.empty)
        self.assertEqual(["test_alpha.py", "test_beta.py"],
                         [row.module for row in report.rows])

    def test_a_test_outside_the_directory_is_a_stranger_not_a_silent_zero(self):
        report = s.decomposition(
            self.record(("test_alpha.AlphaTest.test_one", 0.0, 0.1, "ok"),
                        ("unittest.loader._FailedTest.test_x", 0.1, 0.2, "error"),
                        discover_end=0.0, finished=0.2),
            self.NAMES,
        )
        self.assertEqual(["unittest.loader._FailedTest.test_x"], report.strangers)

    def test_intervals_that_do_not_match_the_count_are_an_unknown(self):
        record = self.record(("test_alpha.AlphaTest.test_one", 0.0, 0.1, "ok"))
        record["tests"] = 2
        with self.assertRaises(s.SuiteTimeError):
            s.decomposition(record, self.NAMES)

    def test_a_zero_length_suite_cannot_be_divided(self):
        with self.assertRaises(s.SuiteTimeError):
            s.percent(1.0, 0.0)


class VerdictTest(Fixture):
    """Коди виходу там, де прогін підставлений, — без справжніх секунд."""

    def main(self, record, *argv, facts=(1.0, 8.0, 16.0)):
        out, err = io.StringIO(), io.StringIO()
        with unittest.mock.patch.object(s, "run_suite", return_value=record), \
                unittest.mock.patch.object(s.job_time, "machine_facts",
                                           return_value=facts), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = s.main(["--root", str(self.tree), *argv])
        return code, out.getvalue(), err.getvalue()

    def record(self, *intervals, finished: float = 1.0):
        return {"started": 0.0, "discover_end": 0.0, "finished": finished,
                "tests": len(intervals), "intervals": list(intervals)}

    def test_a_stranger_test_is_a_finding_not_a_pass(self):
        code, out, err = self.main(
            self.record(("test_alpha.AlphaTest.test_one", 0.0, 0.1, "ok"),
                        ("unittest.loader._FailedTest.test_x", 0.1, 0.2, "error")),
        )
        self.assertEqual(1, code, out)
        self.assertIn("поза текою", err)

    def test_machine_facts_that_cannot_be_read_are_an_unknown(self):
        out, err = io.StringIO(), io.StringIO()
        with unittest.mock.patch.object(s, "run_suite",
                                        return_value=self.record(
                                            ("test_alpha.AlphaTest.test_one",
                                             0.0, 0.1, "ok"))), \
                unittest.mock.patch.object(
                    s.job_time, "machine_facts",
                    side_effect=cj.JobTimeError("навантаження не прочитано")), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = s.main(["--root", str(self.tree)])
        self.assertEqual(2, code, out.getvalue())
        self.assertIn("НЕВІДОМО", err.getvalue())
        self.assertNotIn("ЛОКАЛЬНО", out.getvalue())
