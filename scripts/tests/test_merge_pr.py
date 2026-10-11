"""Тести scripts/merge-pr.sh: відмови гейта на підставному gh.

Стиль той самий, що в scripts/tests/test_test_wrappers.py: скрипт запускається
як процес, а зовнішній інструмент підмінюється змінною (MERGE_PR_GH вказує на
підставний gh із FAKE_GH_DIR). Жоден тест не ходить у мережу і не чіпає
справжній репозиторій.

Дані для історичного тесту (#1215) взяті з живого API на момент того мерджу:
`gh pr checks 1215 --json name,state,bucket,startedAt,completedAt` і
`gh api repos/{owner}/{repo}/commits/735771adafeb.../check-runs?filter=all`
(17 прогонів, 16 success + 1 skipped, 19:05:35–19:44:59; мердж о 19:45:17).

Перевірка 2 гейта читає робочу копію, тож кожен тест дістає власний
git-репозиторій у тимчасовому каталозі (MERGE_PR_REPO). Історія в ньому
синтетична, але топологія відмови — справжня: ребаз, що впав на конфлікті
(обидва маркери), — саме з таким злився #1239 (див. `git reflog` у
.git/worktrees/w1224).
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
# Гілка, за якою тести будують локальний стан.
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
               base="main", head=HEAD, number=1215):
    return {
        "number": number,
        "state": state,
        "isDraft": draft,
        "mergeable": mergeable,
        "mergeStateStatus": merge_state,
        "baseRefName": base,
        "headRefOid": head,
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

    def git_out(self, *args):
        return self.git(*args).stdout.strip()

    def make_repo(self):
        """Чиста копія з одним комітом у main — для тестів про локальний стан."""
        if self.repo.exists():
            shutil.rmtree(self.repo)
        self.repo.mkdir(parents=True)
        self.git("init", "-q", "-b", "main")
        return self.commit("README.md", "base\n", "chore: основа")

    def commit(self, filename, content, message):
        path = self.repo / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git_out("rev-parse", "HEAD")

    def run_script(self, *args, env_extra=None):
        """Запускає гейт як процес із підставним gh у PATH-незалежній змінній."""
        environment = os.environ.copy()
        environment.pop("MERGE_PR_LABEL", None)
        environment.update({
            "MERGE_PR_GH": str(self.gh),
            "MERGE_PR_REPO": str(self.repo),
            "FAKE_GH_DIR": str(self.fixtures),
            "FAKE_GH_LOG": str(self.log),
        })
        environment.update(env_extra or {})
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
    def conflicted_rebase(self, backend):
        """Гілка й main правлять один файл — ребаз спиняється на конфлікті."""
        self.commit("conflict.txt", "base\n", "chore: спільний файл")
        self.git("checkout", "-q", "-b", PR_BRANCH)
        self.commit("conflict.txt", "branch\n", "feat: правка гілки")
        self.git("checkout", "-q", "main")
        self.commit("conflict.txt", "main\n", "chore: правка main")
        self.git("checkout", "-q", PR_BRANCH)
        arguments = ["rebase"] + (["--apply"] if backend == "apply" else []) + ["main"]
        return self.git(*arguments, check=False)

    def test_refuses_an_unfinished_rebase_left_in_the_working_copy(self):
        """Обидва маркери, які лишає git: rebase-merge і rebase-apply.

        #1239 злився з `rebase-merge` у дереві: ребаз упав на конфлікті, а
        гейт цього не бачив. Маркер у w1224, а гейт запускають з іншого
        worktree, тож перевірка дивиться на всю копію.
        """
        for backend, marker in (("merge", "rebase-merge"), ("apply", "rebase-apply")):
            with self.subTest(backend=backend):
                self.make_repo()
                stopped = self.conflicted_rebase(backend)
                self.assertNotEqual(0, stopped.returncode, stopped.stdout)
                self.assertTrue((self.repo / ".git" / marker).is_dir(), marker)

                result = self.gate()

                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("незавершений ребаз", result.stderr)
                self.assertIn(marker, result.stderr)
                self.assertIn(PR_BRANCH, result.stderr)
                self.assertIn("rebase --abort", result.stderr)
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
