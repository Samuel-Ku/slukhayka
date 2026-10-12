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
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
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

    def test_the_two_phases_are_narrow_then_full(self):
        module = REPO_ROOT / "scripts" / "tests" / "test_mutate.py"

        phases = v.phases_for(module, "errors")

        self.assertEqual(["вузький набір", "повний модуль"], [phase for phase, _ in phases])
        self.assertLess(len(phases[0][1]), len(phases[1][1]))


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
            '    work = tmp / "root"\n    work.mkdir()',
            '    work = root\n    return work  # mutated',
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
            "if not is_test_class(name) or runs_the_suite(node):",
            "if not is_test_class(name):  # mutated",
            "test_verdict_field_mutants.WitnessTest"
            ".test_a_class_that_runs_the_suite_is_not_a_witness",
        ),
        (
            "атрибут JUnit-артефакту — окремий вид місця",
            "        and parent.args[0].value == ARTIFACT_ELEMENT",
            '        and parent.args[0].value == "not-a-testsuite"  # mutated',
            "test_verdict_field_mutants.DiscoveryTest.test_the_xml_attribute_is_a_site",
        ),
    )

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
        for index, (name, needle, replacement, target) in enumerate(self.CASES):
            with self.subTest(mutation=name):
                mutant = self.mutant(needle, replacement, index)

                intact = self.run_one_test(target, None)
                self.assertEqual(0, intact.returncode, intact.stderr)
                self.assertIn("Ran 1 test", intact.stderr)

                mutated = self.run_one_test(target, mutant)
                self.assertNotEqual(
                    0,
                    mutated.returncode,
                    "мутація %r вижила: жоден тест її не помітив" % name,
                )
                self.assertIn("Ran 1 test", mutated.stderr)
                self.assertIn("FAILED", mutated.stderr)


if __name__ == "__main__":
    unittest.main()
