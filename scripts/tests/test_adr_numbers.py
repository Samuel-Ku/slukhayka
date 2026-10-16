"""Два рішення під одним номером ADR ловить гейт — обома боками (#1227).

`scripts/adr_numbers.py` читає номери з імен файлів і відмовляє, коли один номер
несуть два рішення. Тут — його власні перевірки, і кожна з них має прогін:

  * `RepositoryTest` — на справжньому дереві: жодного повторного номера, і
    читання дерева дає більше за нуль ADR (нуль — не зелень, а «перевіряти нема
    чого»);
  * `TreeTest` — підставлене дерево: унікальні номери проходять, два рішення з
    одним номером — знахідка з ОБОМА шляхами, і код виходу 1;
  * `UnknownTest` — невідомість коштує код 2: немає теки, порожня тека, дивне
    імʼя (кожне назване окремо), пʼять цифр у номері, немає `git` у PATH;
  * `TrialMergeTest` — дерево ПРОБНОГО злиття (`git merge-tree --write-tree`):
    два боки додають різні файли з одним номером — знахідка; конфліктне злиття —
    невідомість, а не «колізій немає»; робоче дерево й індекс не змінюються;
  * `OldWorldTest` — ЧЕРВОНИЙ НА СТАРІЙ ПОВЕДІНЦІ: `git` зливає ту саму пару
    ЧИСТО (нуль маркерів, обидва файли в дереві), тобто до цього гейта світ був
    зелений; гейт на тій самій парі дає 1. Без гейта цей тест падав би на
    першій же перевірці;
  * `OpenPrTest` — read-only режим `gh`: один номер у двох PR (обидва PR і
    перевірені HEAD названі), номер, який PR додає там, де він уже в `main`,
    невідомий `changeType` і жодного запису в `gh` (лише читання);
  * `BoundaryTest` — названі межі перевіряються прогоном: номер у ТЕКСТІ файлу
    гейт не бачить, вікно `--limit` назване в аргументі `gh`, а `--limit 0` —
    відмова.

Зараховані в `scripts/guard_witnesses.py` сторожі — `FindingTest`, `UnknownTest`,
`TrialMergeTest` і `OpenPrTest` (по одному на мутацію). Решта перевіряють сам
механізм і в реєстрі не стоять: реєстр покриває НАЗВАНИХ, а не всіх.
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

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "adr_numbers.py"
sys.path.insert(0, str(MECHANISM.parent))

import adr_numbers  # noqa: E402


# --------------------------------------------------------------------------
# Підставкове дерево й git
# --------------------------------------------------------------------------


def git_environment() -> dict:
    """Оточення git — СВОЄ, а не успадковане (#1265).

    Глобальний конфіг розробника міг би підписати коміт, повісити хук або
    переписати поведінку злиття; у CI раннер приносить своє. Тому обидва рівні
    конфігу вимкнені, а імʼя й пошта задані тут.
    """
    environment = dict(os.environ)
    environment.update({
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_SYSTEM": os.devnull,
        "GIT_AUTHOR_NAME": "adr-fixture",
        "GIT_AUTHOR_EMAIL": "adr-fixture@example.invalid",
        "GIT_COMMITTER_NAME": "adr-fixture",
        "GIT_COMMITTER_EMAIL": "adr-fixture@example.invalid",
        "LC_ALL": "C",
        "LANG": "C",
    })
    return environment


def git(root: Path, *arguments: str) -> subprocess.CompletedProcess:
    executable = shutil.which("git")
    if executable is None:  # pragma: no cover — git є скрізь, де біжить набір
        raise unittest.SkipTest("немає git")
    return subprocess.run(
        [executable, "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
         *arguments],
        cwd=str(root), capture_output=True, text=True, env=git_environment(),
    )


def write_adr(root: Path, name: str, body: str = "---\nstatus: accepted\n---\n"):
    path = root / adr_numbers.ADR_DIR / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


class Fixture(unittest.TestCase):
    """Спільне: тимчасовий корінь і git-репозиторій у ньому."""

    def base(self, prefix: str) -> Path:
        root = Path(tempfile.mkdtemp(prefix=prefix))
        self.addCleanup(shutil.rmtree, root, True)
        return root

    def repository(self, prefix: str) -> Path:
        root = self.base(prefix)
        self.assert_git(root, "init", "-q", "-b", "main", ".")
        self.assert_git(root, "config", "user.name", "adr-fixture")
        self.assert_git(root, "config", "user.email", "adr-fixture@example.invalid")
        return root

    def assert_git(self, root: Path, *arguments: str) -> str:
        result = git(root, *arguments)
        self.assertEqual(
            0, result.returncode,
            "git %s → %d\nstdout=%r\nstderr=%r"
            % (" ".join(arguments), result.returncode, result.stdout, result.stderr),
        )
        return result.stdout

    def commit(self, root: Path, message: str):
        self.assert_git(root, "add", "-A")
        self.assert_git(root, "commit", "-qm", message)

    def run_gate(self, root: Path, *arguments: str) -> tuple[int, str, str]:
        """Прогін механізму В ПРОЦЕСІ, з перехопленим виводом."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = adr_numbers.main(["--root", str(root), *arguments])
        return code, out.getvalue(), err.getvalue()


# --------------------------------------------------------------------------
# Справжнє дерево
# --------------------------------------------------------------------------


class RepositoryTest(Fixture):
    def test_the_real_tree_has_no_duplicate_adr_numbers(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            findings, unknowns = adr_numbers.check(REPO_ROOT)
        self.assertEqual([], unknowns)
        self.assertEqual([], findings, "у дереві два рішення з одним номером:\n%s"
                         % "\n".join(findings))
        self.assertIn("ADR", out.getvalue())

    def test_the_real_tree_has_more_than_zero_adr(self):
        """Нуль ADR — не зелень: порожнє читання нічого не доводить."""
        names = sorted((REPO_ROOT / adr_numbers.ADR_DIR).glob("*.md"))
        self.assertGreater(len(names), 0)
        for name in names:
            self.assertIsNotNone(adr_numbers.ADR_NAME.match(name.name), name.name)

    def test_the_registry_is_the_named_set(self):
        """Форма, тека й списки — дані; звузити їх мовчки видно як правку тесту."""
        self.assertEqual("docs/adr", adr_numbers.ADR_DIR)
        self.assertEqual(r"^(?P<number>\d{4})-(?P<slug>.+\.md)$",
                         adr_numbers.ADR_NAME.pattern)
        self.assertEqual({"ADDED", "RENAMED"}, set(adr_numbers.CLAIMING_CHANGES))
        self.assertEqual({"MODIFIED", "DELETED"}, set(adr_numbers.NON_CLAIMING_CHANGES))
        self.assertEqual(100, adr_numbers.OPEN_PR_LIMIT)
        self.assertEqual(
            {0: 0, 1: 1, 2: 2},
            {adr_numbers.EXIT_OK: 0, adr_numbers.EXIT_FINDING: 1,
             adr_numbers.EXIT_UNKNOWN: 2},
        )


# --------------------------------------------------------------------------
# Дерево робочої копії
# --------------------------------------------------------------------------


class TreeTest(Fixture):
    def test_unique_numbers_pass(self):
        root = self.base("adr-unique-")
        write_adr(root, "0001-one.md")
        write_adr(root, "0002-two.md")
        code, out, err = self.run_gate(root)
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("OK", out)
        self.assertEqual("", err)

    def test_two_files_with_one_number_are_a_finding(self):
        root = self.base("adr-duplicate-")
        write_adr(root, "0062-one-heavy-build-slot.md")
        write_adr(root, "0062-real-catalog-recommendation-eval.md")
        code, out, err = self.run_gate(root)
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("0062", out)
        self.assertIn("0062-one-heavy-build-slot.md", out)
        self.assertIn("0062-real-catalog-recommendation-eval.md", out)
        self.assertEqual("", err)

    def test_a_number_named_by_three_decisions_names_all_three(self):
        root = self.base("adr-triple-")
        for slug in ("a", "b", "c"):
            write_adr(root, "0007-%s.md" % slug)
        code, out, _ = self.run_gate(root)
        self.assertEqual(1, code)
        for slug in ("a", "b", "c"):
            self.assertIn("0007-%s.md" % slug, out)

    def test_the_gate_writes_nothing(self):
        """Гейт лише читає: після прогону жоден файл не змінився й не додався."""
        root = self.base("adr-readonly-")
        write_adr(root, "0001-one.md")
        write_adr(root, "0001-two.md")
        before = sorted(path.name for path in (root / adr_numbers.ADR_DIR).iterdir())
        self.run_gate(root)
        after = sorted(path.name for path in (root / adr_numbers.ADR_DIR).iterdir())
        self.assertEqual(before, after)


# --------------------------------------------------------------------------
# Невідомість
# --------------------------------------------------------------------------


class UnknownTest(Fixture):
    def test_a_missing_adr_directory_is_an_unknown_not_a_clean_tree(self):
        root = self.base("adr-nodir-")
        code, out, err = self.run_gate(root)
        self.assertEqual(2, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("немає теки docs/adr", err)
        self.assertNotIn("OK", out)
        self.assertNotIn("Traceback", err)

    def test_an_empty_adr_directory_is_an_unknown_too(self):
        root = self.base("adr-empty-")
        (root / adr_numbers.ADR_DIR).mkdir(parents=True)
        code, _, err = self.run_gate(root)
        self.assertEqual(2, code)
        self.assertIn("порожня", err)

    def test_every_odd_name_is_named_separately(self):
        root = self.base("adr-odd-")
        write_adr(root, "0061-good.md")
        write_adr(root, "61-short.md")
        write_adr(root, "notes.md")
        (root / adr_numbers.ADR_DIR / "sub").mkdir()
        code, _, err = self.run_gate(root)
        self.assertEqual(2, code)
        for odd in ("61-short.md", "notes.md", "sub/"):
            self.assertIn(odd, err, "дивне імʼя не названо окремо: %s" % odd)

    def test_a_five_digit_prefix_is_an_odd_name_not_a_number(self):
        root = self.base("adr-five-")
        write_adr(root, "10061-x.md")
        write_adr(root, "10061-y.md")
        code, out, err = self.run_gate(root)
        self.assertEqual(2, code, "два «10061» — не два 0061:\n%r" % out)
        self.assertIn("10061-x.md", err)
        self.assertIn("10061-y.md", err)

    def test_a_missing_git_is_an_unknown_not_a_stack(self):
        """Без `git` — код 2 із названою причиною, а не стек."""
        root = self.repository("adr-nogit-")
        write_adr(root, "0001-one.md")
        self.commit(root, "base")
        empty = self.base("adr-nogit-bin-")
        environment = {"PATH": str(empty), "HOME": str(root), "LC_ALL": "C"}
        for arguments, named in ((("--base", "HEAD", "--head", "HEAD"), "немає `git`"),
                                 (("--open-prs",), "немає")):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [sys.executable, str(MECHANISM), "--root", str(root), *arguments],
                    capture_output=True, text=True, check=False, env=environment,
                )
                self.assertEqual(2, result.returncode,
                                 "stdout=%r stderr=%r" % (result.stdout, result.stderr))
                self.assertIn(named, result.stderr)
                self.assertNotIn("Traceback", result.stderr)
                self.assertEqual("", result.stdout)

    def test_a_limit_without_prs_is_a_refusal(self):
        root = self.base("adr-limit-")
        write_adr(root, "0001-one.md")
        code, out, err = self.run_gate(root, "--open-prs", "--limit", "0")
        self.assertEqual(2, code)
        self.assertIn("--limit 0", err)
        self.assertEqual("", out)


# --------------------------------------------------------------------------
# Пробне злиття
# --------------------------------------------------------------------------


class TrialMergeTest(Fixture):
    def colliding_branches(self, prefix: str) -> Path:
        """`main` + дві гілки, кожна додає РІЗНЕ імʼя під одним номером."""
        root = self.repository(prefix)
        write_adr(root, "0001-one.md")
        self.commit(root, "base")
        self.assert_git(root, "checkout", "-qb", "add-x")
        write_adr(root, "0002-x.md")
        self.commit(root, "add-x")
        self.assert_git(root, "checkout", "-q", "main")
        self.assert_git(root, "checkout", "-qb", "add-y")
        write_adr(root, "0002-y.md")
        self.commit(root, "add-y")
        self.assert_git(root, "checkout", "-q", "main")
        return root

    def test_two_branches_adding_one_number_are_a_finding(self):
        root = self.colliding_branches("adr-merge-")
        code, out, err = self.run_gate(root, "--base", "add-x", "--head", "add-y")
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("0002-x.md", out)
        self.assertIn("0002-y.md", out)
        self.assertIn("пробне злиття", out)

    def test_one_branch_against_main_passes(self):
        root = self.colliding_branches("adr-merge-one-")
        code, out, err = self.run_gate(root, "--base", "main", "--head", "add-x")
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("OK", out)

    def test_a_conflicted_trial_merge_is_an_unknown_not_a_clean_tree(self):
        root = self.repository("adr-conflict-")
        write_adr(root, "0001-one.md", "base\n")
        self.commit(root, "base")
        self.assert_git(root, "checkout", "-qb", "left")
        write_adr(root, "0001-one.md", "left\n")
        self.commit(root, "left")
        self.assert_git(root, "checkout", "-q", "main")
        self.assert_git(root, "checkout", "-qb", "right")
        write_adr(root, "0001-one.md", "right\n")
        self.commit(root, "right")
        self.assert_git(root, "checkout", "-q", "main")
        code, out, err = self.run_gate(root, "--base", "left", "--head", "right")
        self.assertEqual(2, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("конфліктне", err)
        self.assertNotIn("OK", out)

    def test_a_deleted_number_replaced_by_another_is_not_a_collision(self):
        """Дерево злиття — справжнє: заміна 0002-старого на 0002-новий — не дубль."""
        root = self.repository("adr-replace-")
        write_adr(root, "0001-one.md")
        write_adr(root, "0002-old.md")
        self.commit(root, "base")
        self.assert_git(root, "checkout", "-qb", "replace")
        self.assert_git(root, "rm", "-q", "docs/adr/0002-old.md")
        write_adr(root, "0002-new.md")
        self.commit(root, "replace")
        self.assert_git(root, "checkout", "-q", "main")
        code, out, err = self.run_gate(root, "--base", "main", "--head", "replace")
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_the_trial_merge_leaves_the_worktree_and_index_untouched(self):
        root = self.colliding_branches("adr-untouched-")
        status = self.assert_git(root, "status", "--porcelain")
        head = self.assert_git(root, "rev-parse", "HEAD")
        index = self.assert_git(root, "write-tree")
        code, _, _ = self.run_gate(root, "--base", "add-x", "--head", "add-y")
        self.assertEqual(1, code)
        self.assertEqual(status, self.assert_git(root, "status", "--porcelain"))
        self.assertEqual(head, self.assert_git(root, "rev-parse", "HEAD"))
        self.assertEqual(index, self.assert_git(root, "write-tree"))


# --------------------------------------------------------------------------
# Старий світ: те, на чому гейт червоний
# --------------------------------------------------------------------------


class OldWorldTest(Fixture):
    def test_before_the_gate_git_merges_the_same_number_cleanly(self):
        """ЧЕРВОНИЙ НА СТАРІЙ ПОВЕДІНЦІ — без гейта цей тест не існував би.

        `git` зливає дві гілки з РІЗНИМИ іменами під одним номером чисто: нуль
        конфліктів, обидва файли в дереві. Це і є старий світ — «все гаразд»,
        яке гейт мусить перетворити на відмову. Якщо гейт прибрати, перша ж
        перевірка (код 1 на тій самій парі) стане зеленою — тобто тест
        падатиме, а не мовчатиме.
        """
        root = TrialMergeTest.colliding_branches(self, "adr-oldworld-")
        self.assert_git(root, "checkout", "-q", "add-x")
        merged = git(root, "merge", "--no-edit", "-m", "old world merge", "add-y")
        self.assertEqual(0, merged.returncode,
                         "git зненацька побачив конфлікт: %r" % merged.stdout)
        self.assertNotIn("CONFLICT", merged.stdout)
        names = sorted(path.name for path in (root / adr_numbers.ADR_DIR).glob("*.md"))
        self.assertEqual(["0001-one.md", "0002-x.md", "0002-y.md"], names)
        code, out, err = self.run_gate(root)
        self.assertEqual(1, code, "гейт не побачив дубля, який git злив чисто\n"
                         "stdout=%r stderr=%r" % (out, err))
        self.assertIn("0002", out)

    def test_the_pair_the_ticket_names_is_exactly_this_shape(self):
        """Форма колізії #1139/#1186: два РІЗНІ імена, один номер."""
        root = self.base("adr-ticket-shape-")
        write_adr(root, "0061-real-catalog-recommendation-eval.md")
        write_adr(root, "0061-local-folder-source-is-a-reference.md")
        code, out, _ = self.run_gate(root)
        self.assertEqual(1, code)
        self.assertIn("0061-real-catalog-recommendation-eval.md", out)
        self.assertIn("0061-local-folder-source-is-a-reference.md", out)


# --------------------------------------------------------------------------
# Read-only режим `gh`
# --------------------------------------------------------------------------


def file_entry(path: str, change_type: str) -> dict:
    return {"path": path, "changeType": change_type, "additions": 1, "deletions": 0}


def recording_gh(payload):
    """Підставковий `gh`: записує аргументи й віддає назване."""
    calls: list[list[str]] = []

    def runner(arguments):
        calls.append(list(arguments))
        return payload

    return runner, calls


class OpenPrTest(Fixture):
    def main_repository(self, prefix: str) -> Path:
        root = self.repository(prefix)
        write_adr(root, "0001-one.md")
        write_adr(root, "0062-one-heavy-build-slot.md")
        self.commit(root, "base")
        return root

    def fake_gh(self, payload):
        return recording_gh(payload)

    def test_the_same_new_number_in_two_open_prs_names_both(self):
        root = self.main_repository("adr-prs-")
        runner, _ = self.fake_gh([
            {"number": 1139, "headRefOid": "a" * 40, "files": [
                file_entry("docs/adr/0061-real-catalog-recommendation-eval.md", "ADDED"),
            ]},
            {"number": 1186, "headRefOid": "b" * 40, "files": [
                file_entry("docs/adr/0061-local-folder-source-is-a-reference.md", "ADDED"),
            ]},
        ])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            findings, unknowns = adr_numbers.check(
                root, open_prs=True, main_ref="main", gh=runner)
        self.assertEqual([], unknowns)
        self.assertEqual(1, len(findings), findings)
        text = findings[0]
        for piece in ("#1139", "#1186", "a" * 40, "b" * 40,
                      "docs/adr/0061-real-catalog-recommendation-eval.md",
                      "docs/adr/0061-local-folder-source-is-a-reference.md"):
            self.assertIn(piece, text)
        self.assertIn("0061", text)

    def test_a_new_number_that_main_already_carries_is_a_finding(self):
        root = self.main_repository("adr-pr-main-")
        runner, _ = self.fake_gh(
            {"number": 1139, "headRefOid": "c" * 40, "files": [
                file_entry("docs/adr/0062-real-catalog-recommendation-eval.md", "ADDED"),
            ]})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            findings, unknowns = adr_numbers.check(
                root, prs=[1139], main_ref="main", gh=runner)
        self.assertEqual([], unknowns)
        self.assertEqual(1, len(findings), findings)
        self.assertIn("0062-one-heavy-build-slot.md", findings[0])
        self.assertIn("c" * 40, findings[0])

    def test_the_gate_only_reads_from_gh(self):
        """Жодного запису: ні `edit`, ні `-X`, ні тіла в `gh`-виклику."""
        root = self.main_repository("adr-gh-readonly-")
        runner, calls = self.fake_gh(
            {"number": 7, "headRefOid": "d" * 40, "files": [
                file_entry("docs/adr/0070-new.md", "ADDED"),
            ]})
        with contextlib.redirect_stdout(io.StringIO()):
            adr_numbers.check(root, prs=[7], main_ref="main", gh=runner)
        self.assertEqual(1, len(calls))
        flat = " ".join(" ".join(call) for call in calls)
        for forbidden in ("edit", "merge", "close", "reopen", "-X", "--method",
                          "POST", "PATCH", "DELETE", "PUT"):
            self.assertNotIn(forbidden, flat)

    def test_a_pr_that_only_modifies_an_existing_adr_claims_no_number(self):
        root = self.main_repository("adr-gh-modified-")
        runner, _ = self.fake_gh(
            {"number": 7, "headRefOid": "e" * 40, "files": [
                file_entry("docs/adr/0062-one-heavy-build-slot.md", "MODIFIED"),
            ]})
        with contextlib.redirect_stdout(io.StringIO()):
            findings, unknowns = adr_numbers.check(
                root, prs=[7], main_ref="main", gh=runner)
        self.assertEqual([], unknowns)
        self.assertEqual([], findings)

    def test_an_unknown_change_type_is_an_unknown_not_a_silent_pass(self):
        root = self.main_repository("adr-gh-changetype-")
        runner, _ = self.fake_gh(
            {"number": 7, "headRefOid": "f" * 40, "files": [
                file_entry("docs/adr/0061-x.md", "COPIED"),
            ]})
        with contextlib.redirect_stdout(io.StringIO()):
            findings, unknowns = adr_numbers.check(
                root, prs=[7], main_ref="main", gh=runner)
        self.assertEqual([], findings)
        self.assertEqual(1, len(unknowns))
        self.assertIn("COPIED", unknowns[0])
        self.assertIn("0061-x.md", unknowns[0])

    def test_a_pr_without_head_is_an_unknown(self):
        root = self.main_repository("adr-gh-nohead-")
        runner, _ = self.fake_gh(
            {"number": 7, "files": [file_entry("docs/adr/0061-x.md", "ADDED")]})
        with contextlib.redirect_stdout(io.StringIO()):
            findings, unknowns = adr_numbers.check(
                root, prs=[7], main_ref="main", gh=runner)
        self.assertEqual([], findings)
        self.assertEqual(1, len(unknowns))
        self.assertIn("headRefOid", unknowns[0])

    def test_gh_answering_something_else_than_a_list_is_an_unknown(self):
        root = self.main_repository("adr-gh-badjson-")
        runner, _ = self.fake_gh({"message": "Not Found"})
        with contextlib.redirect_stdout(io.StringIO()):
            findings, unknowns = adr_numbers.check(
                root, open_prs=True, main_ref="main", gh=runner)
        self.assertEqual([], findings)
        self.assertEqual(1, len(unknowns))
        self.assertIn("не список PR", unknowns[0])

    def test_the_window_is_named_by_the_limit(self):
        root = self.main_repository("adr-gh-window-")
        runner, calls = self.fake_gh([])
        with contextlib.redirect_stdout(io.StringIO()):
            adr_numbers.check(root, open_prs=True, main_ref="main", limit=3, gh=runner)
        self.assertEqual([["pr", "list", "--state", "open", "--limit", "3",
                           "--json", "number,headRefOid,files"]], calls)

    def test_base_and_open_prs_together_are_refused(self):
        root = self.main_repository("adr-gh-both-")
        runner, _ = self.fake_gh([])
        findings, unknowns = adr_numbers.check(
            root, base="main", open_prs=True, main_ref="main", gh=runner)
        self.assertEqual([], findings)
        self.assertEqual(1, len(unknowns))
        self.assertIn("два різні дерева", unknowns[0])


# --------------------------------------------------------------------------
# Межі, названі в коді, — прогоном
# --------------------------------------------------------------------------


class BoundaryTest(Fixture):
    def test_a_number_only_in_the_text_is_not_seen(self):
        """Межа: гейт читає ІМЕНА, а не прозу. Два різні номери в іменах — зелені."""
        root = self.base("adr-text-")
        write_adr(root, "0061-a.md", "---\nstatus: accepted\n---\nADR-0062\n")
        write_adr(root, "0062-b.md", "---\nstatus: accepted\n---\nADR-0061\n")
        code, out, _ = self.run_gate(root)
        self.assertEqual(0, code, "текст у тілі не сміє бути номером файлу:\n%r" % out)
        self.assertIn("OK", out)

    def test_a_head_ref_without_the_directory_is_an_unknown(self):
        root = self.repository("adr-head-nodir-")
        (root / "README.md").write_text("x\n", encoding="utf-8")
        self.commit(root, "no adr dir")
        code, _, err = self.run_gate(root, "--head", "HEAD")
        self.assertEqual(2, code)
        self.assertIn("немає теки docs/adr", err)

    def test_an_unknown_revision_is_an_unknown(self):
        root = self.repository("adr-bad-ref-")
        write_adr(root, "0001-one.md")
        self.commit(root, "base")
        code, _, err = self.run_gate(root, "--head", "no-such-ref")
        self.assertEqual(2, code)
        self.assertNotIn("Traceback", err)

    def test_an_odd_name_in_a_pr_is_an_unknown(self):
        root = self.repository("adr-pr-odd-")
        write_adr(root, "0001-one.md")
        self.commit(root, "base")
        runner, _ = recording_gh(
            {"number": 7, "headRefOid": "a" * 40, "files": [
                file_entry("docs/adr/61-x.md", "ADDED"),
            ]})
        with contextlib.redirect_stdout(io.StringIO()):
            findings, unknowns = adr_numbers.check(
                root, prs=[7], main_ref="main", gh=runner)
        self.assertEqual([], findings)
        self.assertEqual(1, len(unknowns))
        self.assertIn("61-x.md", unknowns[0])


if __name__ == "__main__":
    unittest.main()
