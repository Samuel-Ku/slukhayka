"""Тести scripts/merge-pr.sh: відмови гейта на підставному gh.

Стиль той самий, що в scripts/tests/test_test_wrappers.py: скрипт запускається
як процес, а зовнішній інструмент підмінюється змінною (MERGE_PR_GH вказує на
підставний gh із FAKE_GH_DIR). Жоден тест не ходить у мережу і не чіпає
справжній репозиторій.

Дані для історичного тесту (#1215) взяті з живого API на момент того мерджу:
`gh pr checks 1215 --json name,state,bucket,startedAt,completedAt` і
`gh api repos/{owner}/{repo}/commits/735771adafeb.../check-runs?filter=all`
(17 прогонів, 16 success + 1 skipped, 19:05:35–19:44:59; мердж о 19:45:17).

Перевірки 2 і 3 гейта читають робочу копію, тож кожен тест дістає власний
git-репозиторій у тимчасовому каталозі (MERGE_PR_REPO). Історія в ньому
синтетична, але топологія відмов — справжня: ребаз, що впав на конфлікті
(обидва маркери), і гілка, з якої на remote поїхав лише перший пік ребаза —
саме так злився #1239 (4 коміти → 1, див. `git reflog` у .git/worktrees/w1224).
"""

import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MERGE_PR = REPO_ROOT / "scripts" / "merge-pr.sh"

HEAD = "735771adafeb47de3a990f881fc75c1cf84839a6"
HEAD_SHORT = HEAD[:7]
HEAD_COMMITTED_AT = "2026-10-10T18:56:27Z"
HEAD_HEADLINE = "test(snapshots): #1205 кадри межі 840 dp і смуги під рейлом"
VERDICT_LABEL = "review:pass"
# Вердикт, свіжий щодо CI: усі прогони нижче закінчились до 19:44:59.
VERDICT_AT = "2026-10-10T19:50:00Z"
# Вердикт, старіший за частину прогонів — для перевірки «мітка старіша за нове».
EARLY_VERDICT_AT = "2026-10-10T19:00:00Z"
# Гілка PR #1215: у тимчасовому репозиторії її немає, тож перевірка цілісності
# мовчки (WARN) пропускається скрізь, де її не перевіряють навмисно.
PR_BRANCH = "t1205-player-rail"

# (назва, висновок, початок, кінець) — три зелені чеки замість сімнадцяти: решта
# тестів перевіряють не повноту матриці, а логіку гейта.
GREEN_CHECKS = (
    ("Pure JVM", "SUCCESS", "2026-10-10T19:05:49Z", "2026-10-10T19:09:11Z"),
    ("Android Lint", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:12:05Z"),
    ("Room native SDK 36", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:30:17Z"),
)

# Реальний стан #1215 на момент мерджу: усі чеки зелені (один skipped),
# мітки вердикту немає — саме тому гейт і відмовив би.
HISTORICAL_1215_CHECKS = (
    ("Start workflow timer", "SUCCESS", "2026-10-10T19:05:35Z", "2026-10-10T19:05:39Z"),
    ("Release branch lineage guard", "SUCCESS", "2026-10-10T19:05:35Z", "2026-10-10T19:05:38Z"),
    ("Base sync guard", "SUCCESS", "2026-10-10T19:05:35Z", "2026-10-10T19:05:48Z"),
    ("Migration slot guard", "SUCCESS", "2026-10-10T19:05:35Z", "2026-10-10T19:05:48Z"),
    ("Room native default SDK", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:09:54Z"),
    ("Room native SDK 35", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:12:05Z"),
    ("Room native SDK 36", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:30:17Z"),
    ("Room Robolectric-only", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:40:05Z"),
    ("Android Lint", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:12:05Z"),
    ("Recommendation eval", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:11:01Z"),
    ("Web typecheck and vitest", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:06:39Z"),
    ("Accessibility journey (API 35)", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:14:22Z"),
    ("Pure JVM", "SUCCESS", "2026-10-10T19:05:49Z", "2026-10-10T19:09:11Z"),
    ("Compose + Roborazzi", "SUCCESS", "2026-10-10T19:05:50Z", "2026-10-10T19:11:43Z"),
    ("Signed smoke APK", "SKIPPED", "2026-10-10T19:40:06Z", "2026-10-10T19:40:06Z"),
    ("Kover aggregate gate", "SUCCESS", "2026-10-10T19:40:08Z", "2026-10-10T19:44:53Z"),
    ("Workflow timing", "SUCCESS", "2026-10-10T19:44:55Z", "2026-10-10T19:44:59Z"),
)

# Прогони з реального payload #1215, що стартували або завершились після
# вердикту 2026-10-10T19:10:00Z (пораховано з живого API, а не тим самим
# кодом, що й у скрипті: інакше тест перевіряв би сам себе).
LATE_AFTER_19_10 = (
    "Accessibility journey (API 35)",
    "Android Lint",
    "Compose + Roborazzi",
    "Kover aggregate gate",
    "Recommendation eval",
    "Room Robolectric-only",
    "Room native SDK 35",
    "Room native SDK 36",
    "Signed smoke APK",
    "Workflow timing",
)

# Підставний git старої версії: --path-format зʼявився лише в 2.31, і без нього
# скан робочої копії не має права тихо перетворитися на «маркерів немає».
FAKE_OLD_GIT = '''#!/usr/bin/env bash
set -u
for argument in "$@"; do
  case "$argument" in
    --path-format*) printf 'error: unknown option `%s\\n' "$argument" >&2; exit 129 ;;
  esac
done
exec git "$@"
'''

# Підставний git, у якого не працює саме порівняння патчів: тоді «патчів, яких
# немає у вершині, не видно» — це невідомість, а не дозвіл зливати.
FAKE_BROKEN_GIT = '''#!/usr/bin/env bash
set -u
for argument in "$@"; do
  case "$argument" in
    --cherry-mark*) printf 'fatal: unable to read tree\\n' >&2; exit 128 ;;
  esac
done
exec git "$@"
'''

FAKE_GH = '''#!/usr/bin/env bash
# Підставний gh: віддає фікстури з FAKE_GH_DIR, пише кожен виклик у FAKE_GH_LOG.
set -u
printf '%s\\n' "$*" >> "$FAKE_GH_LOG"

sub="${1:-} ${2:-}"
path=""
for argument in "$@"; do
  case "$argument" in
    repos/*) path="$argument" ;;
  esac
done

case "$sub" in
  "pr view")
    cat "$FAKE_GH_DIR/pr.json"
    ;;
  "pr merge")
    if [ "${FAKE_GH_MERGE_EXIT:-0}" -eq 0 ]; then
      printf '✓ Squashed and merged pull request\\n'
      exit 0
    fi
    printf 'gh: Pull request is not mergeable (HTTP 405)\\n' >&2
    exit "${FAKE_GH_MERGE_EXIT}"
    ;;
  "api "*)
    case "$path" in
      */labels/*)
        case "${FAKE_GH_LABEL_MODE:-auto}" in
          error)
            printf 'error connecting to api.github.com\\n' >&2
            exit 1
            ;;
        esac
        # 404, коли мітки немає в репозиторії — так поводиться справжній gh.
        [ -f "$FAKE_GH_DIR/label.json" ] || { printf 'gh: Not Found (HTTP 404)\\n' >&2; exit 1; }
        cat "$FAKE_GH_DIR/label.json"
        ;;
      */timeline) cat "$FAKE_GH_DIR/timeline.json" ;;
      *check-runs*) cat "$FAKE_GH_DIR/check-runs.json" ;;
      */commits) cat "$FAKE_GH_DIR/commits.json" ;;
      *) cat "$FAKE_GH_DIR/repo.json" ;;
    esac
    ;;
  *)
    printf 'fake gh: несподіваний виклик: %s\\n' "$*" >&2
    exit 3
    ;;
esac
'''


def check(name, conclusion="SUCCESS", started="2026-10-10T19:05:41Z",
          completed="2026-10-10T19:12:05Z", status="COMPLETED"):
    """Контекст зі зведення PR (gh pr view --json statusCheckRollup)."""
    return {
        "__typename": "CheckRun",
        "name": name,
        "conclusion": conclusion,
        "status": status,
        "startedAt": started,
        "completedAt": completed,
        "detailsUrl": "https://example.invalid/checks/%s" % name.replace(" ", "-").lower(),
    }


def status_context(name="ci/legacy", state="FAILURE"):
    """Старий commit status: у зведенні він теж контекст, але без часу."""
    return {
        "__typename": "StatusContext",
        "context": name,
        "state": state,
        "targetUrl": "https://example.invalid/status/%s" % name.replace("/", "-"),
    }


def run(name, conclusion="success", started="2026-10-10T19:05:41Z",
        completed="2026-10-10T19:12:05Z", status="completed"):
    """Прогін у REST-історії чеків (gh api .../check-runs?filter=all)."""
    return {
        "name": name,
        "status": status,
        "conclusion": conclusion,
        "started_at": started,
        "completed_at": completed,
        "html_url": "https://example.invalid/runs/%s" % name.replace(" ", "-").lower(),
    }


def rollup(specs=GREEN_CHECKS):
    return [check(name, conclusion, started, completed) for name, conclusion, started, completed in specs]


def history(specs=GREEN_CHECKS):
    return [run(name, conclusion.lower(), started, completed) for name, conclusion, started, completed in specs]


def check_runs_page(*runs):
    """Сторінка /commits/<sha>/check-runs у СПРАВЖНІЙ формі API.

    `gh api --paginate --slurp` тут віддає масив сторінок-об'єктів
    `[{"total_count": N, "check_runs": [...]}]`, а не масив прогонів. Перша
    версія гейта чекала на список, тож бачила 0 пізніх прогонів; фікстура
    мусить мати ту саму форму, інакше тест зеленіє на формі, якої API не
    віддає ніколи.
    """
    return [{"total_count": len(runs), "check_runs": list(runs)}]


def real_1215_check_runs_page():
    """Реальна відповідь API для HEAD #1215: 17 прогонів, 19:05:35–19:44:59.

    Імена, висновки й часи — з `gh api .../commits/735771a.../check-runs?filter=all`;
    фікстура звірена з живим payload поелементно.
    """
    return check_runs_page(*history(HISTORICAL_1215_CHECKS))


def rest_commit(sha=HEAD, when=HEAD_COMMITTED_AT, headline=HEAD_HEADLINE):
    """Коміт у REST-списку (gh api .../pulls/N/commits)."""
    return {
        "sha": sha,
        "commit": {
            "message": headline + "\n\nтіло",
            "committer": {"date": when},
            "author": {"name": "Samuel-Ku", "email": "shtopor02@gmail.com"},
        },
        "author": {"login": "Samuel-Ku"},
    }


def labeled(when=VERDICT_AT, label=VERDICT_LABEL):
    return {"event": "labeled", "created_at": when, "label": {"name": label}}


def page(*events):
    """gh api --paginate --slurp: масив сторінок."""
    return [list(events)]


def pr_payload(*, checks=None, labels=(VERDICT_LABEL,), state="OPEN",
               draft=False, mergeable="MERGEABLE", merge_state="CLEAN",
               base="main", head=HEAD, head_ref=PR_BRANCH, cross_repo=False,
               number=1215):
    return {
        "number": number,
        "state": state,
        "isDraft": draft,
        "mergeable": mergeable,
        "mergeStateStatus": merge_state,
        "baseRefName": base,
        "headRefName": head_ref,
        "headRefOid": head,
        "isCrossRepository": cross_repo,
        "url": "https://github.com/Samuel-Ku/slukhayka/pull/%d" % number,
        "labels": [{"name": name} for name in labels],
        "statusCheckRollup": rollup() if checks is None else checks,
    }


class MergePrTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fixtures = self.root / "fixtures"
        self.fixtures.mkdir()
        self.log = self.root / "gh.log"
        self.gh = self.root / "gh"
        self.gh.write_text(FAKE_GH, encoding="utf-8")
        self.gh.chmod(self.gh.stat().st_mode | stat.S_IXUSR)
        self.repo = self.root / "repo"
        self.make_repo()

    def tearDown(self):
        self._tmp.cleanup()

    # --- тимчасова робоча копія ---------------------------------------------
    def git(self, *args, cwd=None, check=True):
        """git у тимчасовій копії; жодного конфігу користувача не читає."""
        environment = os.environ.copy()
        environment.update({
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_AUTHOR_NAME": "Samuel-Ku",
            "GIT_AUTHOR_EMAIL": "shtopor02@gmail.com",
            "GIT_COMMITTER_NAME": "Samuel-Ku",
            "GIT_COMMITTER_EMAIL": "shtopor02@gmail.com",
        })
        return subprocess.run(
            ["git", *args], cwd=str(cwd or self.repo), check=check,
            capture_output=True, text=True, env=environment,
        )

    def git_out(self, *args, cwd=None):
        return self.git(*args, cwd=cwd).stdout.strip()

    def make_repo(self):
        """Чиста копія з одним комітом у main — для тестів про локальний стан."""
        if self.repo.exists():
            shutil.rmtree(self.repo)
        self.repo.mkdir(parents=True)
        self.git("init", "-q", "-b", "main")
        return self.commit("README.md", "base\n", "chore: основа")

    def commit(self, filename, content, message, cwd=None):
        root = Path(cwd) if cwd is not None else self.repo
        path = root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        self.git("add", "-A", cwd=root)
        self.git("commit", "-q", "-m", message, cwd=root)
        return self.git_out("rev-parse", "HEAD", cwd=root)

    def stop_rebase(self, cwd=None, backend="merge", onto="main"):
        """Ребаз, що спиняється на конфлікті, — саме він лишає маркер."""
        arguments = ["rebase"] + (["--apply"] if backend == "apply" else []) + [onto]
        return self.git(*arguments, cwd=cwd, check=False)

    def conflict_on_both_sides(self, cwd, branch):
        """Гілка (у cwd) і main правлять один файл — ребаз спиниться.

        Гілка мусить бути видимою в тому каталозі, де її ребазують: у
        лінкованому worktree це окрема робоча копія. Наприкінці cwd стоїть на
        гілці — інакше ребаз пішов би з main і нічого б не спинив.
        """
        root = Path(cwd) if cwd is not None else self.repo
        exists = self.git("rev-parse", "--verify", "--quiet", "refs/heads/%s" % branch,
                          cwd=root, check=False)
        if exists.returncode == 0:
            self.git("checkout", "-q", branch, cwd=root)
        else:
            self.git("checkout", "-q", "-b", branch, cwd=root)
        self.commit("conflict.txt", "branch\n", "feat: правка гілки", cwd=root)
        self.git("checkout", "-q", "main", cwd=self.repo)
        self.commit("conflict.txt", "main\n", "chore: правка main", cwd=self.repo)
        self.git("checkout", "-q", branch, cwd=root)
        return root

    def linked_worktree(self, branch):
        """Друга робоча копія того самого репозиторію — як w1224 поруч із гейтом."""
        linked = self.root / "linked"
        self.git("worktree", "add", "-q", "-b", branch, str(linked), "main")
        return linked

    def environment(self, extra=None) -> dict:
        """Оточення гейта і підставного gh — своє, а не успадковане (#1265).

        CI-раннер приносить у процес власні змінні, і серед них може бути
        експортована функція (`BASH_FUNC_cat%%`, `BASH_FUNC_printf%%`), яка
        затіняє команду ВСЕРЕДИНІ підставного gh: він тоді не віддає фікстуру й
        виходить із кодом 3 — при незмінних файлах фікстури. Саме так фікстура
        #1260 і почервоніла в CI, хоч локально була зелена.

        `scripts/tests/test_mutate.py` задає оточення так само — свіжим
        словником, а не `os.environ`. Імена тут — лише свої: `FAKE_GH_*` читає
        підставний gh, `MERGE_PR_*` — вхід самого гейта.
        """
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "MERGE_PR_GH": str(self.gh),
            "MERGE_PR_REPO": str(self.repo),
            "FAKE_GH_DIR": str(self.fixtures),
            "FAKE_GH_LOG": str(self.log),
        }
        environment.update(extra or {})
        return environment

    def run_script(self, *args, env_extra=None):
        """Запускає гейт як процес із підставним gh у PATH-незалежній змінній."""
        environment = self.environment(env_extra)
        environment.pop("MERGE_PR_LABEL", None)
        return subprocess.run(
            ["bash", str(MERGE_PR), *args],
            check=False, capture_output=True, text=True,
            env=environment, cwd=str(REPO_ROOT),
        )

    def gate(self, *flags, pr=None, timeline=None, check_runs=None, commits=None,
             repo=None, label_in_repo=True, env_extra=None):
        """Записує фікстури й запускає гейт на PR з фікстури.

        Типово — зелений PR зі свіжим вердиктом; кожен тест міняє рівно те,
        що перевіряє. Номер PR береться з фікстури, щоб підпис відмови
        збігався з тим, що тест насправді моделює.
        """
        payload = pr_payload() if pr is None else pr
        fixtures = {
            "pr.json": payload,
            "timeline.json": page(labeled()) if timeline is None else timeline,
            "check-runs.json": check_runs_page(*history()) if check_runs is None else check_runs,
            "commits.json": [[rest_commit()]] if commits is None else commits,
            "repo.json": {"delete_branch_on_merge": False} if repo is None else repo,
        }
        for name, value in fixtures.items():
            (self.fixtures / name).write_text(json.dumps(value), encoding="utf-8")
        label_file = self.fixtures / "label.json"
        if label_in_repo:
            label_file.write_text(json.dumps({"name": VERDICT_LABEL}), encoding="utf-8")
        else:
            # Мітки немає в репозиторії: справжній gh віддає на це 404.
            label_file.unlink(missing_ok=True)
        return self.run_script(str(payload.get("number", 1215)), *flags, env_extra=env_extra)

    def invocations(self):
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()

    def merge_calls(self):
        return [line for line in self.invocations() if line.startswith("pr merge")]

    # --- відмови на чеках ---------------------------------------------------
    def test_refuses_a_failing_check(self):
        checks = [check("Pure JVM"), check("Android Lint", "FAILURE")]

        result = self.gate(pr=pr_payload(checks=checks))

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("Android Lint", result.stderr)
        self.assertIn("FAILURE", result.stderr)
        self.assertIn("упали", result.stderr)
        self.assertIn("Далі:", result.stderr)
        self.assertNotIn("PR #1215 злито", result.stdout)

    def test_refuses_a_legacy_status_context_that_failed(self):
        checks = [check("Pure JVM"), status_context()]

        result = self.gate(pr=pr_payload(checks=checks))

        self.assertEqual(1, result.returncode)
        self.assertIn("ci/legacy", result.stderr)
        self.assertIn("FAILURE", result.stderr)

    def test_refuses_pending_checks(self):
        checks = [check("Pure JVM"), check("Room native SDK 36", "", completed=None, status="IN_PROGRESS")]

        result = self.gate(pr=pr_payload(checks=checks))

        self.assertEqual(1, result.returncode)
        self.assertIn("не завершився", result.stderr)
        self.assertIn("Room native SDK 36", result.stderr)

    def test_refuses_a_cancelled_check(self):
        checks = [check("Pure JVM"), check("Compose + Roborazzi", "CANCELLED")]

        result = self.gate(pr=pr_payload(checks=checks))

        self.assertEqual(1, result.returncode)
        self.assertIn("скасовано", result.stderr)
        self.assertIn("Compose + Roborazzi", result.stderr)

    def test_refuses_a_pr_with_no_checks_at_all(self):
        result = self.gate(pr=pr_payload(checks=[]))

        self.assertEqual(1, result.returncode)
        self.assertIn("немає жодного чека", result.stderr)

    # --- відмови на стані PR ------------------------------------------------
    def test_refuses_a_draft_or_unfinished_pr(self):
        cases = (
            (pr_payload(draft=True), "чернетка"),
            (pr_payload(state="MERGED"), "MERGED"),
            (pr_payload(state="CLOSED"), "CLOSED"),
            (pr_payload(mergeable="CONFLICTING", merge_state="DIRTY"), "конфліктує"),
        )
        for pr, expected in cases:
            with self.subTest(expected=expected):
                result = self.gate(pr=pr)
                self.assertEqual(1, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assertEqual([], self.merge_calls())

    # --- відмови на вердикті ------------------------------------------------
    def test_refuses_without_the_review_verdict(self):
        result = self.gate(pr=pr_payload(labels=()), timeline=page())

        self.assertEqual(1, result.returncode)
        self.assertIn("машиночитного вердикту огляду", result.stderr)
        self.assertIn(VERDICT_LABEL, result.stderr)
        self.assertIn("--add-label", result.stderr)
        self.assertNotIn("gh label create", result.stderr)  # мітка в репозиторії вже є
        self.assertEqual([], self.merge_calls())

    def test_names_the_label_creation_when_the_repository_has_none(self):
        result = self.gate(pr=pr_payload(labels=()), timeline=page(), label_in_repo=False)

        self.assertEqual(1, result.returncode)
        self.assertIn("gh label create 'review:pass'", result.stderr)
        self.assertIn("немає і в репозиторії", result.stderr)

    def test_refuses_a_verdict_whose_time_is_unknown(self):
        result = self.gate(timeline=page())  # мітка на PR є, події проставлення немає

        self.assertEqual(1, result.returncode)
        self.assertIn("час вердикту невідомий", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_refuses_when_new_commits_landed_after_the_verdict(self):
        result = self.gate(
            timeline=page(labeled(EARLY_VERDICT_AT)),
            commits=[[
                rest_commit(),
                rest_commit("61cf5d7d01a4ee10075752ae5b0225f1c8f7d5c1",
                            "2026-10-10T20:10:00Z", "fix(ui): правка після огляду"),
            ]],
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("нові коміти", result.stderr)
        self.assertIn("61cf5d7", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_refuses_a_force_push_after_the_verdict(self):
        timeline = page(
            labeled(VERDICT_AT),
            {"event": "head_ref_force_pushed", "created_at": "2026-10-10T20:00:00Z",
             "after_commit": "61cf5d7d01a4ee10075752ae5b0225f1c8f7d5c1"},
        )

        result = self.gate(timeline=timeline)

        self.assertEqual(1, result.returncode)
        self.assertIn("перезапис", result.stderr)
        self.assertIn("61cf5d7", result.stderr)

    def test_refuses_a_fail_that_appeared_after_the_verdict_and_was_rerun_green(self):
        """Найтонший випадок: зведення PR зараз зелене, бо fail перезапустили."""
        check_runs = check_runs_page(
            *history(),
            run("Room native SDK 36", "failure",
                "2026-10-10T20:05:00Z", "2026-10-10T20:20:00Z"),
        )

        result = self.gate(check_runs=check_runs)

        self.assertEqual(1, result.returncode)
        self.assertIn("після вердикту", result.stderr)
        self.assertIn("Room native SDK 36", result.stderr)
        self.assertIn("failure", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_refuses_ci_that_reran_after_the_verdict(self):
        check_runs = check_runs_page(
            *history(),
            run("Pure JVM", "success", "2026-10-10T20:05:00Z", "2026-10-10T20:09:00Z"),
        )

        result = self.gate(check_runs=check_runs)

        self.assertEqual(1, result.returncode)
        self.assertIn("старіший за останній прогін CI", result.stderr)
        self.assertIn("Pure JVM", result.stderr)

    def test_refuses_when_the_repository_would_delete_the_branch(self):
        result = self.gate(repo={"delete_branch_on_merge": True})

        self.assertEqual(1, result.returncode)
        self.assertIn("delete_branch_on_merge", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_parses_the_real_check_runs_page_wrapper(self):
        """Реальний payload #1215 (17 прогонів) у справжній формі сторінки-об'єкта.

        З вердиктом о 19:10:00Z пізніх прогонів рівно 10 — перша версія
        парсера бачила 0 і зливала PR попри свіжий CI.
        """
        result = self.gate(
            pr=pr_payload(checks=rollup(HISTORICAL_1215_CHECKS)),
            timeline=page(labeled("2026-10-10T19:10:00Z")),
            check_runs=real_1215_check_runs_page(),
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("старіший за останній прогін CI", result.stderr)
        named = {
            line.split("✗", 1)[1].split("—")[0].strip()
            for line in result.stderr.splitlines() if "✗" in line
        }
        self.assertEqual(set(LATE_AFTER_19_10), named)
        self.assertEqual([], self.merge_calls())

    def test_fails_closed_on_an_unexpected_page_shape(self):
        """Незрозуміла сторінка — код 2, а не «пізніх прогонів немає»."""
        result = self.gate(check_runs=[{"total_count": 1, "something_else": []}])

        self.assertEqual(2, result.returncode)
        self.assertIn("не вдалося розібрати відповідь gh", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_merge_failure_is_not_reported_as_a_gate_refusal(self):
        """gh pr merge упав після зелених перевірок — код 2, не 1."""
        result = self.gate(env_extra={"FAKE_GH_MERGE_EXIT": "1"})

        self.assertEqual(2, result.returncode)
        self.assertIn("перевірки пройдено, але gh pr merge не злито", result.stderr)
        self.assertIn("not mergeable", result.stderr)
        self.assertNotIn("машиночитного вердикту", result.stderr)

    def test_does_not_claim_the_label_is_missing_when_the_probe_fails(self):
        result = self.gate(pr=pr_payload(labels=()), timeline=page(),
                           env_extra={"FAKE_GH_LABEL_MODE": "error"})

        self.assertEqual(1, result.returncode)
        self.assertIn("не вдалося перевірити, чи існує мітка", result.stderr)
        self.assertNotIn("немає і в репозиторії", result.stderr)

    # --- локальний стан: незавершений ребаз ---------------------------------
    def test_refuses_an_unfinished_rebase_left_in_the_working_copy(self):
        """Обидва маркери, які лишає git: rebase-merge і rebase-apply.

        Ребаз гілки, яку зливають: у #1239 маркер лишився в дереві, гілку
        рухали посеред операції, і гейт цього не бачив.
        """
        for backend, marker in (("merge", "rebase-merge"), ("apply", "rebase-apply")):
            with self.subTest(backend=backend):
                self.make_repo()
                self.commit("conflict.txt", "base\n", "chore: спільний файл")
                self.conflict_on_both_sides(self.repo, PR_BRANCH)
                stopped = self.stop_rebase(self.repo, backend=backend)
                self.assertNotEqual(0, stopped.returncode, stopped.stdout)
                self.assertTrue((self.repo / ".git" / marker).is_dir(), marker)

                result = self.gate()

                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("незавершений ребаз", result.stderr)
                self.assertIn(marker, result.stderr)
                self.assertIn(PR_BRANCH, result.stderr)
                self.assertIn("rebase --abort", result.stderr)
                # Робоча копія названа нею самою, а не її git-каталогом.
                self.assertIn("робоча копія %s)" % self.repo, result.stderr)
                self.assertEqual([], self.merge_calls())

    def test_refuses_a_rebase_left_in_another_worktree_of_the_same_copy(self):
        """Маркер у лінкованому worktree, гейт запущено з головного.

        Саме так #1239 і злився: ребаз жив у w1224, а гейт кликали з іншого
        worktree того самого клона. Перевірка, що дивиться лише на свій
        git-каталог, цієї відмови не дає — і саме тому цей тест є.
        """
        self.commit("conflict.txt", "base\n", "chore: спільний файл")
        linked = self.linked_worktree(PR_BRANCH)
        self.conflict_on_both_sides(linked, PR_BRANCH)
        self.git("checkout", "-q", PR_BRANCH, cwd=linked)
        stopped = self.stop_rebase(linked)
        self.assertNotEqual(0, stopped.returncode, stopped.stdout)
        marker = self.repo / ".git" / "worktrees" / "linked" / "rebase-merge"
        self.assertTrue(marker.is_dir(), marker)

        result = self.gate()

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("незавершений ребаз", result.stderr)
        self.assertIn(PR_BRANCH, result.stderr)
        self.assertIn(str(linked), result.stderr)  # названо чужу робочу копію
        self.assertEqual([], self.merge_calls())

    def test_warns_but_merges_when_another_band_is_mid_rebase(self):
        """Чужий ребаз не ставить цей PR у чергу за чужою смугою.

        У репозиторії десятки worktree й паралельні агенти: відмова на будь-
        якому ребазі зупинила б усі мерджі, а «Далі» радило б завершувати
        операцію в чужій копії — тобто знищило б чуже розвʼязання конфлікту.
        """
        self.commit("conflict.txt", "base\n", "chore: спільний файл")
        linked = self.linked_worktree("t9999-other-band")
        self.conflict_on_both_sides(linked, "t9999-other-band")
        stopped = self.stop_rebase(linked)
        self.assertNotEqual(0, stopped.returncode, stopped.stdout)
        self.assertTrue(
            (self.repo / ".git" / "worktrees" / "linked" / "rebase-merge").is_dir())

        result = self.gate()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("незавершений ребаз чужої гілки", result.stderr)
        self.assertIn("t9999-other-band", result.stderr)
        self.assertNotIn("лишився незавершений ребаз —", result.stderr)
        self.assertNotEqual([], self.merge_calls())

    def test_refuses_a_rebase_whose_branch_is_not_named(self):
        """`head-name` = detached HEAD: гілку не названо — відмова, не WARN.

        Приписати такий ребаз чужій смузі нема підстав, а мовчазно пропустити
        його означало б пропустити і свій.
        """
        self.commit("conflict.txt", "base\n", "chore: спільний файл")
        self.conflict_on_both_sides(self.repo, PR_BRANCH)
        self.git("checkout", "-q", "--detach", PR_BRANCH)
        stopped = self.stop_rebase(self.repo)
        self.assertNotEqual(0, stopped.returncode, stopped.stdout)
        head_name = (self.repo / ".git" / "rebase-merge" / "head-name").read_text(
            encoding="utf-8").strip()
        self.assertEqual("detached HEAD", head_name)

        # Гілка PR тут навіть не та, що ребазується: відмова не про збіг імені.
        result = self.gate(pr=pr_payload(head_ref="t9999-elsewhere"))

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("незавершений ребаз", result.stderr)
        self.assertIn("не названо", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_refuses_a_stopped_git_am_that_names_no_branch(self):
        """`git am` лишає `rebase-apply` БЕЗ `head-name` — і це відмова.

        Не літерал у фікстурі, а справжній зупинений `git am`: саме він
        відкривав дірку. Запис розділявся табом, а таб — це IFS-whitespace:
        порожнє поле зникало, шлях worktree з'їжджав у поле гілки, і гейт
        читав безіменну операцію як «ребаз чужої гілки, названої шляхом» —
        тобто WARN і мердж там, де гілку рухали й половина могла поїхати.
        """
        patch_file = self.root / "incoming.patch"
        self.commit("am.txt", "base\n", "chore: основа для листа")
        self.git("checkout", "-q", "-b", PR_BRANCH)
        self.commit("am.txt", "місцеве\n", "feat: місцева правка")
        self.git("checkout", "-q", "-b", "incoming", "main")
        self.commit("am.txt", "вхідне\n", "feat: вхідний лист")
        patch_file.write_text(
            self.git("format-patch", "-1", "--stdout", "incoming").stdout, encoding="utf-8")
        self.git("checkout", "-q", PR_BRANCH)

        stopped = self.git("am", str(patch_file), check=False)

        self.assertNotEqual(0, stopped.returncode, stopped.stdout)
        apply_dir = self.repo / ".git" / "rebase-apply"
        self.assertTrue(apply_dir.is_dir(), apply_dir)
        self.assertFalse((apply_dir / "head-name").exists())

        result = self.gate()

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("незавершений ребаз", result.stderr)
        self.assertIn("не названо", result.stderr)
        self.assertNotIn("чужої гілки", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_fails_closed_when_the_working_copy_scan_fails(self):
        """Скан не вдався — код 2, а не «маркерів немає».

        Старий git (< 2.31) не знає --path-format, і без нього гейт не бачить
        жодного маркера: маркер у дереві, а він каже «злив би». Невідомість має
        коштувати код 2, як і ревізія вершини, якої немає в копії.
        """
        old_git = self.root / "git"
        old_git.write_text(FAKE_OLD_GIT, encoding="utf-8")
        old_git.chmod(old_git.stat().st_mode | stat.S_IXUSR)
        self.commit("conflict.txt", "base\n", "chore: спільний файл")
        self.conflict_on_both_sides(self.repo, "t9999-other-band")
        stopped = self.stop_rebase(self.repo)
        self.assertNotEqual(0, stopped.returncode, stopped.stdout)
        self.assertTrue((self.repo / ".git" / "rebase-merge").is_dir())

        result = self.gate(env_extra={"MERGE_PR_GIT": str(old_git)})

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("не вдалося прочитати стан робочої копії", result.stderr)
        self.assertEqual([], self.merge_calls())

    # --- цілісність гілки ---------------------------------------------------
    def pick_only(self, sha):
        """Один коміт серії, застосований до main — те, що лишив ребаз #1239."""
        self.git("checkout", "-q", "-b", "truncated", self.git_out("rev-parse", "main"))
        self.git("cherry-pick", sha)
        picked = self.git_out("rev-parse", "HEAD")
        self.git("checkout", "-q", PR_BRANCH)
        return picked

    def test_refuses_when_the_pr_head_misses_commits_of_the_local_branch(self):
        """Топологія #1239: у гілці серія комітів, у вершині PR — лише перший.

        Ребаз спинився на конфлікті, на remote поїхав один пік із чотирьох
        комітів, CI пройшов (перевіряти не було чого), і в main потрапила
        оригінальна версія скрипта — без правок огляду. GitHub тут не свідок:
        його список комітів PR будується з тієї самої вершини.
        """
        self.git("checkout", "-q", "-b", PR_BRANCH)
        first = self.commit(
            "scripts/web-layout-probe.mjs", "let exitCode = 1\n",
            "feat(scripts): #1224 веб-щуп розкладки — збірка, preview і виміри через CDP")
        self.commit(
            "docs/agents/local-environment.md", "розділ щупа\n",
            "docs(agents): #1224 CSS-половина web перевіряється одною командою")
        tip = self.commit(
            "scripts/web-layout-probe.mjs", "const EXIT_DIFFERENCE = 1\n",
            "fix(scripts): #1224 огляд — коди виходу, source у таблиці")
        truncated = self.pick_only(first)

        result = self.gate(pr=pr_payload(number=1239, head=truncated, head_ref=PR_BRANCH))

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("PR #1239", result.stderr)
        self.assertIn("не містить усіх комітів гілки", result.stderr)
        self.assertIn(PR_BRANCH, result.stderr)
        self.assertIn(tip[:7], result.stderr)
        self.assertIn("запуш повну гілку", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_merges_when_the_local_branch_is_fully_in_the_head(self):
        self.git("checkout", "-q", "-b", PR_BRANCH)
        tip = self.commit(
            "scripts/web-layout-probe.mjs", "const EXIT_DIFFERENCE = 1\n",
            "feat(scripts): #1224 веб-щуп розкладки")

        result = self.gate(pr=pr_payload(number=1239, head=tip, head_ref=PR_BRANCH))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["pr merge 1239 --squash --match-head-commit %s" % tip], self.merge_calls())

    def test_merges_when_the_local_branch_is_behind_the_head(self):
        """Відстала копія — не обрізання: перевірка вимагає входження, не рівності.

        Гілку могли доребазити чи доповнити з іншого клона: локальна копія тоді
        старіша за вершину PR, і мердж зупиняти нічим.
        """
        self.git("checkout", "-q", "-b", PR_BRANCH)
        behind = self.commit("scripts/probe.mjs", "перша версія\n", "feat: перший коміт")
        self.git("checkout", "-q", "-b", "pushed")
        head = self.commit("scripts/probe.mjs", "друга версія\n", "fix: другий коміт")

        result = self.gate(pr=pr_payload(number=1239, head=head, head_ref=PR_BRANCH))

        self.assertEqual(behind, self.git_out("rev-parse", PR_BRANCH))
        self.assertEqual(0, result.returncode, result.stderr)
        # Мовчки: це входження, а не розбіжність історій. Якщо перевірку
        # перетягнути в рівність, сюди приїде WARN про ребаз — і тест почервоніє.
        self.assertNotIn("розійшлися", result.stderr)
        self.assertNotIn("уже у вершині за патчем", result.stderr)
        self.assertEqual(
            ["pr merge 1239 --squash --match-head-commit %s" % head], self.merge_calls())

    def test_warns_but_merges_when_the_local_branch_was_rebased_elsewhere(self):
        """Історії розійшлися, але кожен патч гілки вже у вершині.

        Гілку перебазували й запушили з ІНШОГО клона — місцевий ref лишається
        старим. Відмова тут була б хибною, а її порада (force-push) повернула б
        доребазову історію поверх доребазованої — тобто знищила б рецензоване.
        """
        self.git("checkout", "-q", "-b", PR_BRANCH)
        first = self.commit("scripts/probe.mjs", "перша\n", "feat: перший коміт")
        second = self.commit("scripts/probe.mjs", "друга\n", "fix: другий коміт")
        # Вершина — ті самі патчі, накладені на main, який відтоді зрушив.
        self.git("checkout", "-q", "-b", "pushed", "main")
        self.commit("README.md", "main рухається\n", "chore: main рухається")
        self.git("cherry-pick", first)
        self.git("cherry-pick", second)
        head = self.git_out("rev-parse", "HEAD")
        self.assertNotEqual(head, self.git_out("rev-parse", PR_BRANCH))

        result = self.gate(pr=pr_payload(number=1239, head=head, head_ref=PR_BRANCH))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("розійшлися", result.stderr)
        self.assertIn("ребаз із іншого клона", result.stderr)
        self.assertIn("git fetch origin +refs/heads/%s:refs/heads/%s" % (PR_BRANCH, PR_BRANCH),
                      result.stderr)
        self.assertNotIn("запуш повну гілку", result.stderr)
        self.assertEqual(
            ["pr merge 1239 --squash --match-head-commit %s" % head], self.merge_calls())

    def test_fails_closed_when_the_patch_comparison_fails(self):
        """Порівняння патчів не відбулося — код 2, не «обрізання не видно».

        Порожній список `--cherry-mark` від невдалого виклику не сміє читатися
        як «усі патчі у вершині»: це рівно та сама невідомість, що й ревізія
        вершини, якої немає в копії.
        """
        broken = self.root / "git"
        broken.write_text(FAKE_BROKEN_GIT, encoding="utf-8")
        broken.chmod(broken.stat().st_mode | stat.S_IXUSR)
        self.git("checkout", "-q", "-b", PR_BRANCH)
        first = self.commit("scripts/probe.mjs", "перша\n", "feat: перший коміт")
        self.commit("scripts/probe.mjs", "друга\n", "fix: другий коміт")
        truncated = self.pick_only(first)

        result = self.gate(
            pr=pr_payload(number=1239, head=truncated, head_ref=PR_BRANCH),
            env_extra={"MERGE_PR_GIT": str(broken)})

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("не вдалося визначити, які коміти", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_warns_and_merges_when_the_local_branch_is_not_here(self):
        """Межа названа: чужого клона й форка гейт не бачить — це WARN."""
        self.git("checkout", "-q", "-b", PR_BRANCH)
        self.commit("scripts/probe.mjs", "x\n", "feat: локальний коміт")
        cases = (
            (pr_payload(head_ref="t1247-not-in-this-copy"), "локальної гілки"),
            (pr_payload(head_ref=PR_BRANCH, cross_repo=True), "з форка"),
        )
        for pr, expected in cases:
            with self.subTest(expected=expected):
                result = self.gate(pr=pr)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(expected, result.stderr)
                self.assertIn("НЕ перевірено", result.stderr)
                self.assertNotEqual([], self.merge_calls())

    def test_fails_closed_when_the_local_copy_lacks_the_pr_head(self):
        """Гілка локально є, а ревізії вершини немає — код 2, не «злив би»."""
        self.git("checkout", "-q", "-b", PR_BRANCH)
        self.commit("scripts/probe.mjs", "x\n", "feat: локальний коміт")

        result = self.gate(pr=pr_payload(head="0" * 40, head_ref=PR_BRANCH))

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("немає ревізії", result.stderr)
        self.assertIn("git fetch origin %s" % PR_BRANCH, result.stderr)
        self.assertEqual([], self.merge_calls())

    # --- злиття -------------------------------------------------------------
    def test_merges_with_squash_and_never_deletes_the_branch(self):
        result = self.gate()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ["pr merge 1215 --squash --match-head-commit %s" % HEAD],
            self.merge_calls(),
        )
        self.assertNotIn("--delete-branch", "\n".join(self.invocations()))
        self.assertIn("злито squash-ом", result.stdout)

    def test_dry_run_reports_the_merge_without_running_it(self):
        result = self.gate("--dry-run")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.merge_calls())
        dry_run = [line for line in result.stdout.splitlines() if "DRY-RUN" in line]
        self.assertEqual(1, len(dry_run), result.stdout)
        self.assertIn("pr merge 1215 --squash --match-head-commit %s" % HEAD, dry_run[0])
        self.assertNotIn("--delete-branch", dry_run[0])

    # --- історичний доказ ---------------------------------------------------
    def test_historical_state_of_pr_1215_is_refused(self):
        """#1215 на момент мерджу: 17 зелених чеків, вердикту огляду немає."""
        pr = pr_payload(checks=rollup(HISTORICAL_1215_CHECKS), labels=(), state="OPEN")

        result = self.gate(pr=pr, timeline=page(), commits=[[rest_commit()]])

        self.assertEqual(1, result.returncode)
        self.assertIn("машиночитного вердикту огляду", result.stderr)
        self.assertIn(HEAD_SHORT, result.stderr)
        # Саме вердикт, а не CI: червоних чеків у #1215 не було.
        self.assertNotIn("упали", result.stderr)
        self.assertNotIn("не завершився", result.stderr)
        self.assertEqual([], self.merge_calls())

    # --- аргументи і відповіді gh -------------------------------------------
    def test_fails_closed_on_an_empty_gh_response(self):
        result = self.gate(pr={})

        self.assertEqual(2, result.returncode)
        self.assertIn("порожня відповідь", result.stderr)
        self.assertEqual([], self.merge_calls())

    def test_rejects_bad_usage(self):
        for args in ([], ["abc"], ["12", "--bogus"], ["12", "13"]):
            with self.subTest(args=args):
                result = self.run_script(*args)
                self.assertEqual(2, result.returncode)
                self.assertEqual([], self.invocations())

    def test_help_exits_zero_without_calling_gh(self):
        result = self.run_script("--help")

        self.assertEqual(0, result.returncode)
        self.assertIn("usage: scripts/merge-pr.sh", result.stdout)
        self.assertEqual([], self.invocations())


if __name__ == "__main__":
    unittest.main()
