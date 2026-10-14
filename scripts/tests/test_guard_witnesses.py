"""Реєстр сторожів і кожна відмова механізму — з прогоном, а не з обіцянки (#1270).

`scripts/guard_witnesses.py` відтворює мутацію кожного записаного сторожа й
вимагає, щоб той почервонів. Тут те саме з другого боку:

  * `RepositoryTest` жене механізм на СПРАВЖНЬОМУ дереві — тим самим викликом, що
    CI: якщо хоч один сторож зелений під своєю мутацією, червоний цей файл;
  * `RegistryTest` пінить сам реєстр: жоден запис не зникає тихо, кожен називає
    ціль, яка резолвиться, і файл, який існує, і жоден не править рядків СВОГО
    тесту;
  * `FixtureTest`/`FindingTest`/`RefusalTest` — підставлене дерево з підставленим
    сторожем: і зелений сторож-свідок, і кожна невідомість (мутація не
    застосовується, базовий прогін червоний, прогін не виконав нічого, мутант не
    розібрано, ціль не резолвиться, механізм усередині себе, порожній реєстр).

Механізм пінить СЕБЕ своїми ж записами (див. `GUARDS` у ньому): перевірка
вердикту має запис, і CI його відтворює. Окремого харнеса чутливості тоді не
треба — і це навмисно: другий механізм для того самого питання розійшовся б із
першим, а питання тут одне.
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
TESTS_DIR = Path("scripts/tests")
MECHANISM = REPO_ROOT / "scripts" / "guard_witnesses.py"
sys.path.insert(0, str(MECHANISM.parent))

import guard_witnesses as w  # noqa: E402

# Той самий перелік, що в реєстрі механізму, — друге джерело правди, і саме тому
# воно потрібне: `GUARDS = ()` дав би зелений набір, який нічого не перевірив.
# Зникнення запису мусить бути видимим, а не тихим.
GUARD_TESTS = (
    "test_ci_job_time.ClaimTest"
    ".test_a_local_figure_without_a_named_load_is_a_finding",
    "test_ci_job_time.ClaimTest"
    ".test_a_word_that_merely_contains_the_mark_is_not_a_label",
    "test_ci_job_time.LocalRunTest"
    ".test_the_local_mode_reports_a_local_measurement_and_not_the_job_time",
    "test_ci_job_time.LimitTest"
    ".test_a_changed_limit_moves_the_number_without_touching_the_mechanism",
    "test_ci_job_time.MeasurementTest"
    ".test_the_relation_floor_reddens_on_the_slowest_run",
    "test_ci_job_time.MeasurementTest"
    ".test_the_window_asks_the_api_for_main_push_runs_only",
    "test_ci_job_time.RefusalTest"
    ".test_a_run_from_another_branch_is_an_unknown_not_a_measurement",
    "test_ci_job_time.RefusalTest.test_a_missing_gh_is_an_unknown_not_a_zero",
    "test_declared_fields.RepositoryTest"
    ".test_every_record_declares_exactly_the_listed_fields",
    "test_doc_numbers.RepositoryTest.test_every_number_derives_on_the_real_tree",
    "test_doc_numbers.RepositoryTest.test_the_cited_commands_all_exist",
    "test_doc_numbers.RepositoryTest"
    ".test_the_enrolled_prices_are_the_recorded_measurement",
    "test_fixture_environments.EnvironmentTest"
    ".test_a_named_run_that_is_skipped_is_not_green",
    "test_fixture_environments.EnvironmentTest"
    ".test_the_guard_sees_only_the_runs_it_is_named",
    "test_guard_witnesses.FindingTest"
    ".test_a_guard_that_stays_green_under_its_mutation_is_a_finding",
    "test_guard_witnesses.RefusalTest"
    ".test_a_mutation_that_edits_the_guards_own_test_is_refused",
    "test_guard_witnesses.RefusalTest"
    ".test_a_mutation_that_no_longer_applies_is_an_unknown",
    "test_issue_premise.ExtractionTest.test_measure_head_exists_because_the_docs_cite_it",
    "test_issue_premise.BareTreeMeasureTest"
    ".test_the_documented_command_counts_the_same_class_the_verdict_makes_ambiguous",
    "test_issue_premise.TreeEvidenceTest"
    ".test_a_bare_name_that_matches_only_a_tree_is_ambiguous",
    "test_issue_premise.TreeEvidenceTest"
    ".test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep",
    "test_named_boundaries.SectionTest"
    ".test_a_boundary_named_in_code_without_a_witness_is_refused",
    "test_named_boundaries.SectionTest"
    ".test_a_closing_mutation_that_edits_the_witness_module_is_refused",
    "test_named_boundaries.SectionTest.test_a_record_without_a_mutation_is_refused",
    "test_mutate.MutationRunTest"
    ".test_a_refused_run_still_reaps_the_scratch_a_sigkill_left",
    "test_suite_time.DecompositionTest"
    ".test_the_module_with_the_slow_setup_is_the_top_consumer",
    "test_test_wrappers.FullSuiteWrapperTest"
    ".test_a_sigkilled_run_is_reaped_by_the_next_run",
    "test_test_wrappers.FullSuiteWrapperTest"
    ".test_an_unavailable_tmpdir_refuses_with_a_named_reason",
    "test_test_wrappers.FullSuiteWrapperTest"
    ".test_a_stale_run_dir_that_cannot_be_removed_does_not_fail_the_run",
    "test_test_wrappers.FullSuiteWrapperTest"
    ".test_two_live_runs_coexist_and_a_sweep_takes_neither",
    "test_tmp_budget.ScratchTest"
    ".test_no_run_dir_prefix_in_the_tree_is_left_outside_the_sweeper",
    "test_named_boundaries.RegistryTest"
    ".test_the_unreadable_boundary_sections_are_the_named_ones",
    "test_test_wrappers.ChangedTestWrapperScratchTest"
    ".test_a_scratch_left_by_a_sigkilled_run_is_reaped_by_the_next_run",
    "test_test_wrappers.ChangedTestWrapperScratchTest"
    ".test_no_scratch_directory_survives_a_run",
    "test_test_wrappers.ChangedTestWrapperScratchTest"
    ".test_the_scratch_of_a_killed_run_names_that_run_as_its_owner",
    "test_tmp_budget.ScratchTest"
    ".test_an_old_scratch_without_a_marker_is_reaped_for_both_prefixes",
    "test_tmp_budget.ScratchTest"
    ".test_the_prefixes_the_scripts_claim_are_the_reaped_ones",
    "test_verdict_field_mutants.FailClosedTest"
    ".test_a_marker_that_cannot_be_written_is_unknown_and_leaves_no_carrier",
    "test_witness_derivation.DerivationTest"
    ".test_every_form_is_accepted_and_runs_nothing",
    "test_witness_derivation.DerivationTest.test_the_same_ast_gives_two_truths",
)
EXPECTED_GUARDS = len(GUARD_TESTS)

# Підставлене дерево: субʼєкт, який сторож тримає, і три сторожі — той, що бачить
# зміну, той, що червоний завжди, і той, що не виконується.
SUBJECT = "ANSWER = 42\nOTHER = 1\n"

WITNESS_MODULE = '''"""Підставлений сторож: тримає СТРУКТУРУ субʼєкта, а не підрядок."""
import ast
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SUBJECT = ROOT / "scripts" / "subject.py"


def answer():
    tree = ast.parse(SUBJECT.read_text(encoding="utf-8"))
    return [node.value.value for node in ast.walk(tree)
            if isinstance(node, ast.Assign)
            and getattr(node.targets[0], "id", "") == "ANSWER"]


class WitnessTest(unittest.TestCase):
    def test_the_subject_still_answers_42(self):
        self.assertEqual([42], answer())


class RedTest(unittest.TestCase):
    def test_always_red(self):
        self.fail("зламаний сторож")


class SkippedTest(unittest.TestCase):
    @unittest.skip("сторож, який не виконується")
    def test_never_runs(self):
        pass
'''


def edit(file: str, find: str, replace: str) -> w.Edit:
    return w.Edit(file=file, find=find, replace=replace)


# Мутації підставленого дерева: та, що червонить сторожа, і та, до якої йому
# байдуже (нею й піниться «зелений під мутацією — відмова»).
REDDENS = edit("scripts/subject.py", "ANSWER = 42\n", "ANSWER = 0\n")
HARMLESS = edit("scripts/subject.py", "OTHER = 1\n", "OTHER = 2\n")
STALE = edit("scripts/subject.py", "ANSWER = 7\n", "ANSWER = 0\n")
BREAKS_SYNTAX = edit("scripts/subject.py", "ANSWER = 42\n", "ANSWER = (\n")
OWN_TEST_LINE = edit("scripts/tests/test_witness.py",
                     "        self.assertEqual([42], answer())\n",
                     "        self.fail()  # mutated\n")
OWN_HELPER_LINE = edit("scripts/tests/test_witness.py",
                       "    return [node.value.value for node in ast.walk(tree)\n"
                       "            if isinstance(node, ast.Assign)\n"
                       '            and getattr(node.targets[0], "id", "") == "ANSWER"]\n',
                       "    return []  # mutated\n")

WITNESS = "test_witness.WitnessTest.test_the_subject_still_answers_42"
RED = "test_witness.RedTest.test_always_red"
SKIPPED = "test_witness.SkippedTest.test_never_runs"


def entry(test: str, *edits: w.Edit) -> w.Guard:
    return w.Guard(test=test, protects="підставлений сторож у підставленому дереві",
                   edits=tuple(edits))


class FixtureBase(unittest.TestCase):
    """Підставлене дерево, на якому механізм перевіряють, а не вдають."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="guard-witness-")
        self.addCleanup(self._tmp.cleanup)
        self.base = Path(self._tmp.name)
        (self.base / "tmp").mkdir()
        # Дерева механізму йдуть у свою теку: `prepare` прибирає в ній застарілі
        # дерева СВОЇХ префіксів, і робити це в спільному TMPDIR під час набору
        # означало б чіпати чужі прогони.
        patch = mock.patch.dict(os.environ,
                                {w.tmp_budget.RELOCATE_VARIABLE: str(self.base / "tmp")})
        patch.start()
        self.addCleanup(patch.stop)
        self.root = self.tree()

    def tree(self) -> Path:
        """Дерево, у якому механізм має що перевіряти: субʼєкт, сторож і runner."""
        root = Path(tempfile.mkdtemp(dir=self.base)) / "root"
        scripts = root / "scripts"
        (scripts / "tests").mkdir(parents=True)
        (scripts / "subject.py").write_text(SUBJECT, encoding="utf-8")
        (scripts / "tests" / "test_witness.py").write_text(WITNESS_MODULE, encoding="utf-8")
        # Runner — той самий, що в репозиторії: інакше підставлене дерево
        # перевіряло б не той інструмент. Шлях береться з константи механізму, а
        # не з літерала: імені цього файлу тут бути не повинно — за іменем модулі
        # знаходять свідків інші механізми, і зайва згадка робила б цей модуль
        # свідком там, де він нічого не запускає.
        shutil.copy(REPO_ROOT / w.RUNNER, scripts / w.RUNNER.name)
        return root

    def check(self, *entries: w.Guard) -> w.Report:
        return w.check(self.root, TESTS_DIR, entries, jobs=1)


class FixtureTest(FixtureBase):
    """Зелений сторож-свідок і межа правила «не ламай себе»."""

    def test_a_guard_that_reddens_on_its_mutation_is_a_witness(self):
        report = self.check(entry(WITNESS, REDDENS))

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.findings)
        self.assertEqual([WITNESS], [verdict.guard.test for verdict in report.killed])
        self.assertEqual(("scripts/subject.py",), report.killed[0].files)
        self.assertEqual("green", report.killed[0].baseline.state)

    def test_a_mutation_outside_the_test_but_inside_its_module_is_a_witness(self):
        """Межа правила «не ламай себе»: хелпери модуля мутувати можна.

        Правило сторожа живе в хелпері, і саме мутація правила доводить, що тест
        його тримає. Заборона на весь модуль робила б таку мутацію неможливою — а
        вона й потрібна.
        """
        report = self.check(entry(WITNESS, OWN_HELPER_LINE))

        self.assertEqual([], report.blocked)
        self.assertEqual([], report.findings)
        self.assertEqual([WITNESS], [verdict.guard.test for verdict in report.killed])

    def test_the_tree_is_not_touched(self):
        """Мутант живе лише в копії: корінь під перевіркою лишається байт-у-байт."""
        before = {
            path.relative_to(self.root): path.read_bytes()
            for path in sorted((self.root / "scripts").rglob("*")) if path.is_file()
        }

        self.assertEqual([], self.check(entry(WITNESS, REDDENS)).findings)

        after = {
            path.relative_to(self.root): path.read_bytes()
            for path in sorted((self.root / "scripts").rglob("*")) if path.is_file()
        }
        self.assertEqual(sorted(before), sorted(after))
        for relative, content in before.items():
            self.assertEqual(content, after[relative], relative)


class FindingTest(FixtureBase):
    """Сторож, зелений під СВОЄЮ мутацією, — це відмова, а не свідок."""

    def test_a_guard_that_stays_green_under_its_mutation_is_a_finding(self):
        report = self.check(entry(WITNESS, HARMLESS))

        self.assertEqual([], report.blocked)
        self.assertEqual(1, len(report.findings))
        self.assertIn("не червоніє", report.findings[0])
        self.assertIn(WITNESS, report.findings[0])


class RefusalTest(FixtureBase):
    """Кожна невідомість — код 2 і причина, а не тихий дозвіл."""

    def refusal(self, *entries: w.Guard) -> str:
        report = self.check(*entries)
        self.assertEqual([], report.verdicts, report.verdicts)
        self.assertEqual(1, len(report.blocked), report.blocked)
        return report.blocked[0]

    def test_a_mutation_that_no_longer_applies_is_an_unknown(self):
        blocked = self.refusal(entry(WITNESS, STALE))

        self.assertIn("не застосовується", blocked)
        self.assertIn("scripts/subject.py", blocked)

    def test_a_mutation_that_edits_the_guards_own_test_is_refused(self):
        blocked = self.refusal(entry(WITNESS, OWN_TEST_LINE))

        self.assertIn("САМ ТЕСТ сторожа", blocked)

    def test_a_guard_that_is_red_without_a_mutation_is_an_unknown(self):
        blocked = self.refusal(entry(RED, REDDENS))

        self.assertIn("червоний БЕЗ мутації", blocked)

    def test_a_guard_that_executes_nothing_is_an_unknown(self):
        """Скіп — не зелень і тут: прогін, який нічого не виконав, не вердикт."""
        blocked = self.refusal(entry(SKIPPED, REDDENS))

        self.assertIn("виконаного немає", blocked)

    def test_a_mutant_that_does_not_parse_is_an_unknown(self):
        blocked = self.refusal(entry(WITNESS, BREAKS_SYNTAX))

        self.assertIn("не розібрано", blocked)

    def test_a_guard_that_does_not_resolve_is_an_unknown(self):
        blocked = self.refusal(entry("test_witness.WitnessTest.test_absent", REDDENS))

        self.assertIn("не оголошує", blocked)

    def test_a_module_that_does_not_exist_is_an_unknown(self):
        blocked = self.refusal(entry("test_absent.SomeTest.test_x", REDDENS))

        self.assertIn("немає модуля сторожа", blocked)

    def test_an_empty_registry_is_refused_instead_of_passing_silently(self):
        blocked = self.refusal()

        self.assertIn("реєстр порожній", blocked)

    def test_the_mechanism_refuses_its_own_root(self):
        """Прогін у себе не доводить нічого — і це відмова, а не рекурсія."""
        patch = mock.patch.dict(os.environ, {w.RECURSION_MARKER: str(self.root)})
        patch.start()
        self.addCleanup(patch.stop)

        blocked = self.refusal(entry(WITNESS, REDDENS))

        self.assertIn("механізм усередині себе", blocked)

    def test_another_root_under_the_marker_is_not_recursion(self):
        """Фікстура — не той самий корінь: свідок механізму мусить лишатися свідком."""
        patch = mock.patch.dict(os.environ, {w.RECURSION_MARKER: str(self.base / "other")})
        patch.start()
        self.addCleanup(patch.stop)

        report = self.check(entry(WITNESS, REDDENS))

        self.assertEqual([], report.blocked)
        self.assertEqual([WITNESS], [verdict.guard.test for verdict in report.killed])

    def test_an_unknown_guard_name_is_refused_without_running_anything(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = w.main(["--root", str(self.root), "--guard", "test_witness.Nope.test_x"])

        self.assertEqual(2, status, out.getvalue())
        self.assertIn("немає в реєстрі", err.getvalue())

    def test_the_cli_maps_the_verdicts_to_exit_codes(self):
        """Коди виходу — це контракт: 0 свідки, 1 фальшивий сторож, 2 невідомість."""
        codes = {}
        for name, report in (
            ("clean", w.Report(verdicts=[])),
            ("finding", w.Report(verdicts=[w.Verdict(
                guard=entry(WITNESS, HARMLESS), failure="FAIL  підставлений фальшивий")])),
            ("blocked", w.Report(blocked=["FAIL  підставлена невідомість"])),
        ):
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(w, "check", return_value=report), \
                    contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                # Без `--guard`: перевіряється саме відображення вердикту в код
                # виходу, а не звуження; `check` підставлений, тож реєстр не біжить.
                codes[name] = w.main([])

        self.assertEqual({"clean": 0, "finding": 1, "blocked": 2}, codes)

    def test_the_mechanism_has_no_switch_to_turn_it_off(self):
        """Вимикача немає навмисно: гейт, який можна вимкнути, — не гейт."""
        hostile = {**os.environ, "SKIP_GUARD_WITNESSES": "1", "GUARD_WITNESSES": "off",
                   "CI": "true"}
        skipped = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(self.root), "--skip"],
            check=False, capture_output=True, text=True, env=hostile,
        )
        listed = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(self.root), "--list"],
            check=False, capture_output=True, text=True, env=hostile,
        )

        self.assertEqual(2, skipped.returncode, skipped.stderr)
        self.assertEqual(0, listed.returncode, listed.stderr)
        self.assertEqual(EXPECTED_GUARDS,
                         len([line for line in listed.stdout.splitlines()
                              if line.startswith("test_")]))


class RegistryTest(unittest.TestCase):
    """Реєстр — це дані, які читає оглядач: звузити його мовчки не можна."""

    def test_the_registry_is_the_named_set(self):
        self.assertEqual(EXPECTED_GUARDS, len(w.GUARDS),
                         "оголошених сторожів мусить бути рівно стільки")
        self.assertEqual(sorted(GUARD_TESTS), sorted(guard.test for guard in w.GUARDS))

    def test_every_entry_says_what_it_protects(self):
        for guard in w.GUARDS:
            with self.subTest(guard=guard.test):
                self.assertTrue(guard.protects.strip(), "запис мусить назвати, що він тримає")
                self.assertTrue(guard.edits, "запис без мутації нічого не доводить")
                for one in guard.edits:
                    self.assertNotEqual(one.find, one.replace)
                    self.assertTrue((w.ROOT / one.file).is_file(),
                                    "мутація вказує на неіснуючий файл: %s" % one.file)

    def test_every_named_guard_resolves(self):
        for guard in w.GUARDS:
            with self.subTest(guard=guard.test):
                path, span = w.guard_module(w.ROOT, guard.test)
                self.assertTrue(path.is_file())
                self.assertLessEqual(span[0], span[1])

    def test_no_entry_mutates_the_lines_of_its_own_test(self):
        for guard in w.GUARDS:
            path, span = w.guard_module(w.ROOT, guard.test)
            relative = str(path.relative_to(w.ROOT))
            for one in guard.edits:
                if one.file != relative:
                    continue
                with self.subTest(guard=guard.test, find=one.find):
                    self.assertFalse(w.inside_test(path.read_text(encoding="utf-8"),
                                                   one.find, span),
                                     "запис править рядки СВОГО тесту")


class RepositoryTest(unittest.TestCase):
    """Механізм на справжньому дереві: той самий виклик, що CI."""

    @classmethod
    def setUpClass(cls):
        # Один прогін на клас: гейт коштує секунди, і другий такий самий прогін
        # додав би їх до джоби, не додавши жодної нової правди.
        cls.result = subprocess.run(
            [sys.executable, str(MECHANISM), "--root", str(w.ROOT)],
            cwd=str(w.ROOT), check=False, capture_output=True, text=True, timeout=900,
        )

    def test_the_enrolled_guards_redden_on_their_mutations(self):
        self.assertEqual(0, self.result.returncode, self.result.stderr[-4000:])
        self.assertNotIn("FAIL", self.result.stderr)
        killed = [line for line in self.result.stdout.splitlines() if line.startswith("OK    ")]
        self.assertEqual(EXPECTED_GUARDS + 1, len(killed),
                         "на кожен запис мусить бути рядок-свідчення і підсумок:\n%s"
                         % self.result.stdout)
        self.assertIn("сторожів у реєстрі: %d" % EXPECTED_GUARDS, self.result.stdout)

    def test_the_registry_covers_no_more_than_it_says(self):
        """Межа названа вголос: реєстр покриває НАЗВАНИХ сторожів, не всіх.

        Це не перевірка покриття — це пін на те, що межа лишається видимою в
        виводі механізму («решта тестів набору мутацій не має»). Якщо колись
        з'явиться твердження про покриття всього набору, цей рядок почервоніє.
        """
        self.assertIn("мутацій не має", self.result.stdout)
        self.assertNotIn("кожен тест набору", self.result.stdout)

    def test_the_registry_names_its_mutations_readably(self):
        """`--list` називає сторожа і те, що він тримає, — саме це читає оглядач."""
        result = subprocess.run(
            [sys.executable, str(MECHANISM), "--list"],
            check=False, capture_output=True, text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        lines = result.stdout.splitlines()
        for guard in w.GUARDS:
            with self.subTest(guard=guard.test):
                self.assertTrue(any(line.startswith(guard.test) and guard.protects in line
                                    for line in lines),
                                "реєстр мусить називати сторожа і те, що він тримає")
