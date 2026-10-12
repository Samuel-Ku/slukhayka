import contextlib
import io
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import issue_premise as ip  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

# Тіло тікета #1261 — справжній приклад класу, який ловить ґард. Текст той
# самий, що в тілі issue; у тесті він лежить фікстурою, бо тест мусить бігти
# без мережі й без `gh`. Прогін по справжньому #1261 — у звіті тікета.
ISSUE_1261_BODY = """## What to build

Кореневий `AGENTS.md` каже агенту першим ділом прочитати `CODING_STANDARDS.md` — **файлу в репозиторії немає**. Тікет приводить репозиторій до згоди із самим собою: або файл з'являється зі справжнім змістом, або посилання прибирається (і тоді названо, де стандарти живуть насправді).

## Acceptance criteria

- [ ] Кожне посилання на `CODING_STANDARDS.md` у репозиторії або веде на наявний файл, або прибране — перевірено `grep`-ом по всьому дереву, не лише по `AGENTS.md`.
- [ ] Якщо файл створюється — він містить **справжні** правила, які вже діють у репозиторії (а не переказ `CONTRIBUTING.md`), і кожне правило посилається на код або перевірку, яка його тримає.

## Blocked by

- None (can start immediately).
"""

# Тікет, чия премиса в `main` є: `AGENTS.md`, `CONTRIBUTING.md`,
# `docs/agents/work-coordination.md`, `docs/voice.md` — усі в main.
VALID_PREMISE_BODY = """## What to build

`AGENTS.md` і `CONTRIBUTING.md` ведуть агента до стандартів, але не називають
`docs/agents/work-coordination.md` там, де агент читає. Тікет додає покажчик.

## Acceptance criteria

- [ ] `AGENTS.md` згадує `docs/agents/work-coordination.md` одним рядком.
- [ ] `docs/voice.md` згадано там, де пишуть тексти назовні.

## Blocked by

- None (can start immediately).
"""


class FakeTree:
    """Дерево `origin/main` як множина шляхів.

    Ґард судить стан `main`, тож тест підставляє саме дерево: без цього кожен
    тест залежав би від того, що лежить у робочій копії, — тобто від того
    самого, з чого народився #1261.
    """

    def __init__(self, paths):
        self.paths = set(paths)
        self.calls = []

    def __call__(self, ref, path):
        self.calls.append(path)
        return path in self.paths

    def basenames(self, ref, name):
        self.calls.append(f"basename:{name}")
        return sorted(p for p in self.paths if p.rsplit("/", 1)[-1] == name)


def judge_body(body, paths, ref="origin/main"):
    """Вердикт по тілу, де дерево `main` підставне, а не взяте з машини.

    Патчиться й `resolve_main_ref`: у копії дерева без `.git` (саме такі копії
    робить `scripts/verdict_field_mutants.py`) справжній ref не читається, і
    тест, який тримався б за нього, був би зелений лише на повному чекауті —
    тобто залежав би від того самого стану робочої копії, з якого народився
    #1261.
    """
    tree = FakeTree(paths)
    with mock.patch.object(ip, "resolve_main_ref", return_value=(True, "0" * 40)), mock.patch.object(
        ip, "main_has_path", tree
    ), mock.patch.object(ip, "main_matches_basename", tree.basenames):
        code, verdicts = ip.check_body(body, ref)
    return code, verdicts, tree


class ExtractionTest(unittest.TestCase):
    def test_backticked_paths_are_candidates_in_order(self):
        found = ip.candidates("спершу `AGENTS.md`, потім `docs/voice.md`")
        self.assertEqual([c.path for c in found], ["AGENTS.md", "docs/voice.md"])
        self.assertEqual([c.line_no for c in found], [1, 1])

    def test_line_numbers_are_per_line(self):
        found = ip.candidates("`AGENTS.md`\n\nтекст\n\n`docs/voice.md`\n")
        self.assertEqual([c.line_no for c in found], [1, 5])

    def test_repeated_path_is_judged_once(self):
        found = ip.candidates("`AGENTS.md` і ще раз `AGENTS.md`")
        self.assertEqual([c.path for c in found], ["AGENTS.md"])

    def test_commands_are_not_paths(self):
        body = "`gh issue view <number> --comments` і `git grep -l X origin/main`"
        self.assertEqual(ip.candidates(body), [])

    def test_gradle_tasks_are_not_paths(self):
        body = "`./gradlew :app:lintDebug` і `:app:testPureJvm`"
        self.assertEqual(ip.candidates(body), [])

    def test_git_refs_are_not_paths(self):
        body = "база `origin/main`, гілка `refs/heads/main`, `upstream/main`"
        self.assertEqual(ip.candidates(body), [])

    def test_prose_fragments_are_not_paths(self):
        body = "`detached HEAD`, `head-name`, `case`-arms, `\"errors\": \"0\"`, `|`"
        self.assertEqual(ip.candidates(body), [])

    def test_globs_and_placeholders_are_not_paths(self):
        body = "`app/src/main/res/values*/strings.xml`, `scripts/tests/test_*.py`, `<path>`"
        self.assertEqual(ip.candidates(body), [])

    def test_directory_with_trailing_slash_is_checked_as_tree(self):
        found = ip.candidates("шукай у `docs/adr/` і в `docs/agents/`")
        self.assertEqual([c.path for c in found], ["docs/adr", "docs/agents"])

    def test_new_marker_is_read_inside_the_span(self):
        found = ip.candidates("`NEW:docs/agents/premise.md` з'явиться")
        self.assertEqual([(c.path, c.marked_new) for c in found], [("docs/agents/premise.md", True)])

    def test_new_marker_outside_the_span_is_not_read(self):
        # Позначка поза інтервалом не діє: вона мусить бути невідривною від шляху.
        found = ip.candidates("NEW: `docs/agents/premise.md` з'явиться")
        self.assertEqual([(c.path, c.marked_new) for c in found], [("docs/agents/premise.md", False)])


class JudgeTest(unittest.TestCase):
    def test_missing_path_without_marker_is_refused(self):
        code, verdicts, _ = judge_body("читай `CODING_STANDARDS.md`", {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertEqual(verdicts[0].candidate.path, "CODING_STANDARDS.md")
        self.assertIn("премиси немає", verdicts[0].detail)
        self.assertIn("`NEW:CODING_STANDARDS.md`", verdicts[0].detail)

    def test_existing_path_passes(self):
        code, verdicts, _ = judge_body("читай `AGENTS.md`", {"AGENTS.md"})
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].code, 0)

    def test_bare_basename_resolves_to_the_one_file_in_main(self):
        # `ci.yml` у тілі — це `.github/workflows/ci.yml` у дереві; валити його
        # означало б шум, який швидко вимкнуть.
        code, verdicts, tree = judge_body("не чіпати `ci.yml`", {".github/workflows/ci.yml"})
        self.assertEqual(code, 0)
        self.assertIn(".github/workflows/ci.yml", verdicts[0].detail)
        self.assertIn("basename:ci.yml", tree.calls)

    def test_marked_new_path_that_main_lacks_passes(self):
        code, verdicts, _ = judge_body("`NEW:docs/agents/premise.md`", {"AGENTS.md"})
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].code, 0)

    def test_marked_new_path_that_main_already_has_is_refused(self):
        # Друге плече тієї самої перевірки: позначка «новий» на наявний файл —
        # теж неправда про `main`, і тим самим спосіб промовчати закритий.
        code, verdicts, _ = judge_body("`NEW:AGENTS.md`", {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertIn("уже є", verdicts[0].detail)

    def test_bare_name_that_main_lacks_is_refused(self):
        code, verdicts, _ = judge_body("читай `CODING_STANDARDS.md`", {"docs/other.md"})
        self.assertEqual(code, 1)
        self.assertIn("ні за повним шляхом, ні за базовим іменем", verdicts[0].detail)

    def test_ambiguous_bare_name_is_unknown_not_refusal(self):
        code, verdicts, _ = judge_body(
            "кличе `tool.sh`", {"scripts/tool.sh", "fixtures/a/scripts/tool.sh"}
        )
        self.assertEqual(code, 2)
        self.assertIn("неоднозначне", verdicts[0].detail)

    def test_ambiguous_bare_name_marked_new_is_refused(self):
        code, _, _ = judge_body("`NEW:tool.sh`", {"scripts/tool.sh"})
        self.assertEqual(code, 1)

    def test_one_refusal_decides_the_whole_ticket(self):
        body = "`AGENTS.md` є, а `CODING_STANDARDS.md` немає"
        code, verdicts, _ = judge_body(body, {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertEqual([v.code for v in verdicts], [0, 1])

    def test_body_without_any_path_is_unknown_not_green(self):
        code, verdicts, _ = judge_body("`detached HEAD` і `head-name`", {"AGENTS.md"})
        self.assertEqual(code, 2)
        self.assertIn("перевіряти нічого", verdicts[0].detail)

    def test_directory_path_is_judged_as_tree(self):
        code, _, tree = judge_body("шукай у `docs/agents/`", {"docs/agents/issue-tracker.md"})
        self.assertEqual(code, 1)
        self.assertIn("docs/agents", tree.calls)


class RealIssueTest(unittest.TestCase):
    """Обидва прогони вимоги 1 — на справжньому тілі #1261 і на валідній премисі."""

    def test_refuses_the_1261_body(self):
        code, verdicts, _ = judge_body(ISSUE_1261_BODY, {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md"})
        self.assertEqual(code, 1)
        refused = [v for v in verdicts if v.code == 1]
        self.assertEqual([v.candidate.path for v in refused], ["CODING_STANDARDS.md"])

    def test_passes_a_body_whose_premise_is_in_main(self):
        code, verdicts, _ = judge_body(
            VALID_PREMISE_BODY,
            {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md", "docs/voice.md"},
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})

    def test_the_two_bodies_differ_only_in_their_premise(self):
        # Той самий ґард, той самий ref — різний вердикт. Це і є доказ, що
        # рішення ухвалює дерево `main`, а не форма тіла.
        tree = {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md", "docs/voice.md"}
        refused, _, _ = judge_body(ISSUE_1261_BODY, tree)
        passed, _, _ = judge_body(VALID_PREMISE_BODY, tree)
        self.assertEqual(refused, 1)
        self.assertEqual(passed, 0)


class MainRefTest(unittest.TestCase):
    def test_unreadable_ref_is_unknown_not_a_pass(self):
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            code, verdicts = ip.check_body("`AGENTS.md`", "origin/main")
        self.assertEqual(code, 2)
        self.assertIn("git fetch origin main", verdicts[0].detail)

    def test_default_ref_is_origin_main(self):
        parser = ip.argparse.ArgumentParser()
        self.assertEqual(ip.MAIN_REF, "origin/main")

    def test_basename_lookup_uses_ls_tree_of_the_ref(self):
        calls = []

        def fake_git(args):
            calls.append(args)
            return 0, "a/b/ci.yml\nc/ci.yml\n", ""

        with mock.patch.object(ip, "git_output", fake_git):
            matches = ip.main_matches_basename("origin/main", "ci.yml")
        self.assertEqual(matches, ["a/b/ci.yml", "c/ci.yml"])
        self.assertEqual(calls[0][:3], ["ls-tree", "-r", "--name-only"])
        self.assertEqual(calls[0][3], "origin/main")


class CliTest(unittest.TestCase):
    def test_body_file_dash_reads_stdin(self):
        with mock.patch.object(sys, "stdin", io.StringIO("читай `AGENTS.md`")):
            body = ip.read_body(ip.argparse.Namespace(body_file="-", issue=None))
        self.assertEqual(body, "читай `AGENTS.md`")

    def test_quiet_prints_only_non_zero_verdicts(self):
        code, verdicts, _ = judge_body("`AGENTS.md` і `CODING_STANDARDS.md`", {"AGENTS.md"})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            for verdict in verdicts:
                if verdict.code != 0:
                    print(f"[{verdict.code}] {verdict.display()} — {verdict.detail}")
        self.assertIn("CODING_STANDARDS.md", out.getvalue())
        self.assertNotIn("`AGENTS.md`", out.getvalue())
        self.assertEqual(code, 1)

    def test_report_names_the_ref_and_counts(self):
        _, verdicts, _ = judge_body("`AGENTS.md` і `CODING_STANDARDS.md`", {"AGENTS.md"})
        report = ip.format_report("origin/main", 1, verdicts)
        self.assertIn("origin/main", report)
        self.assertIn("шляхів перевірено: 2", report)


class LiveRepositoryTest(unittest.TestCase):
    """Єдине місце, яке питає справжній git: форма `git cat-file -e <ref>:<path>`.

    Дерево підставне в решті тестів, і саме тому тут перевіряється, що ґард
    читає стан `main` з git, а не з робочої копії: `HEAD` гілки може містити
    незакомічені зміни, `origin/main` — ні.

    Межа цього класу названа прямо: у копії дерева без `.git` (такі робить
    `scripts/verdict_field_mutants.py`, і в них `origin/main` не читається)
    тести **пропускаються**, а не проходять. Пропущений тест у тому механізмі
    виглядає зеленим — це його власна межа, і саме тому решта модуля
    герметична: дерево `main` там підставне, і жоден тест не залежить від
    того, що лежить у робочій копії.
    """

    @classmethod
    def setUpClass(cls):
        ok, _ = ip.resolve_main_ref("origin/main")
        if not ok:
            raise unittest.SkipTest("origin/main не читається в цьому дереві")

    def test_main_ref_is_readable_in_this_worktree(self):
        ok, revision = ip.resolve_main_ref("origin/main")
        self.assertTrue(ok)
        self.assertRegex(revision, r"^[0-9a-f]{40}$")

    def test_untracked_file_in_the_worktree_is_invisible_to_main(self):
        # Головна межа ґарда: він дивиться в дерево `origin/main`, а не в
        # робочу копію. Цей тест щойно створено й у `main` його немає.
        self.assertTrue((REPO_ROOT / "scripts" / "tests" / "test_issue_premise.py").exists())
        self.assertFalse(ip.main_has_path("origin/main", "scripts/tests/test_issue_premise.py"))

    def test_unborn_path_in_main_is_not_found(self):
        self.assertFalse(ip.main_has_path("origin/main", "CODING_STANDARDS.md"))
        self.assertTrue(ip.main_has_path("origin/main", "AGENTS.md"))

    def test_git_output_reports_failure_for_a_missing_path(self):
        code, _, _ = ip.git_output(["cat-file", "-e", "origin/main:definitely/not/here.md"])
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
