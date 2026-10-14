"""Механізм #1260: кожне поле, що вирішує вердикт, мусить мати мутацію-свідка.

Головний доказ лежить у фікстурах. `fixtures/verdict_field/unpinned` повторює
стан JVM-половини до #1257 — `MUTATE_TEST_ERRORS` у підставному `gradlew` був
завжди 0, тож артефакт із `errors>0` не проходив через жоден тест, — і механізм
мусить бути на ньому ЧЕРВОНИМ. `fixtures/verdict_field/pinned` відрізняється
лише набором-свідком, і механізм на ньому зелений. Обидва корені мають
байт-у-байт той самий інструмент, і це піниться окремим тестом.

Останні два класи тримають механізм чесним:

 - `FailClosedTest` — невідомість коштує окремий код: поле не знайдено, свідка
   немає, набір червоний БЕЗ мутації, прогін не завершився, інструмент зібраний
   не весь, мутант не проходить синтаксис, механізм усередині себе. Там же —
   дві речі, які інакше тихо зламалися б: мутант живе лише в КОПІЇ дерева, і
   вимикача в механізму немає;
 - `SensitivityTest` — з КОПІЇ механізму знімається одна перевірка за раз, і
   названий тест цього файлу мусить почервоніти. Перевірка, яку ніщо не
   помічає, — це та сама діра, проти якої механізм і заведений.
"""

import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
TEST_MUTATE = REPO_ROOT / "scripts" / "tests" / "test_mutate.py"
TEST_MUTATE_PYTHON = REPO_ROOT / "scripts" / "tests" / "test_mutate_python.py"

# Скільки випадків чутливості гнати одночасно (#1266). Кожен — окремий
# підпроцес зі своєю копією механізму, тож черга тут не потрібна: вона лишала
# ядра просто. Це швидкість, а не вимикач — кожен випадок усе одно виконується.
SENSITIVITY_JOBS = min(4, os.cpu_count() or 1)

# Механізм під тестом. Чутливість підсовує сюди КОПІЮ з однією знятою
# перевіркою, тож «набір падає на мутації» доводиться прогоном, а не тим, що
# мутація існує.
MECHANISM = Path(
    os.environ.get(
        "SLUKHAYKA_VERDICT_FIELDS", REPO_ROOT / "scripts" / "verdict_field_mutants.py"
    )
)
sys.path.insert(0, str(REPO_ROOT / "scripts"))
sys.path.insert(0, str(MECHANISM.parent))

import verdict_field_mutants as v  # noqa: E402

FIXTURES = REPO_ROOT / "scripts" / "tests" / "fixtures" / "verdict_field"
TESTS_DIR = Path("scripts/tests")

SHELL = (
    'tests="" failures="" errors="" skipped=""\n'
    "counts=$(python3 - <<'PY'\n"
    "print(\n"
    '    root.get("errors", "0"),\n'
    ")\n"
    "PY\n"
    ")\n"
    'read -r tests failures errors skipped <<< "$counts"\n'
    "if [[ -z $tests ]]; then\n"
    "  outcome=no-verdict\n"
    'elif [[ $failures -gt 0 || $errors -gt 0 ]]; then  # errors=$errors тут вирішує\n'
    "  outcome=killed\n"
    'elif [[ $skipped == "$tests" ]]; then\n'
    "  outcome=no-verdict\n"
    "fi\n"
    'python3 "$trace_module" record "outcome=$outcome" "errors=$errors"\n'
    'detail="the class failed (errors=$errors)"\n'
)

RED_WITNESS = '''"""Свідок scripts/mutate.sh і scripts/mutation_trace.py, який завжди червоний."""
import unittest


class GateTest(unittest.TestCase):
    def test_always_red(self):
        self.fail("зламаний набір")
'''

HANGING_WITNESS = '''"""Свідок scripts/mutate.sh і scripts/mutation_trace.py, який не завершується."""
import time
import unittest


class GateTest(unittest.TestCase):
    def test_never_finishes(self):
        time.sleep(30)
'''

FIELD_WITNESS = '''"""Свідок scripts/mutate.sh і scripts/mutation_trace.py, який нічого не перевіряє.

Порожній тест: механізм не має права називати його свідком поля.
"""
import unittest


class GateTest(unittest.TestCase):
    def test_nothing(self):
        self.assertTrue(True)
'''


def mechanism_text() -> str:
    return MECHANISM.read_text(encoding="utf-8")


class DiscoveryTest(unittest.TestCase):
    """Місця поля знаходять машинно: кожна ланка ланцюга — окремо."""

    def errors(self):
        return v.FIELDS_BY_NAME["errors"]

    def test_the_xml_attribute_is_a_site(self):
        source = (
            "import xml.etree.ElementTree as ET\n"
            "\n"
            'suite = ET.Element("testsuite", {"tests": str(result.testsRun), '
            '"errors": str(len(result.errors))})\n'
        )

        found = v.python_sites(Path("runner.py"), source, self.errors())

        self.assertEqual(["атрибут артефакту"], [site.kind for site in found])
        self.assertEqual("str(len(result.errors))", found[0].original)
        # Атрибут XML — завжди рядок, тож нуль тут рядковий.
        self.assertEqual('"0"', found[0].replacement)

    def test_the_record_field_and_the_status_field_are_different_sites(self):
        source = (
            "def build_record(values):\n"
            "    return {\n"
            '        "schema": 1,\n'
            '        "errors": integer("errors"),\n'
            "    }\n"
            "\n"
            "def answer(digest):\n"
            '    return {"artifactSha256": digest}\n'
        )

        record = v.python_sites(Path("trace.py"), source, self.errors())
        answer = v.python_sites(Path("trace.py"), source, v.FIELDS_BY_NAME["artifactSha256"])

        self.assertEqual(["поле запису"], [site.kind for site in record])
        self.assertEqual("0", record[0].replacement)
        self.assertEqual(["поле відповіді"], [site.kind for site in answer])
        self.assertEqual("None", answer[0].replacement)

    def test_a_read_of_the_field_is_a_site(self):
        source = (
            "def verdict_for(matching, digest):\n"
            '    twins = [record for record in matching '
            'if record.get("artifactSha256") == digest]\n'
            "    return twins\n"
        )

        found = v.python_sites(Path("trace.py"), source, v.FIELDS_BY_NAME["artifactSha256"])

        self.assertEqual(["читання поля"], [site.kind for site in found])
        self.assertEqual('record.get("artifactSha256")', found[0].original)
        self.assertEqual("None", found[0].replacement)

    def test_a_non_ascii_line_does_not_shift_the_span(self):
        """`ast` рахує колонки в БАЙТАХ, а текст — у символах.

        Без перекладу межа їде на кожному не-ASCII символі перед місцем (тут —
        на дванадцяти байтах «помилка»), і мутант замінює не той шматок: або
        рве файл (код 2), або вбиває місце з чужої причини.
        """
        source = (
            "def build(values):\n"
            "    return {\n"
            '        "schema": 1,\n'
            '        "detail": "помилка", "errors": 0,\n'
            "    }\n"
        )

        site = v.python_sites(Path("trace.py"), source, self.errors())[0]

        self.assertEqual("0", site.original)
        self.assertEqual("0", site.replacement)

    def test_an_unparsable_python_file_is_an_error_not_an_empty_search(self):
        with self.assertRaises(v.FieldError):
            v.python_sites(Path("trace.py"), "def broken(:\n", self.errors())

    def test_the_artifact_read_is_a_site(self):
        found = v.shell_sites(Path("mutate.sh"), SHELL, self.errors())

        site = next(site for site in found if site.kind == "читання з артефакту")

        self.assertEqual('root.get("errors", "0")', site.original)
        self.assertEqual('"0"', site.replacement)
        self.assertEqual(4, site.line)

    def test_the_record_transport_is_a_site(self):
        found = v.shell_sites(Path("mutate.sh"), SHELL, self.errors())

        site = next(site for site in found if site.kind == "передача в запис")

        self.assertEqual('"errors=$errors"', site.original)
        self.assertEqual('"errors=0"', site.replacement)

    def test_the_verdict_rule_is_a_site_including_presence_tests(self):
        found = [site for site in v.shell_sites(Path("mutate.sh"), SHELL, self.errors())
                 if site.kind == "правило вердикту"]
        tests = [site for site in v.shell_sites(Path("mutate.sh"), SHELL, v.FIELDS_BY_NAME["tests"])
                 if site.kind == "правило вердикту"]

        # `$errors` у `[[ $failures -gt 0 || $errors -gt 0 ]]`: нуль поля — це
        # те, чим став би рядок, якби змінна несла 0.
        self.assertEqual(["$errors"], [site.original for site in found])
        self.assertEqual(["0"], [site.replacement for site in found])
        # `[[ -z $tests ]]` — теж місце: нуль поля тут працює (`-z 0` — це саме
        # те, що бачить shell, коли змінна несе "0"), і `"$tests"` у порівнянні
        # отримує нуль у лапках.
        self.assertEqual(["$tests", '"$tests"'], [site.original for site in tests])
        self.assertEqual(["0", '"0"'], [site.replacement for site in tests])

    def test_a_mention_in_prose_is_not_a_site(self):
        """Місце — це код, а не проза: ні рядок `detail`, ні коментар праворуч."""
        found = v.shell_sites(Path("mutate.sh"), SHELL, self.errors())
        lines = SHELL.splitlines()
        detail_line = lines.index('detail="the class failed (errors=$errors)"') + 1

        self.assertNotIn(detail_line, [site.line for site in found])
        self.assertNotIn("errors=$errors)", [site.original for site in found])
        # Коментар праворуч від правила: рядок із `[[` є, а місця в коментарі —
        # немає. Без цього мутант правив би коментар, тобто був би еквівалентний
        # і виживав би завжди — відмова за причини, якої немає.
        rule_line = next(index for index, line in enumerate(lines, 1)
                         if line.startswith("elif [[ $failures"))
        self.assertIn("errors=$errors тут вирішує", lines[rule_line - 1])
        self.assertEqual([rule_line], [site.line for site in found
                                       if site.kind == "правило вердикту"])

    def test_the_shell_variable_is_read_from_the_transport_or_from_read(self):
        # Хеш їде під іншим іменем (`artifact_sha`), ніж поле: здогад про
        # camelCase→snake_case тут не працює, ім'я береться з токена передачі.
        hashed = 'python3 "$trace" record "artifactSha256=$artifact_sha"\n'
        # А лічильник, якого в запис не передають, називає сам `read -r`.
        counted = 'read -r tests failures errors skipped <<< "$counts"\n'

        self.assertEqual("artifact_sha",
                         v._shell_variable(hashed, v.FIELDS_BY_NAME["artifactSha256"]))
        self.assertEqual("skipped", v._shell_variable(counted, v.FIELDS_BY_NAME["skipped"]))
        self.assertEqual("", v._shell_variable("echo hi\n", v.FIELDS_BY_NAME["skipped"]))

    def test_the_tool_files_come_from_the_wiring_and_must_all_exist(self):
        root = Path(self._tmp.name)
        (root / "scripts").mkdir(parents=True)
        (root / "scripts" / "mutate.sh").write_text(
            'repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)\n'
            "trace_module=$repo_root/scripts/mutation_trace.py\n",
            encoding="utf-8",
        )
        (root / "scripts" / "mutation_trace.py").write_text("", encoding="utf-8")

        self.assertEqual(
            [Path("scripts/mutate.sh"), Path("scripts/mutation_trace.py")],
            [path.relative_to(root) for path in v.tool_files(root)],
        )

        (root / "scripts" / "mutation_trace.py").unlink()
        with self.assertRaises(v.FieldError):
            v.tool_files(root)

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)


class WitnessTest(unittest.TestCase):
    """Хто може бути свідком поля, а хто ні."""

    def test_the_narrow_phase_keeps_only_tests_that_name_the_field(self):
        targets = v.test_targets(REPO_ROOT / "scripts" / "tests" / "test_mutate.py", "errors")

        self.assertIn(
            "test_mutate.MutationRunTest.test_records_an_error_as_a_caught_mutation", targets
        )
        self.assertNotIn("test_mutate.MutationRunTest.test_refuses_a_dirty_worktree", targets)

    def test_a_class_that_runs_the_suite_is_not_a_witness(self):
        """Мета-тест набору ловить «щось змінилося», а не поле.

        Він вимагає, щоб `intact`-прогін був зелений, а `intact` у ньому — той
        самий мутант. Порахувати його свідком означало б визнати вбитим будь-який
        мутант.
        """
        for module, meta in (
            (REPO_ROOT / "scripts" / "tests" / "test_mutate.py", "MutationSensitivityTest"),
            (REPO_ROOT / "scripts" / "tests" / "test_mutate_python.py", "PythonSensitivityTest"),
        ):
            with self.subTest(module=module.name):
                targets = v.test_targets(module)

                self.assertTrue(targets, "у модуля мусять бути справжні свідки")
                self.assertFalse([target for target in targets if meta in target])
                self.assertTrue(any("RunTest" in target for target in targets))

    def test_a_program_run_by_name_is_a_suite_run_too(self):
        """`-c <програма>`, що вантажить тести за іменами, — та сама ознака (#1270).

        Ґард оточення перестав писати `-m unittest` у виклику, коли почав брати
        лічильники з самого прогону, і клас-мета-тест раптом став свідком поля:
        підрядок `unittest` у виклику — це ФОРМА, а чи ганяє клас набір, видно з
        того, ЩО робить програма. Коштувало це десяти секунд прогону на кожне
        місце `mutate.sh` — тобто п'ятдесяти секунд джоби.
        """
        source = (
            "import subprocess\n"
            "import sys\n"
            "import unittest\n"
            "\n"
            "RUNNER = '''\n"
            "import sys\n"
            "import unittest\n"
            "suite = unittest.TestLoader().loadTestsFromNames(sys.argv[1:])\n"
            "'''\n"
            "\n"
            "\n"
            "class MetaTest(unittest.TestCase):\n"
            "    def test_runs_the_suite_by_program(self):\n"
            "        subprocess.run([sys.executable, '-c', RUNNER, 'test_x'], check=False)\n"
            "\n"
            "\n"
            "class RealTest(unittest.TestCase):\n"
            "    def test_named_field(self):\n"
            "        record = {'errors': 1}\n"
            "        self.assertEqual(1, record['errors'])\n"
        )
        with tempfile.TemporaryDirectory(prefix="witness-by-program-") as temporary:
            module = Path(temporary) / "test_meta_by_program.py"
            module.write_text(source, encoding="utf-8")

            targets = v.test_targets(module)

        self.assertEqual(["test_meta_by_program.RealTest.test_named_field"], targets,
                         "мета-тест програмою `-c` не свідок, а справжній тест — свідок")

    def test_the_two_phases_are_narrow_then_full(self):
        module = REPO_ROOT / "scripts" / "tests" / "test_mutate.py"

        phases = v.phases_for(module, "errors")

        self.assertEqual(["вузький набір", "повний модуль"], [phase for phase, _ in phases])
        self.assertLess(len(phases[0][1]), len(phases[1][1]))

    def test_the_narrow_phases_of_every_witness_come_before_the_full_ones(self):
        """Порядок МІЖ свідками (#1313): вузькі всіх — перед повними будь-кого.

        Це перестановка, а не звуження, тож вердикт змінитися не може — а час
        може, і саме тому вона тут піниться. Повна фаза модуля, який не вбиває,
        не сміє бігти перед вузькою модуля, який убиває: виміряно, що саме там
        губилися десятки секунд.
        """
        modules = [REPO_ROOT / "scripts" / "tests" / "test_mutate.py",
                   REPO_ROOT / "scripts" / "tests" / "test_mutate_python.py"]

        phases = v.witness_phases(modules, "errors")
        kinds = [phase for _module, phase, _targets in phases]

        # Нічого не загублено: це ті самі фази, що дає `phases_for` на кожен модуль.
        self.assertEqual(
            sorted((module.name, phase) for module in modules
                   for phase, _targets in v.phases_for(module, "errors")),
            sorted((module.name, phase) for module, phase, _targets in phases),
        )
        # І жодна повна не стоїть перед вузькою.
        self.assertIn(v.FULL_PHASE, kinds, "повна фаза мусить лишитися")
        self.assertIn(v.NARROW_PHASE, kinds, "вузька фаза мусить лишитися")
        first_full = kinds.index(v.FULL_PHASE)
        self.assertNotIn(v.NARROW_PHASE, kinds[first_full:])


class MutantTest(unittest.TestCase):
    def test_the_mutation_replaces_exactly_the_site(self):
        source = 'line one\nroot.get("errors", "0")\nline three\n'
        site = v.shell_sites(Path("mutate.sh"), source, v.FIELDS_BY_NAME["errors"])[0]

        mutant = v.mutate(source, site)

        self.assertEqual('line one\n"0"\nline three\n', mutant)

    def test_a_broken_mutant_is_refused_before_any_run(self):
        with self.assertRaises(v.FieldError):
            v.validate("def broken(:\n", Path("tool.py"))
        with self.assertRaises(v.FieldError):
            v.validate("if [[ -z $tests ]\n", Path("tool.sh"))

        v.validate('x = 1\n', Path("tool.py"))
        v.validate('if [[ -z $tests ]]; then :; fi\n', Path("tool.sh"))


class HistoricalStateTest(unittest.TestCase):
    """Фікстура #1257: поле errors не пінить ніхто; у pinned — пінить."""

    def report(self, name: str) -> v.Report:
        return v.check(FIXTURES / name, TESTS_DIR, only=["errors"])

    def test_the_two_fixtures_differ_only_in_the_witness(self):
        for relative in ("scripts/mutate.sh", "scripts/mutation_trace.py"):
            with self.subTest(file=relative):
                self.assertEqual(
                    (FIXTURES / "unpinned" / relative).read_bytes(),
                    (FIXTURES / "pinned" / relative).read_bytes(),
                )

    def test_the_historical_state_is_red_and_names_the_field_and_the_witness(self):
        report = self.report("unpinned")

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.killed)
        self.assertEqual(4, len(report.findings))
        for finding in report.findings:
            self.assertIn("errors", finding)
            self.assertIn("не пінить жоден тест", finding)
            self.assertIn("test_gate.py", finding)

    def test_the_pinned_state_kills_every_site(self):
        report = self.report("pinned")

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.findings)
        self.assertEqual(4, len(report.killed))
        for verdict in report.killed:
            self.assertTrue(
                any("test_a_witness_that_raises" in name for name in verdict.killed_by),
                verdict.killed_by,
            )

    def test_the_fixture_cli_is_shaped_like_the_real_one(self):
        """`record` — підкоманда, а не позиційний аргумент ПЕРЕД опцією.

        Форму `record --log X key=value …` різні patch-версії CPython розбирають
        по-різному: 3.12.3 (`python3` в ubuntu-24.04, тобто в джобі script-tests)
        лишає `key=value` «unrecognized arguments», а 3.12.12 розбирає. Саме на
        цьому CI PR #1263 упав удруге — вже після ліку оточення. Справжній
        `scripts/mutation_trace.py` використовує підкоманду, і фікстура мусить
        бути такою самою: інакше вона перевіряє не ту форму, яку вдає.
        """
        modules = [REPO_ROOT / "scripts" / "mutation_trace.py"] + [
            FIXTURES / name / "scripts" / "mutation_trace.py" for name in ("pinned", "unpinned")
        ]
        for module in modules:
            with self.subTest(module=module.name):
                result = subprocess.run([sys.executable, str(module), "--help"],
                                        capture_output=True, text=True)

                self.assertEqual(0, result.returncode, result.stderr)
                usage = result.stdout.splitlines()[0]
                # Підкоманди (фігурні дужки) — і серед них `record`; стара форма
                # мала в usage `--log LOG record`, тобто позиційний перед опцією.
                self.assertIn("{", usage, usage)
                self.assertIn("record", usage, usage)
                self.assertNotIn("--log LOG record", usage, usage)

    def test_the_fixture_verdict_rule_is_the_real_one(self):
        """Ланка, яку вдає фікстура, — це насамперед ПРАВИЛО вердикту.

        Запис реєстру каже, що фікстура вдає ланку (артефакт → лічильники →
        вердикт → запис), а не CLI: у неї нуль argv, у справжнього
        `scripts/mutate.sh` на нуль argv — usage. Ланка без правила — не ланка,
        тож рішення мусить бути тим самим рядком, що в справжньому інструменті:
        `errors`/`failures` убивають, усі скіпи — `no-verdict`. Розійдись
        справжній інструмент із фікстурою — фікстура перевіряла б інше правило,
        ніж те, що в репозиторії, і це саме та межа, яку реєстр називає піном.
        """
        real = (REPO_ROOT / "scripts" / "mutate.sh").read_text(encoding="utf-8")

        for name in ("pinned", "unpinned"):
            with self.subTest(fixture=name):
                fixture = (FIXTURES / name / "scripts" / "mutate.sh").read_text(
                    encoding="utf-8"
                )
                for rule in (
                    "elif [[ $failures -gt 0 || $errors -gt 0 ]]; then",
                    'elif [[ $skipped == "$tests" ]]; then',
                ):
                    self.assertIn(rule, real,
                                  "правило вердикту зникло зі справжнього інструмента")
                    self.assertIn(rule, fixture, rule)

    def test_the_two_fixtures_cover_both_real_halves_of_the_chain(self):
        """Чотири місця — це читання, правило, передача й поле запису."""
        report = self.report("pinned")

        self.assertEqual(
            {"поле запису", "правило вердикту", "передача в запис", "читання з артефакту"},
            {site.kind for site in report.sites},
        )


# Другий свідок тієї самої ланки, який ДОПИСУЄТЬСЯ до копії фікстури: на ньому
# видно, що прогін мутанта спиняється на першій червоній цілі, а не договорює
# решту. З одним свідком це число не відрізнити від повного прогону — саме тому
# він і потрібен. Сортується ПІСЛЯ `test_a_witness_that_raises_…`, тож першою
# червоною лишається вона, і решта перевірок фікстури не зрушуються.
SECOND_WITNESS = '''

    def test_the_zeroed_errors_are_named_by_the_record(self):
        artifact(tests=1, failures=0, errors=1)

        self.assertEqual(1, run_tool()["errors"])
'''


class FailfastTest(unittest.TestCase):
    """Прогін мутанта спиняється на першій червоній цілі — і це видно, а не обіцяно.

    Вердикт від цього не залежить: він — код виходу, а прапорець може лише
    ВКОРОТИТИ прогін, не створивши й не знявши жодної червоної цілі. Змінюється
    рівно одне — скільком свідкам прогін устигає впасти. Без цієї перевірки
    «швидше» могло б тихо означати «інакше»: мутація, яка знімає прапорець,
    лишає вердикт тим самим, а це число — ні (запис у реєстрі
    `guard_witnesses`).
    """

    def test_the_mutant_run_stops_at_the_first_red(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(FIXTURES / "pinned", root, dirs_exist_ok=True)
            witness = root / "scripts" / "tests" / "test_gate.py"
            witness.write_text(witness.read_text(encoding="utf-8") + SECOND_WITNESS,
                               encoding="utf-8")

            report = v.check(root, TESTS_DIR, only=["errors"])

        self.assertEqual([], report.blocked)
        self.assertEqual(4, len(report.killed))
        for verdict in report.killed:
            self.assertEqual(1, len(verdict.killed_by),
                             "прогін договорює цілі, а не спиняється на першій "
                             "червоній: %s" % verdict.killed_by)


class WarmTest(unittest.TestCase):
    """Прогрів перших базових прогонів — та сама робота, але без черги (#1313).

    Замок ключа робить базовий прогін один на ключ; той, кому ключ потрібен
    другим, СТОЇТЬ і чекає, і це час воркера, а не робота. Прогрів знімає чергу,
    не змінюючи ні ключів, ні вердикту. Піниться тут саме те, що прогріваються
    ПЕРШІ фази — дешеві, — а не останні: `phases[-1]` прогріло б повні набори
    модулів, і зрізання тихо вернулося б разом із секундами.
    """

    def test_the_warmed_baselines_are_the_first_phases_of_every_site(self):
        # Свідок справжній — у нього обидві фази, і повна більша за вузьку;
        # `plan()` тут не потрібен: питання про ключі, а не про місця.
        witness = REPO_ROOT / "scripts" / "tests" / "test_mutate.py"
        site = v.Site(field=v.FIELDS_BY_NAME["errors"], kind="правило вердикту",
                      path=Path("scripts/mutate.sh"), line=1, start=0, end=0,
                      original="", replacement="")
        witnesses = {site.path: [witness]}

        phases = v.witness_phases(witnesses[site.path], site.field.name)
        self.assertEqual([v.NARROW_PHASE, v.FULL_PHASE],
                         [phase for _module, phase, _targets in phases])

        warm = v.first_phase_baselines([site], witnesses)

        # Прогрівається ПЕРША фаза — і тільки вона: ключ останньої (повний набір
        # модуля) тут означав би, що прогрів рахує дороге замість дешевого.
        self.assertEqual({(witness.name, tuple(phases[0][2]))}, set(warm))
        self.assertNotIn((witness.name, tuple(phases[-1][2])), warm)


class FailClosedTest(unittest.TestCase):
    """Невідомість — окремий код, і жоден її випадок не стає дозволом."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.base = Path(self._tmp.name)

    def root(self, name: str = "pinned") -> Path:
        directory = Path(tempfile.mkdtemp(dir=self.base)) / "root"
        shutil.copytree(FIXTURES / name / "scripts", directory / "scripts")
        return directory

    def witness(self, root: Path, text: str) -> None:
        (root / "scripts" / "tests" / "test_gate.py").write_text(text, encoding="utf-8")

    def run_main(self, root: Path, *arguments: str):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = v.main(["--root", str(root), *arguments])
        return status, out.getvalue(), err.getvalue()

    def test_a_field_with_no_site_is_unknown_not_a_pass(self):
        root = self.root()
        tool = root / "scripts" / "mutate.sh"
        tool.write_text(
            "\n".join(line for line in tool.read_text(encoding="utf-8").splitlines()
                      if "skipped" not in line) + "\n",
            encoding="utf-8",
        )

        status, out, err = self.run_main(root, "--field", "skipped")

        self.assertEqual(2, status, out)
        self.assertIn("поле skipped не знайдено", err)

    def test_an_unknown_field_is_refused(self):
        status, out, err = self.run_main(self.root(), "--field", "skiped")

        self.assertEqual(2, status, out)
        self.assertIn("немає серед полів", err)

    def test_a_field_that_no_longer_decides_anything_is_unknown(self):
        """Поле, яке ніде не вирішує, не має проходити зеленим (огляд #1263).

        Досить перенести рішення на іншу змінну — прибрати `|| $errors -gt 0` —
        і мутант на кожному місці, що лишилося, виживе: поле їде в запис, а
        вердикт від нього вже не залежить. Механізм мусить відмовити на самому
        плані, а не сказати «усі місця вбиті».
        """
        root = self.root()
        tool = root / "scripts" / "mutate.sh"
        tool.write_text(
            tool.read_text(encoding="utf-8").replace(
                "elif [[ $failures -gt 0 || $errors -gt 0 ]]; then",
                "elif [[ $failures -gt 0 ]]; then",
            ),
            encoding="utf-8",
        )

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("жодного «правило вердикту»", err)
        self.assertIn("errors", err)

    def test_the_list_refuses_an_unknown_field_instead_of_answering_silently(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = v.main(["--root", str(FIXTURES / "pinned"), "--field", "skiped", "--list"])

        self.assertEqual(2, status, out.getvalue())
        self.assertIn("немає серед полів", err.getvalue())

    def test_a_worker_that_cannot_copy_the_tree_is_unknown_not_a_crash(self):
        """Диск, права, ліміти: невдача воркера — це код 2, а не трейсбек."""
        root = self.root()

        with mock.patch.object(v.shutil, "copytree", side_effect=OSError("no space left")):
            report = v.check(root, TESTS_DIR, only=["errors"])

        self.assertEqual([], report.verdicts)
        self.assertTrue(report.blocked, "невдача копіювання мусить бути названа")
        self.assertIn("перевірити не вдалося", report.blocked[0])

    def test_a_marker_that_cannot_be_written_is_unknown_and_leaves_no_carrier(self):
        """Збій маркера — відмова (код 2) і жодної теки-носія без власника (#1270).

        Маркер — та сама невдача, що й тимчасова тека: ENOSPC/EDQUOT/EROFS.
        Механізм мусить назвати її відмовою, а не трейсбеком, і не лишити по собі
        теку, яку наступний прогін прибере за часом як сміття: сміття, проти якого
        механізм і заведений, він же й створив би. У порожній формі цю гілку пінить
        `test_empty_shape_mutants.TempBudgetTest`
        `.test_a_marker_that_cannot_be_written_is_unknown_and_leaves_no_carrier`; у
        полях її не пінив ніхто (огляд #1275) — знятий `try/except OSError` навколо
        `Runner` лишався б непоміченим, а без прибирання теки-носія сусідній прогін
        прибрав би її аж за пільговий час.
        """
        elsewhere = Path(self._tmp.name) / "elsewhere"
        elsewhere.mkdir()

        with mock.patch.object(v.tmp_budget, "mark",
                               side_effect=OSError("no space left")), \
                mock.patch.dict(os.environ,
                                {v.tmp_budget.RELOCATE_VARIABLE: str(elsewhere)}):
            status, out, err = self.run_main(self.root(), "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("не створити", err)
        self.assertIn("no space left", err)
        self.assertEqual([], sorted(path.name for path in elsewhere.iterdir()),
                         "невдалий маркер не має лишати теку-носій без власника")

    def test_a_tool_without_a_witness_is_refused(self):
        root = self.root()
        (root / "scripts" / "tests" / "test_gate.py").unlink()

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("не називає", err)
        self.assertIn("нема кому ловити", err)

    def test_a_red_witness_is_unknown_not_a_killed_mutant(self):
        root = self.root()
        self.witness(root, RED_WITNESS)

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("червоний БЕЗ мутації", err)
        self.assertNotIn("не пінить", err)

    def test_a_witness_that_never_finishes_is_unknown(self):
        root = self.root()
        self.witness(root, HANGING_WITNESS)

        with mock.patch.dict(os.environ, {"SLUKHAYKA_VERDICT_FIELD_TIMEOUT": "1"}):
            status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        # Саме «не дав вердикту», а не будь-яка згадка таймауту: червоний набір
        # теж приносить у повідомлення вивід прогону, і різниця між «набір
        # зламаний» та «набір не дійшов до кінця» — це і є перевірка.
        self.assertIn("не дав вердикту", err)
        self.assertIn("не завершився", err)
        self.assertNotIn("червоний БЕЗ мутації", err)

    def test_a_tool_that_is_already_broken_is_unknown(self):
        root = self.root()
        tool = root / "scripts" / "mutate.sh"
        tool.write_text(tool.read_text(encoding="utf-8") + "\nif [[ -z $tests ]\n",
                        encoding="utf-8")

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("bash -n", err)

    def test_a_missing_tool_part_is_unknown(self):
        root = self.root()
        (root / "scripts" / "mutation_trace.py").unlink()

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("якого немає", err)

    def test_an_unparsable_tool_file_is_unknown(self):
        root = self.root()
        (root / "scripts" / "mutation_trace.py").write_text("def broken(:\n", encoding="utf-8")

        status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("не розібрано", err)

    def test_the_mechanism_refuses_to_run_inside_itself(self):
        root = self.root()

        with mock.patch.dict(os.environ, {v.RECURSION_MARKER: "1"}):
            report = v.check(root, TESTS_DIR, only=["errors"])

        self.assertEqual([], report.verdicts)
        self.assertEqual(1, len(report.blocked))
        self.assertIn("усередині механізма", report.blocked[0])

    def test_the_tree_is_not_touched(self):
        """Мутант живе лише в копії: корінь під перевіркою лишається байт-у-байт."""
        root = self.root()
        before = {
            path.relative_to(root): path.read_bytes()
            for path in sorted((root / "scripts").rglob("*")) if path.is_file()
        }

        self.assertEqual([], v.check(root, TESTS_DIR, only=["errors"]).findings)

        after = {
            path.relative_to(root): path.read_bytes()
            for path in sorted((root / "scripts").rglob("*")) if path.is_file()
        }
        self.assertEqual(sorted(before), sorted(after))
        for relative, content in before.items():
            self.assertEqual(content, after[relative], relative)

    def test_the_mechanism_has_no_switch_to_turn_it_off(self):
        """Вимикача немає навмисно: гейт, який можна вимкнути, — не гейт."""
        unpinned = FIXTURES / "unpinned"
        hostile = {
            **os.environ,
            "SKIP_VERDICT_FIELD_MUTANTS": "1",
            "VERDICT_FIELD_MUTANTS": "off",
            "CI": "true",
        }

        skipped = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(unpinned),
             "--field", "errors", "--skip"],
            check=False, capture_output=True, text=True, env=hostile,
        )
        refused = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(unpinned), "--field", "errors"],
            check=False, capture_output=True, text=True, env=hostile,
        )

        self.assertEqual(2, skipped.returncode, skipped.stderr)
        self.assertEqual(1, refused.returncode, refused.stdout)
        self.assertIn("не пінить жоден тест", refused.stderr)


class EnvironmentTest(unittest.TestCase):
    """Фікстура не залежить від середовища, яке дісталося їй у спадок.

    CI-раннер приносить у процес власні змінні, і серед них може бути будь-що:
    експортована функція затіняє команду (`BASH_FUNC_dirname%%`), `repo_root`
    підставного інструмента стає `/`, `python3` не знаходить
    `mutation_trace.py` — і виходить із кодом 2 при незмінному файлі. Саме так
    цей модуль і почервонів у CI: локально 298 тестів зелені, у CI — шість
    падінь із `CalledProcessError: … exit status 2` на кожному запуску
    підставного інструмента.

    Тому свідок фікстури задає оточення інструмента сам (свіжим словником, як
    `scripts/tests/test_mutate.py`), а цей тест пінить саме це: отруєне
    успадковане оточення не мусить доходити до інструмента.
    """

    HOSTILE = {
        "BASH_FUNC_dirname%%": "() { return 1; }",
        "BASH_FUNC_pwd%%": "() { return 1; }",
        "BASH_FUNC_cd%%": "() { return 1; }",
        "BASH_FUNC_python3%%": "() { return 2; }",
        "BASH_ENV": "/nonexistent/rc",
        "CDPATH": "/tmp",
        "PYTHONPATH": "/nonexistent",
    }

    def test_a_poisoned_inherited_environment_does_not_reach_the_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            shutil.copytree(FIXTURES / "pinned" / "scripts", root / "scripts")

            with mock.patch.dict(os.environ, self.HOSTILE):
                report = v.check(root, TESTS_DIR, only=["errors"])

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.findings)
        self.assertEqual(4, len(report.killed))


class TempBudgetTest(unittest.TestCase):
    """Тимчасові дерева (#1271): спершу відмова, потім копіювання.

    Механізм будує копію дерева на кожен мутант. Коли тимчасової ФС бракує, він
    мусить відмовити (код 2, з числами) ДО копіювання, а не заповнити ФС: її
    вичерпання валить кожен запуск процесу в середовищі — `bash` не стартує навіть
    на `true`, `ripgrep` не запускається, і робота виглядає як «зламалися
    інструменти», а не як вичерпана квота. Тому кожен тест тут ловить не лише
    повідомлення, а й те, що копіювання **не почалося**.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def pinned(self) -> Path:
        root = self.root / "root"
        shutil.copytree(FIXTURES / "pinned" / "scripts", root / "scripts")
        return root

    def run_main(self, root: Path, *arguments: str):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = v.main(["--root", str(root), *arguments])
        return status, out.getvalue(), err.getvalue()

    def no_copy(self):
        return mock.patch.object(
            v.shutil, "copytree",
            side_effect=AssertionError("копіювання почалося попри відмову"),
        )

    def test_no_room_is_refused_before_the_copy_with_both_numbers(self):
        root = self.pinned()
        needed = v.tmp_budget.mib(v.tmp_budget.requirement(v.copy_bytes(root)))

        with self.no_copy(), mock.patch.object(
            v.tmp_budget, "disk_free_bytes", return_value=1024
        ):
            status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("FAIL", err)
        self.assertIn(needed, err)          # скільки треба
        self.assertIn("0.0 MiB", err)       # скільки є
        self.assertIn("Копіювання не почато", err)
        self.assertIn(v.tmp_budget.RELOCATE_VARIABLE, err)
        self.assertNotIn("не пінить", err, "це відмова середовища, а не живий мутант")

    def test_a_small_ram_tmpfs_is_refused_loudly(self):
        """Маленький tmpfs у RAM — відмова, а не тихе перенаправлення."""
        root = self.pinned()

        with self.no_copy(), mock.patch.object(
            v.tmp_budget, "ram_backed", return_value=True
        ), mock.patch.object(
            v.tmp_budget, "filesystem_total_bytes", return_value=64 * 1048576
        ):
            status, out, err = self.run_main(root, "--field", "errors")

        self.assertEqual(2, status, out)
        self.assertIn("tmpfs", err)
        self.assertIn("64.0 MiB", err)
        self.assertIn("TMPDIR", err)
        self.assertIn(v.tmp_budget.RELOCATE_VARIABLE, err)

    def test_stale_trees_of_previous_crashes_are_reclaimed_loudly(self):
        """Після `EDQUOT` лишається недороблене дерево, і воно добиває наступний прогін."""
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        stale = elsewhere / "slukhayka-field-stale"
        stale.mkdir()
        (stale / "payload").write_bytes(b"p" * 4096)
        os.utime(stale, (1, 1))
        live = elsewhere / "slukhayka-field-live"
        live.mkdir()
        (live / v.tmp_budget.OWNER_MARKER).write_text("%d\n" % os.getpid(), encoding="utf-8")

        with mock.patch.dict(os.environ, {v.tmp_budget.RELOCATE_VARIABLE: str(elsewhere)}):
            status, out, err = self.run_main(self.pinned(), "--field", "errors")

        self.assertEqual(0, status, err)
        self.assertFalse(stale.exists(), "застаріле дерево мусить бути прибране")
        self.assertTrue(live.exists(), "дерево живого прогону чіпати не можна")
        self.assertIn("прибрано застаріле тимчасове дерево", err)
        self.assertIn(str(stale), err)
        self.assertIn(str(elsewhere), err, "перенесення мусить бути назване вголос")
        self.assertEqual([], list(elsewhere.glob("slukhayka-field-*/root")),
                         "дерева прогону мусять бути прибрані разом із коренем")

    def test_the_immutable_part_of_the_tree_is_linked_not_copied(self):
        """Важіль обсягу: копіюється лише `scripts/`, решта — симлінки.

        Виміряно на дереві `d6c35cdf`: повна копія — 76.2 MiB і 0.32 с на кожне з 38
        дерев, тобто 38 × 76.2 MiB = 2.8 GiB на гейт; копія `scripts/` — 1.4 MiB і
        0.01 с. Тест тримає саме відношення й те, що симлінки не стали копіями.
        """
        (self.root / "tree").mkdir()
        work = v._roots(REPO_ROOT, self.root / "tree")

        copied = v.copy_bytes(REPO_ROOT)
        naive = sum(v.tmp_budget.tree_bytes(entry)
                    for entry in REPO_ROOT.iterdir()
                    if entry.name not in v.SKIP_DIRS | {v.COPY_ENTRY})
        self.assertLess(copied, 8 * 1048576, "копіювати `scripts/` цілком — це межа")
        self.assertGreater(naive, 10 * copied, "решта дерева мусить лишатися симлінками")
        self.assertFalse((work / v.COPY_ENTRY).is_symlink(), "`scripts/` — справжня копія")
        for entry in REPO_ROOT.iterdir():
            if entry.name in v.SKIP_DIRS | {v.COPY_ENTRY}:
                continue
            with self.subTest(entry=entry.name):
                self.assertTrue((work / entry.name).is_symlink(),
                                "незмінна частина дерева не копіюється")

    def test_a_run_releases_its_tree_instead_of_keeping_every_one(self):
        """Пік — це `--jobs` дерев, а не всі 38: інакше 38 копій живуть до кінця прогону.

        Корінь тут — фікстура, а не репозиторій: твердження про звільнення дерева
        від кореня не залежить, а випадок чутливості жене цей тест двічі.
        """
        root = FIXTURES / "pinned"
        runner = v.Runner(root, TESTS_DIR)
        self.addCleanup(runner.cleanup)
        module = root / "scripts" / "tests" / "test_gate.py"
        targets = ["test_gate.GateTest.test_a_witness_that_raises"]
        site = v.plan(root, TESTS_DIR, only=["errors"])[0][0]

        with mock.patch.object(v, "_run", return_value=(0, "")):
            baseline = runner.baseline(module, targets)
            runner.mutant(site, module, targets, [])

        self.assertEqual("", baseline)
        self.assertEqual(
            [v.tmp_budget.OWNER_MARKER],
            sorted(path.name for path in runner._temporary.iterdir()),
            "дерево прогону мусить бути звільнене одразу після нього, лишається "
            "тільки маркер власника",
        )


class MechanismCostTest(unittest.TestCase):
    """Ціна механізму (#1266): базові прогони різних ключів не стоять у черзі.

    Це властивість про ЧАС, а не про те, що перевіряється, тож вона піниться без
    секундоміра — зустріччю двох базових прогонів у бар'єрі. Спільний замок на
    весь прогін коштував 35 % простою пулу на 4 ядрах: поки один ключ рахувався,
    решта потоків чекали, і 38 прогонів механізму йшли ~44 с замість ~36 с.
    """

    def test_baselines_of_different_keys_do_not_wait_for_each_other(self):
        runner = v.Runner(REPO_ROOT, TESTS_DIR)
        self.addCleanup(runner.cleanup)
        together = threading.Barrier(2, timeout=5)
        met = []

        def fake_run(work, tests_dir, targets):
            try:
                together.wait()
            except threading.BrokenBarrierError:
                # Спільний замок: другий прогін до `_run` не доходить, поки
                # перший не скінчиться, — і бар'єр розсипається за таймаутом.
                return 0, ""
            met.append(targets)
            return 0, ""

        keys = (
            (TEST_MUTATE, ["test_mutate.MutationRunTest.test_one"]),
            (TEST_MUTATE_PYTHON, ["test_mutate_python.PythonMutationRunTest.test_two"]),
        )

        with mock.patch.object(v, "_run", fake_run):
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(runner.baseline, module, targets)
                           for module, targets in keys]
                for future in futures:
                    self.assertEqual("", future.result(), "зелений базовий прогін — зелений")

        self.assertEqual(
            sorted(targets for _module, targets in keys), sorted(met),
            "базові прогони РІЗНИХ ключів мусять іти одночасно, а не чергою",
        )


class PlanTest(unittest.TestCase):
    """Типовий виклик мусить покривати всі поля, а не те, що зручно."""

    def test_the_default_call_covers_every_declared_field(self):
        sites, witnesses, blocked = v.plan(REPO_ROOT, TESTS_DIR)

        self.assertEqual([], blocked)
        self.assertEqual({field.name for field in v.FIELDS},
                         {site.field.name for site in sites})
        self.assertTrue(all(modules for modules in witnesses.values()),
                        "кожен файл інструмента мусить мати свідка")

    def test_a_narrowed_call_covers_only_the_named_field(self):
        sites, _witnesses, blocked = v.plan(REPO_ROOT, TESTS_DIR, only=["errors"])

        self.assertEqual([], blocked)
        self.assertEqual({"errors"}, {site.field.name for site in sites})

    def test_the_refusal_names_the_witness_that_would_have_to_hold_it(self):
        report = v.check(FIXTURES / "unpinned", TESTS_DIR, only=["errors"])

        for finding in report.findings:
            self.assertIn("test_gate.py", finding)
            self.assertIn("додай свідка", finding)


class RepositoryTest(unittest.TestCase):
    """Механізм по справжньому репозиторію: зелений і не порожній.

    Порожній зелений («місць не знайдено») теж був би зеленим, тож тест вимагає,
    щоб кожне оголошене поле було назване вбитим — і саме по кожному з його місць.
    """

    @classmethod
    def setUpClass(cls):
        cls.out, cls.err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(cls.out), contextlib.redirect_stderr(cls.err):
            cls.status = v.main([])
        cls.printed = cls.out.getvalue()

    def test_the_repository_passes_with_every_field_witnessed(self):
        self.assertEqual(0, self.status, self.err.getvalue())

        sites, _witnesses, blocked = v.plan(REPO_ROOT, TESTS_DIR)
        self.assertEqual([], blocked)
        killed = [line for line in self.printed.splitlines() if "убита" in line]
        self.assertEqual(len(sites), len(killed))
        for site in sites:
            marker = "%s:%d" % (site.path, site.line)
            self.assertTrue(
                any(marker in line and "убита" in line for line in killed),
                "%s немає серед убитих:\n%s" % (marker, self.printed),
            )

    def test_every_declared_field_has_its_sites_killed(self):
        for field in v.FIELDS:
            with self.subTest(field=field.name):
                self.assertIn("%s (" % field.name, self.printed)

    def test_the_historical_hole_still_reads_as_a_refusal(self):
        """Той самий механізм на старому стані мусить відмовити (доказ — у тікеті).

        Фікстура тут — не історія, а її форма: покарання за втрачену піну вже
        одного разу не відбулося, тож воно мусить бути піненe.
        """
        report = v.check(FIXTURES / "unpinned", TESTS_DIR, only=["errors"])

        self.assertEqual([], report.blocked)
        self.assertEqual(4, len(report.findings))


class SensitivityTest(unittest.TestCase):
    """Кожна перевірка механізму мусить падати, коли її зняти.

    Знімається по одній: копія механізму з однією правкою їде в підпроцес разом
    із названим тестом цього файлу. `intact` (без правки) мусить бути зелений,
    мутант — червоний: інакше перевірка нічого не тримає.
    """

    CASES = (
        (
            "вимога, щоб мутант убив набір",
            "if red:",
            "if False:  # mutated",
            "test_verdict_field_mutants.HistoricalStateTest"
            ".test_the_pinned_state_kills_every_site",
        ),
        (
            "червоний базовий прогін — невідомість",
            'elif state == "red":',
            'elif False:  # mutated',
            "test_verdict_field_mutants.FailClosedTest"
            ".test_a_red_witness_is_unknown_not_a_killed_mutant",
        ),
        (
            "прогін, який не завершився, — невідомість",
            "if status is None:",
            "if False:  # mutated",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_a_witness_that_never_finishes_is_unknown",
        ),
        (
            "поле без жодного місця — невідомість",
            "        if not places:",
            "        if False:  # mutated",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_a_field_with_no_site_is_unknown_not_a_pass",
        ),
        (
            "інструмент без свідка — відмова",
            "    if not modules:",
            "    if False:  # mutated",
            "test_verdict_field_mutants.FailClosedTest.test_a_tool_without_a_witness_is_refused",
        ),
        (
            "частина інструмента, якої немає, — невідомість",
            "        if not path.is_file():\n            raise FieldError(",
            "        if False:  # mutated\n            raise FieldError(",
            "test_verdict_field_mutants.FailClosedTest.test_a_missing_tool_part_is_unknown",
        ),
        (
            "мутант мусить бути синтаксично цілим",
            "            validate(mutate(source.read_text(encoding=\"utf-8\"), site), site.path)",
            "            None  # mutated",
            "test_verdict_field_mutants.FailClosedTest.test_a_tool_that_is_already_broken_is_unknown",
        ),
        (
            "файл інструмента, який не розібрано, — невідомість",
            "    except SyntaxError as error:\n        raise FieldError(",
            "    except OSError as error:\n        raise FieldError(",
            "test_verdict_field_mutants.FailClosedTest.test_an_unparsable_tool_file_is_unknown",
        ),
        (
            "механізм усередині себе — відмова",
            "    if os.environ.get(RECURSION_MARKER):",
            "    if False:  # mutated",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_the_mechanism_refuses_to_run_inside_itself",
        ),
        (
            "мутант живе лише в копії дерева",
            "            work = _roots(self.root, tmp)\n            target_file = work / site.path",
            "            work = self.root  # mutated\n            target_file = work / site.path",
            "test_verdict_field_mutants.FailClosedTest.test_the_tree_is_not_touched",
        ),
        (
            "типовий виклик покриває всі поля",
            "    names = list(only) if only else [field.name for field in FIELDS]",
            '    names = list(only) if only else ["errors"]  # mutated',
            "test_verdict_field_mutants.PlanTest.test_the_default_call_covers_every_declared_field",
        ),
        (
            "читання поля з артефакту — місце",
            "r'root\\.get\\(\\s*\"%s\"\\s*,\\s*\"0\"\\s*\\)' % re.escape(field.name)",
            "r'(?!x)x'",
            "test_verdict_field_mutants.DiscoveryTest.test_the_artifact_read_is_a_site",
        ),
        (
            "передача поля в запис — місце",
            "r'\"%s=\\$[A-Za-z_][A-Za-z0-9_]*\"' % re.escape(field.name)",
            "r'(?!x)x'",
            "test_verdict_field_mutants.DiscoveryTest.test_the_record_transport_is_a_site",
        ),
        (
            "правило вердикту — місце",
            "    if variable:\n",
            "    if False:  # mutated\n",
            "test_verdict_field_mutants.DiscoveryTest"
            ".test_the_verdict_rule_is_a_site_including_presence_tests",
        ),
        (
            "згадка в рядку detail — не місце",
            'for match in re.finditer(r"^.*\\[\\[.*$", code, re.MULTILINE)',
            'for match in re.finditer(r"^.*$", code, re.MULTILINE)',
            "test_verdict_field_mutants.DiscoveryTest.test_a_mention_in_prose_is_not_a_site",
        ),
        (
            "згадка в коментарі — не місце",
            "    code = _blank_comments(text)",
            "    code = text  # mutated",
            "test_verdict_field_mutants.DiscoveryTest.test_a_mention_in_prose_is_not_a_site",
        ),
        (
            "поле мусить мати місце «правило вердикту»",
            "        elif not any(site.kind == VERDICT_RULE for site in places):",
            "        elif False:  # mutated",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_a_field_that_no_longer_decides_anything_is_unknown",
        ),
        (
            "колонки ast — у байтах, а не в символах",
            '        column = len(lines[lineno - 1].encode("utf-8")[:byte_column].decode("utf-8"))',
            "        column = byte_column  # mutated",
            "test_verdict_field_mutants.DiscoveryTest"
            ".test_a_non_ascii_line_does_not_shift_the_span",
        ),
        (
            "--list відмовляє на невідомому полі",
            "        unknown = [name for name in arguments.field if name not in FIELDS_BY_NAME]",
            "        unknown = []  # mutated",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_the_list_refuses_an_unknown_field_instead_of_answering_silently",
        ),
        (
            "невдача воркера — невідомість, а не трейсбек",
            "                except (OSError, FieldError) as error:\n"
            "                    # Копіювання дерева",
            "                except ZeroDivisionError as error:  # mutated\n"
            "                    # Копіювання дерева",
            "test_verdict_field_mutants.FailClosedTest"
            ".test_a_worker_that_cannot_copy_the_tree_is_unknown_not_a_crash",
        ),
        (
            "мета-тест набору — не свідок поля",
            "if not is_test_class(name) or runs_the_suite(node, programs):",
            "if not is_test_class(name):  # mutated",
            "test_verdict_field_mutants.WitnessTest"
            ".test_a_class_that_runs_the_suite_is_not_a_witness",
        ),
        (
            "клас, що ганяє набір програмою `-c`, — теж не свідок поля",
            '    return any("loadTestsFromNames" in text for text in literals)',
            "    return False  # mutated",
            "test_verdict_field_mutants.WitnessTest"
            ".test_a_program_run_by_name_is_a_suite_run_too",
        ),
        (
            "місце міряється ДО першої копії, а не після",
            "        base = tmp_budget.prepare(tmp_budget.OWN_PREFIXES, copy_bytes(root))",
            "        base = tmp_budget.prepare(tmp_budget.OWN_PREFIXES)  # mutated",
            "test_verdict_field_mutants.TempBudgetTest"
            ".test_no_room_is_refused_before_the_copy_with_both_numbers",
        ),
        (
            "дерево прогону звільняється одразу після нього",
            "        finally:\n            shutil.rmtree(tmp, ignore_errors=True)\n"
            "        state, why = verdict_of_run(status, output)",
            "        finally:\n            pass  # mutated\n"
            "        state, why = verdict_of_run(status, output)",
            "test_verdict_field_mutants.TempBudgetTest"
            ".test_a_run_releases_its_tree_instead_of_keeping_every_one",
        ),
        (
            "атрибут JUnit-артефакту — окремий вид місця",
            "        and parent.args[0].value == ARTIFACT_ELEMENT",
            '        and parent.args[0].value == "not-a-testsuite"  # mutated',
            "test_verdict_field_mutants.DiscoveryTest.test_the_xml_attribute_is_a_site",
        ),
    )
    # Друге джерело правди про розмір `CASES` — див. коментар у тесті.
    EXPECTED_CASES = 25

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.mutants = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_one_test(self, target: str, mutant: Path | None):
        environment = dict(os.environ)
        environment.pop("SLUKHAYKA_VERDICT_FIELDS", None)
        if mutant is not None:
            environment["SLUKHAYKA_VERDICT_FIELDS"] = str(mutant / "verdict_field_mutants.py")
        return subprocess.run(
            [sys.executable, "-m", "unittest", target],
            cwd=REPO_ROOT / "scripts" / "tests",
            check=False, capture_output=True, text=True,
            env=environment, timeout=900,
        )

    def mutant(self, needle: str, replacement: str, index: int) -> Path:
        source = mechanism_text()
        if needle not in source:
            raise AssertionError("мутація %r більше не застосовується" % needle)
        directory = self.mutants / f"mutant-{index}"
        directory.mkdir()
        (directory / "verdict_field_mutants.py").write_text(
            source.replace(needle, replacement), encoding="utf-8"
        )
        return directory

    def test_the_suite_dies_when_a_check_of_the_mechanism_is_mutated(self):
        """Випадки незалежні, тож ідуть одночасно (#1266).

        У кожного свій каталог мутанта й свій підпроцес — спільного в них лише
        те, що вони читають той самий механізм. Черга лишала ядра просто: 12 с із
        136 с джоби `script-tests` — один послідовний блок. Це швидкість, а не
        вимикач: кожен випадок усе одно перевіряється, і жодна перевірка не
        зникає.
        """
        # Оголошене число тут — ДРУГЕ джерело правди, і саме тому воно потрібне
        # (#1266, огляд). `CASES` — це джерело ітерації: перевірка «скільки
        # зібрано» проти нього ж не тримає нічого, бо `CASES = ()` дає порожній
        # `outcomes` і зелений набір — «Ran 1 test ... OK» на наборі, який не
        # виконав жодного випадку. Це та сама діра, яку лікує ця серія: свідок,
        # зелений тому, що не виконався. Число поруч робить червоними і порожній
        # перелік, і зрізаний, і воно навмисно вимагає правки разом із `CASES`:
        # зникнення випадку мусить бути видимим, а не тихим.
        self.assertEqual(self.EXPECTED_CASES, len(self.CASES),
                         "оголошених випадків чутливості мусить бути рівно стільки")

        in_flight = 0
        observed = 0
        lock = threading.Lock()

        def judge(index, case):
            nonlocal in_flight, observed
            with lock:
                in_flight += 1
                observed = max(observed, in_flight)
            try:
                name, needle, replacement, target = case
                try:
                    mutant = self.mutant(needle, replacement, index)
                    intact = self.run_one_test(target, None)
                    mutated = self.run_one_test(target, mutant)
                except Exception as error:  # noqa: BLE001 — звітується під subTest
                    return name, None, None, error
                return name, intact, mutated, None
            finally:
                with lock:
                    in_flight -= 1

        with ThreadPoolExecutor(max_workers=SENSITIVITY_JOBS) as pool:
            outcomes = list(pool.map(lambda pair: judge(*pair), enumerate(self.CASES)))

        # Одночасність не мусить тихо з'їсти випадок: скільки оголошено, стільки
        # й перевірено.
        self.assertEqual(len(self.CASES), len(outcomes),
                         "кожен оголошений випадок мусить бути перевірений")
        if SENSITIVITY_JOBS > 1:
            # І випадки мусять іти одночасно, а не чергою: повернення `for`
            # лишило б набір зеленим, тож черга піниться тим, скільки випадків
            # побувало в польоті разом. На одному ядрі пінити нічого — пул там
            # і буде один.
            self.assertGreater(observed, 1,
                               "випадки мусять іти одночасно, а не чергою")

        for name, intact, mutated, error in outcomes:
            with self.subTest(mutation=name):
                self.assertIsNone(error, error)
                self.assertEqual(0, intact.returncode, intact.stderr)
                self.assertIn("Ran 1 test", intact.stderr)

                self.assertNotEqual(
                    0,
                    mutated.returncode,
                    "мутація %r вижила: жоден тест її не помітив" % name,
                )
                self.assertIn("Ran 1 test", mutated.stderr)
                self.assertIn("FAILED", mutated.stderr)


if __name__ == "__main__":
    unittest.main()
