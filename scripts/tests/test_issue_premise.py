import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import issue_premise as ip  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]


def build_repo(base: Path, files: dict[str, str], gitignore: str = "") -> Path:
    """Репозиторій зі **своїм** `origin/main` — у `/tmp`, не в чекауті.

    Той самий прийом, що в `test_mutate.py` і `test_review_pass.py`: тест, який
    питає git, мусить мати власний git-стан, а не позичати його в машини, де
    біжить. `origin/main` створюється як **віддалений** ref (push у bare
    `origin`), бо саме на нього дивиться ґард.
    """
    base.mkdir(parents=True, exist_ok=True)
    remote, repo = base / "origin.git", base / "repo"
    run = lambda *args, cwd: subprocess.run(  # noqa: E731
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    )
    subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    run("config", "user.email", "test@example.com", cwd=repo)
    run("config", "user.name", "Test", cwd=repo)
    for relative, content in files.items():
        target = repo / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    if gitignore:
        (repo / ".gitignore").write_text(gitignore, encoding="utf-8")
    run("add", "-A", cwd=repo)
    run("commit", "-q", "-m", "base", cwd=repo)
    run("remote", "add", "origin", str(remote), cwd=repo)
    run("push", "-q", "origin", "HEAD:refs/heads/main", cwd=repo)
    run("fetch", "-q", "origin", "main", cwd=repo)
    return repo

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
        self.top = frozenset(p.split("/")[0] for p in self.paths)

    def __call__(self, ref, path, repo=None):
        self.calls.append(path)
        return path in self.paths

    def suffixes(self, ref, suffix, repo=None):
        self.calls.append(f"suffix:{suffix}")
        return sorted(p for p in self.paths if p == suffix or p.endswith("/" + suffix))

    def top_level(self, ref, repo):
        self.calls.append(f"top:{ref}")
        return self.top


def judge_body(body, paths, ref="origin/main"):
    """Вердикт по тілу, де дерево `main` підставне, а не взяте з машини.

    Патчиться й `_top_level_entries`: у CI `origin/main` не існує (раннер
    фетчить merge-ref), тож справжній виклик повернув би порожню множину — і
    `docs/agents` прочитався б як проза. Саме це й упало в CI PR #1268:
    локально тест був зелений, бо на машині `origin/main` є.
    """
    tree = FakeTree(paths)
    with mock.patch.object(ip, "resolve_main_ref", return_value=(True, "0" * 40)), mock.patch.object(
        ip, "main_has_path", tree
    ), mock.patch.object(ip, "main_matches_suffix", tree.suffixes), mock.patch.object(
        ip, "_top_level_entries", tree.top_level
    ):
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

    def test_git_internals_are_not_paths(self):
        # Стан операції, а не вміст репозиторію: у `main` їх немає й бути не
        # мусить, тож премисою про наявний файл вони не є (#1247).
        body = "`rebase-merge`, `rebase-apply`, `.git/rebase-merge`, `MERGE_HEAD`"
        self.assertEqual(ip.candidates(body), [])

    def test_slash_shorthand_is_not_a_path(self):
        # `errorLine1/2` — пара імен, не каталог (#1222). Друга половина без
        # літер і без крапки; `values-en/pl` так не виглядає і лишається
        # кандидатом.
        self.assertEqual(ip.candidates("поля `errorLine1/2`"), [])
        self.assertEqual(ip.candidates("каталог `values-en/pl`")[0].path, "values-en/pl")

    def test_branch_names_are_not_paths(self):
        # Справжні назви гілок із тіла #1106. Гілка не мусить бути в `main` —
        # валити за неї означало б валити тікет за назву смуги роботи.
        body = (
            "`release/v1.3.9`, `release/v1.3.10`, `spec-53/wave-1`, "
            "`fix/purge-4read-legacy`, `chore/device-verification`, "
            "`backup/large-screens-900-prerebase`, `feat/ui-polish`"
        )
        self.assertEqual(ip.candidates(body), [])

    def test_docs_paths_are_not_mistaken_for_branches(self):
        # Ціна голови `docs` у списку гілок: `docs/voice.md` і `docs/adr` —
        # справжні шляхи. Перевірка саме на них, а не на формі гілки.
        self.assertEqual(
            [c.path for c in ip.candidates("`docs/voice.md` і `docs/adr`")],
            ["docs/voice.md", "docs/adr"],
        )
        self.assertFalse(ip._is_branch_name("docs/voice.md"))
        self.assertFalse(ip._is_branch_name("docs/adr"))

    def test_test_directories_are_not_mistaken_for_branches(self):
        # Та сама ціна для голів `test`/`tests` (огляд PR #1268): у дереві 931
        # шлях під такими теками, і голова зробила б їх невидимими.
        self.assertEqual(
            [c.path for c in ip.candidates("`scripts/tests/test_x.py` і `app/src/test/java/A.kt`")],
            ["scripts/tests/test_x.py", "app/src/test/java/A.kt"],
        )
        self.assertFalse(ip._is_branch_name("tests/x"))
        self.assertFalse(ip._is_branch_name("test/x"))

    def test_device_paths_are_not_paths(self):
        # `filesDir/local_imports` — тека в застосунку, не в репозиторії
        # (#1163, #1185).
        body = "тека `filesDir/local_imports`, кеш `cacheDir/x`"
        self.assertEqual(ip.candidates(body), [])

    def test_ellipsis_segment_is_not_a_path(self):
        # `app/src/androidTest/...` — «і далі», а не каталог (#764).
        self.assertEqual(ip.candidates("теки `app/src/androidTest/...`"), [])

    def test_bare_extension_is_not_a_path(self):
        # `.png` і `.html` — формат вкладення, не ім'я файлу (#991, #1029).
        self.assertEqual(ip.candidates("вкладення `.png` і `.html`"), [])

    def test_attachment_names_are_not_refused(self):
        # Справжні імена вкладень із #991 і #962: файли існують на машині, у
        # `main` їх немає навмисно. Голе ім'я вкладення — не премиса про дерево.
        code, verdicts, _ = judge_body(
            "скріншоти `live-book-2f-screencap.png`, `crop-2f-live-pill.png`, `18-person.png`",
            {"AGENTS.md"},
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})
        self.assertIn("вкладення", verdicts[0].detail)

    def test_named_path_with_attachment_extension_is_still_checked(self):
        # Лише голе ім'я: названий шлях перевіряється як усі.
        code, _, _ = judge_body("`docs/screens/x.png`", {"AGENTS.md"})
        self.assertEqual(code, 1)

    def test_attachment_name_present_in_main_still_passes(self):
        # Якщо таке ім'я в дереві **є**, воно проходить як звичайний шлях.
        code, verdicts, _ = judge_body("`icon.png`", {"app/src/main/res/icon.png"})
        self.assertEqual(code, 0)
        self.assertIn("icon.png", verdicts[0].detail)

    def test_ignored_paths_are_dropped(self):
        # `.mutations` у `main` немає **навмисно** — такі каталоги живе лише на
        # машині, і «немає в main» про них не є відмовою.
        with mock.patch.object(ip, "_is_ignored", lambda path, repo=None: path in {".mutations", "build"}):
            self.assertEqual(ip.candidates("стан у `.mutations` і в `build`"), [])

    def test_ignore_lookup_is_asked_only_about_surviving_paths(self):
        # `git check-ignore` — процес; платити за нього на кожен інтервал у
        # бектиках означало б платити за прозу.
        asked = []

        def fake_ignored(path, repo=None):
            asked.append(path)
            return False

        with mock.patch.object(ip, "_is_ignored", fake_ignored):
            ip.candidates("`detached HEAD`, `gh issue view 1`, `AGENTS.md`")
        self.assertEqual(asked, ["AGENTS.md"])

    def test_new_marked_path_skips_the_ignore_lookup(self):
        with mock.patch.object(ip, "_is_ignored", lambda path, repo=None: True):
            self.assertEqual([c.path for c in ip.candidates("`NEW:docs/x.md`")], ["docs/x.md"])

    def test_ignore_question_goes_to_the_repository_gitignore(self):
        calls = []

        def fake_git(args, repo=None):
            calls.append(args)
            return 0, "", ""

        with mock.patch.object(ip, "git_output", fake_git):
            self.assertTrue(ip._is_ignored(".mutations", REPO_ROOT))
        self.assertEqual(calls[0][:4], ["check-ignore", "--no-index", "-q", "--"])
        self.assertEqual(calls[0][4], ".mutations")

    def test_ignore_failure_means_not_ignored(self):
        # Запобіжник від шуму не мусить сам ставати відмовою: помилка git
        # означає «не ігнорується», і шлях перевіряється як усі.
        with mock.patch.object(ip, "git_output", lambda args, repo=None: (128, "", "boom")):
            self.assertFalse(ip._is_ignored(".mutations", REPO_ROOT))

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
        self.assertIn("suffix:ci.yml", tree.calls)

    def test_suffix_shorthand_resolves_to_the_one_file_in_main(self):
        # Тікет #1223 назвав файл як `ui/adaptive/WindowLayout.kt`, а в дереві
        # він лежить під `app/src/main/java/com/slukhayka/audiobooks/`. Хвіст
        # на межі сегмента — та сама коротка форма, що `ci.yml`.
        code, verdicts, _ = judge_body(
            "число живе в `ui/adaptive/WindowLayout.kt`",
            {"app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt"},
        )
        self.assertEqual(code, 0)
        self.assertIn("app/src/main/java/", verdicts[0].detail)

    def test_suffix_shorthand_does_not_match_across_segments(self):
        # `adaptive/WindowLayout.kt` — хвіст на межі сегмента; `aptive/…` — ні.
        code, _, _ = judge_body("`aptive/WindowLayout.kt`", {"ui/adaptive/WindowLayout.kt"})
        self.assertEqual(code, 1)

    def test_exact_path_wins_over_a_suffix_match(self):
        code, verdicts, _ = judge_body(
            "`docs/voice.md`", {"docs/voice.md", "vendor/docs/voice.md"}
        )
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].detail, "є в origin/main")

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
        self.assertIn("ні за повним шляхом, ні за хвостом", verdicts[0].detail)

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

    def test_unreadable_local_ref_does_not_advise_fetching(self):
        # `git fetch origin main` лікує лише віддалений ref; для локального це
        # був би шум (NIT огляду PR #1268).
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            code, verdicts = ip.check_body("`AGENTS.md`", "my-local-ref")
        self.assertEqual(code, 2)
        self.assertIn("`my-local-ref` не читається", verdicts[0].detail)
        self.assertNotIn("git fetch", verdicts[0].detail)

    def test_scan_refuses_to_measure_an_unreadable_ref(self):
        # Числа шуму — не твердження: `--scan` міряє той самий ref, про який
        # ухвалює вердикт, і не міряє нічого, якщо ref не читається.
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            self.assertEqual(ip.scan(5, "origin/main"), 2)

    def test_default_ref_is_origin_main(self):
        parser = ip.argparse.ArgumentParser()
        self.assertEqual(ip.MAIN_REF, "origin/main")

    def test_suffix_lookup_uses_ls_tree_of_the_ref(self):
        calls = []

        def fake_git(args, repo=None):
            calls.append(args)
            return 0, "a/b/ci.yml\nc/ci.yml\n", ""

        with mock.patch.object(ip, "git_output", fake_git):
            matches = ip.main_matches_suffix("origin/main", "ci.yml")
        self.assertEqual(matches, ["a/b/ci.yml", "c/ci.yml"])
        # `-t` обов'язковий: без нього каталог у `main` не знаходився ніколи
        # (знахідка огляду PR #1268).
        self.assertEqual(calls[0][:4], ["ls-tree", "-r", "-t", "--name-only"])
        self.assertEqual(calls[0][4], "origin/main")

    def test_suffix_lookup_sees_directories_not_only_blobs(self):
        # Справжній приклад: `ui/components` у дереві є, але лише як теки.
        code, verdicts, _ = judge_body(
            "`ui/components` і `ui/adaptive`",
            {
                "app/src/main/java/com/slukhayka/audiobooks/ui/components",
                "app/src/main/java/com/slukhayka/audiobooks/ui/adaptive",
            },
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})


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
        self.assertIn("є в origin/main: 1", report)
        self.assertIn("відмов: 1", report)

    def test_report_counts_a_new_path_as_new_not_as_present(self):
        # Інакше підсумок казав би «є в origin/main: 3» про шлях, якого там
        # немає, — а це та сама неправда про `main`, яку ґард і ловить.
        _, verdicts, _ = judge_body("`NEW:docs/agents/premise.md` і `AGENTS.md`", {"AGENTS.md"})
        report = ip.format_report("origin/main", 0, verdicts)
        self.assertIn("є в origin/main: 1", report)
        self.assertIn("позначено `NEW:`: 1", report)


class RealGitTest(unittest.TestCase):
    """Справжній `git` — але у **своєму** репозиторії, не в чекауті.

    Це відповідь на падіння CI PR #1268: тест, який питав `origin/main`
    репозиторію, у якому біжить, залежав від того, що завантажив раннер
    (`actions/checkout` фетчить merge-ref, і `origin/main` там немає — тож
    дерево порожнє, і тека читалася як проза). Тут репозиторій піднімається
    сам, зі своїм `origin/main`, тож перевіряється справжній git-шлях
    (`cat-file`, `ls-tree`, `check-ignore`), а не підставка — і жодного
    питання до стану чекауту.
    """

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="issue-premise-git-")
        cls.addClassCleanup(cls._tmp.cleanup)
        cls.repo = build_repo(
            Path(cls._tmp.name),
            {
                "AGENTS.md": "# Agent instructions\n",
                "docs/voice.md": "voice\n",
                "docs/agents/issue-tracker.md": "tracker\n",
                "ui/components/PlayerPane.kt": "package ui.components\n",
            },
            ".mutations/\n/build\n",
        )

    def test_ref_reads_as_a_commit(self):
        ok, revision = ip.resolve_main_ref("origin/main", self.repo)
        self.assertTrue(ok)
        self.assertRegex(revision, r"^[0-9a-f]{40}$")

    def test_file_in_main_is_found(self):
        self.assertTrue(ip.main_has_path("origin/main", "AGENTS.md", self.repo))

    def test_untracked_file_in_the_worktree_is_invisible_to_main(self):
        # Головна межа ґарда: він дивиться в дерево `origin/main`, а не в
        # робочу копію. Цей файл є в дереві й не є в ref-і.
        (self.repo / "UNCOMMITTED.md").write_text("ще не в main\n", encoding="utf-8")
        self.assertTrue((self.repo / "UNCOMMITTED.md").exists())
        self.assertFalse(ip.main_has_path("origin/main", "UNCOMMITTED.md", self.repo))

    def test_unborn_path_in_main_is_not_found(self):
        self.assertFalse(ip.main_has_path("origin/main", "CODING_STANDARDS.md", self.repo))
        self.assertTrue(ip.main_has_path("origin/main", "AGENTS.md", self.repo))

    def test_suffix_lookup_sees_directories_through_real_git(self):
        # `-t` на справжньому дереві: тека є лише деревом, не блобом.
        matches = ip.main_matches_suffix("origin/main", "ui/components", self.repo)
        self.assertEqual(matches, ["ui/components"])

    def test_ignored_path_goes_through_the_repository_gitignore(self):
        self.assertTrue(ip._is_ignored(".mutations", self.repo))
        self.assertFalse(ip._is_ignored("AGENTS.md", self.repo))

    def test_body_is_judged_against_the_repos_own_ref(self):
        # Той самий шлях у тілі, але ref-и різні: вердикт дає дерево ref-а.
        code, _ = ip.check_body("читай `AGENTS.md`", "origin/main", self.repo)
        self.assertEqual(code, 0)

    def test_missing_ref_is_unknown_not_a_pass(self):
        code, verdicts = ip.check_body("читай `AGENTS.md`", "origin/nonexistent", self.repo)
        self.assertEqual(code, 2)
        self.assertIn("не читається", verdicts[0].detail)

    def test_git_output_reports_failure_for_a_missing_path(self):
        code, _, _ = ip.git_output(
            ["cat-file", "-e", "origin/main:definitely/not/here.md"], self.repo
        )
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
