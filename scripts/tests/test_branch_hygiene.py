"""Чистка гілок і локального дерева тримається на доведенні (#1303).

Що тут піниться:

  * `DecideTest` — три доведення поглинання (`ancestor`, `pr-merged-sha` із
    merge-комітом у графі `main`, `patch-absorbed`) і все, що їх скасовує:
    відкритий PR, PR у релізній лінії, захищена назва, реліз без тега;
  * `LocalDecideTest` — локальні запобіжники: резервування, свіжість, бруд,
    ігнорований вміст, головне й поточне дерево, worktree поза репозиторієм;
  * `ClaimsTest` — резервування шукається за номером тікета в назві гілки й
    смуги; полів `branch`/`worktree` реєстр не має — ця межа названа в
    `docs/runbooks/branch-hygiene.md`;
  * `LocalReportTest` — звіт називає роботу, якої немає в `main`, і підказку
    вибирає зі структури рішення, а не з власної прози;
  * `ApiPrsTest` — форма PR із публічного GitHub API: джерело без gh, без
    токена й без keyring;
  * `CollectIntegrationTest` — справжній git-репозиторій: збір фактів, `--apply`
    проти справжніх гілки та worktree, відмова без PR-даних, відмова видаляти
    гілку, що рушила після збору фактів, і ігнорований вміст, який
    `git worktree remove` зніс би разом із деревом;
  * `LlmTest` — наратив не має права падати на недоступному endpoint.

Межі, названі прямо. У реєстрі сторожів (`scripts/guard_witnesses.py`) стоять
три записи з цього файлу — саме ті, що тримають право на видалення: перевірка
бази PR, перевірка резервувань і пін доведеного sha. Решта перевіряють форму
звіту, збір фактів і локальне прибирання; реєстр покриває НАЗВАНИХ, а не всіх.
Мережі в тестах немає: `gh` підставляється файлом `--pr-facts`, `map_api_pr`
перевіряється на фікстурі, а `LlmTest` стукає в закритий порт localhost.
"""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
from datetime import datetime, timedelta, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "branch-hygiene.py"

spec = importlib.util.spec_from_file_location("branch_hygiene", SCRIPT)
branch_hygiene = importlib.util.module_from_spec(spec)
spec.loader.exec_module(branch_hygiene)


NOW = datetime(2026, 10, 14, 12, 0, tzinfo=timezone.utc)


def make_branch(
    name,
    *,
    sha="a" * 40,
    ancestor=False,
    unique_patches=3,
    tagged_release=None,
    prs=None,
    age_days=1,
):
    return {
        "name": name,
        "sha": sha,
        "ancestor": ancestor,
        "unique_patches": unique_patches,
        "tagged_release": tagged_release,
        "tip_date": (NOW - timedelta(days=age_days)).isoformat(),
        "tip_author": "Samuel",
        "tip_subject": "feat: щось",
        "prs": list(prs or []),
    }


def make_pr(number, state, *, sha="a" * 40, draft=False, head="topic", updated_days=1,
            base="main", in_main_graph=True):
    stamp = (NOW - timedelta(days=updated_days)).isoformat()
    return {
        "inMainGraph": in_main_graph,
        "number": number,
        "headRefName": head,
        "baseRefName": base,
        "state": state,
        "isDraft": draft,
        "title": f"PR {number}",
        "updatedAt": stamp,
        "mergedAt": stamp if state == "MERGED" else None,
        "headRefOid": sha,
        "url": f"https://example.invalid/{number}",
    }


def decide(branch, **kwargs):
    return branch_hygiene.decide(branch, now=NOW, **kwargs)


def make_worktree(
    branch="t1",
    *,
    path="/repo/.worktrees/w1",
    main=False,
    current=False,
    external=False,
    dirty=0,
    fresh_hours=48.0,
    locked=False,
    detached=False,
):
    return {
        "path": path,
        "branch": branch,
        "main": main,
        "current": current,
        "external": external,
        "dirty": dirty,
        "fresh_hours": fresh_hours,
        "locked": locked,
        "detached": detached,
        "prunable": False,
        "touched_at": (NOW - timedelta(hours=fresh_hours)).isoformat(),
    }


def decide_local(branch, **kwargs):
    return branch_hygiene.decide_local(branch, now=NOW, **kwargs)


class DecideTest(unittest.TestCase):
    def test_ancestor_of_main_is_deleted(self):
        verdict = decide(make_branch("t1", ancestor=True, unique_patches=None))
        self.assertEqual(verdict["action"], "delete")
        self.assertEqual(verdict["rule"], "ancestor")

    def test_zero_unique_patches_is_deleted(self):
        verdict = decide(make_branch("t1", unique_patches=0))
        self.assertEqual(verdict["action"], "delete")
        self.assertEqual(verdict["rule"], "patch-absorbed")

    def test_merged_pr_with_same_tip_is_deleted(self):
        # Squash-злиття: tip не предок main, але PR злито саме з цим tip.
        verdict = decide(
            make_branch(
                "t1",
                sha="b" * 40,
                unique_patches=2,
                prs=[make_pr(11, "MERGED", sha="b" * 40)],
            )
        )
        self.assertEqual(verdict["action"], "delete")
        self.assertEqual(verdict["rule"], "pr-merged-sha")

    def test_merged_pr_outside_the_main_graph_is_not_proof(self):
        # Після force-push `main` PR лишається MERGED із тим самим tip, але його
        # вмісту в main немає: видалення гілки втратило б єдиний ref.
        verdict = decide(
            make_branch(
                "t1",
                sha="b" * 40,
                unique_patches=2,
                prs=[make_pr(11, "MERGED", sha="b" * 40, in_main_graph=False)],
            )
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("рухалася далі", verdict["reason"])

    def test_merged_pr_with_moved_branch_is_kept(self):
        verdict = decide(
            make_branch(
                "t1",
                sha="c" * 40,
                unique_patches=5,
                prs=[make_pr(11, "MERGED", sha="b" * 40)],
            )
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("рухалася далі", verdict["reason"])

    def test_pr_merged_into_a_release_lane_is_not_proof(self):
        # PR, злитий у release/*, не означає, що зміни вже в main: таку гілку
        # не можна видаляти, навіть коли tip не рухався.
        verdict = decide(
            make_branch(
                "fix/issue-900",
                sha="e" * 40,
                unique_patches=4,
                prs=[make_pr(21, "MERGED", sha="e" * 40, base="release/v1.5")],
            )
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("не в main", verdict["reason"])
        self.assertIn("release/v1.5", verdict["reason"])

    def test_open_pr_blocks_deletion_even_when_absorbed(self):
        verdict = decide(
            make_branch("t1", ancestor=True, prs=[make_pr(12, "OPEN", draft=True)])
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("#12", verdict["reason"])
        self.assertTrue(verdict["protected"] is False)

    def test_closed_unmerged_pr_is_kept(self):
        verdict = decide(
            make_branch("t1", unique_patches=4, prs=[make_pr(13, "CLOSED")])
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("без злиття", verdict["reason"])

    def test_branch_without_pr_and_unique_commits_is_kept(self):
        verdict = decide(make_branch("t1", age_days=30))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("PR немає", verdict["reason"])

    def test_protected_names_are_never_touched(self):
        for name in ("main", "master", "legal/bsl-1.1", "backup/pre-rewrite"):
            with self.subTest(name=name):
                verdict = decide(make_branch(name, ancestor=True))
                self.assertEqual(verdict["action"], "keep")
                self.assertTrue(verdict["protected"])

    def test_extra_protect_glob(self):
        verdict = decide(
            make_branch("wip/experiment", ancestor=True), protect=["wip/*"]
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertTrue(verdict["protected"])

    def test_release_branch_needs_a_tag_even_when_absorbed(self):
        verdict = decide(make_branch("release/v1.5", ancestor=True))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("без тега v*", verdict["reason"])

    def test_tagged_release_branch_is_deleted(self):
        verdict = decide(
            make_branch("release/v1.5", ancestor=True, tagged_release="v1.5")
        )
        self.assertEqual(verdict["action"], "delete")
        self.assertIn("v1.5", verdict["reason"])

    def test_release_branch_with_unique_commits_is_kept(self):
        verdict = decide(
            make_branch("release/v1.5", unique_patches=9, tagged_release="v1.5")
        )
        self.assertEqual(verdict["action"], "keep")


class ReportTest(unittest.TestCase):
    def build(self, branches, deleted=None, stale_days=14):
        facts = {
            "collected_at": NOW.isoformat(),
            "main": "main",
            "main_sha": "d" * 40,
            "pr_data": True,
            "branches": branches,
        }
        decisions = branch_hygiene.with_pr_freshness(
            branch_hygiene.decide_all(facts, now=NOW), stale_days, NOW
        )
        body = branch_hygiene.render_report(
            facts,
            decisions,
            deleted,
            stale_days=stale_days,
            narrative=None,
            now=NOW,
        )
        return body, decisions

    def test_stale_orphan_branch_is_recommended(self):
        body, _ = self.build([make_branch("codex/old", age_days=30)])
        self.assertIn("PR немає", body)
        self.assertIn("codex/old", body)

    def test_protected_and_release_branches_are_not_advised_to_open_pr(self):
        body, _ = self.build(
            [
                make_branch("legal/bsl-1.1", age_days=60),
                make_branch("release/v1.3.9", age_days=60, unique_patches=7),
            ]
        )
        self.assertNotIn("`legal/bsl-1.1` — PR немає", body)
        self.assertIn("релізна лінія без тега v*", body)

    def test_stale_draft_is_reported(self):
        body, decisions = self.build(
            [
                make_branch(
                    "codex/long-draft",
                    unique_patches=4,
                    age_days=20,
                    prs=[make_pr(700, "OPEN", draft=True, updated_days=20)],
                )
            ]
        )
        self.assertTrue(decisions[0]["stale_draft"])
        self.assertIn("драфт без руху", body)
        self.assertIn("#700", body)

    def test_a_fresh_pr_on_a_branch_with_an_old_one_is_not_stale(self):
        _, decisions = self.build(
            [
                make_branch(
                    "t1",
                    unique_patches=2,
                    prs=[
                        make_pr(701, "OPEN", updated_days=40),
                        make_pr(702, "OPEN", updated_days=2),
                    ],
                )
            ]
        )
        self.assertEqual(decisions[0]["pr_stale_days"], 2)
        self.assertFalse(decisions[0]["stale_open"])

    def test_fresh_open_pr_is_not_stale(self):
        _, decisions = self.build(
            [
                make_branch(
                    "t1",
                    unique_patches=2,
                    prs=[make_pr(701, "OPEN", updated_days=2)],
                )
            ]
        )
        self.assertFalse(decisions[0]["stale_draft"])
        self.assertFalse(decisions[0]["stale_open"])

    def test_deleted_section_lists_what_cleanup_did(self):
        branch = make_branch("t1", ancestor=True, unique_patches=None)
        body, decisions = self.build(
            [branch],
            deleted={
                "dry_run": False,
                "applied": [{"name": "t1", "ok": True, "error": ""}],
            },
        )
        self.assertEqual(decisions[0]["action"], "delete")
        self.assertIn("`t1` — видалено (поглинуто main", body)

    def test_failed_deletion_is_reported(self):
        body, _ = self.build(
            [make_branch("t1", ancestor=True, unique_patches=None)],
            deleted={
                "dry_run": False,
                "applied": [{"name": "t1", "ok": False, "error": "remote rejected"}],
            },
        )
        self.assertIn("видалити не вдалося: remote rejected", body)

    def test_missing_pr_data_is_disclosed(self):
        body, _ = self.build([make_branch("t1", unique_patches=2)])
        self.assertNotIn("Даних про PR немає", body)
        facts = {
            "collected_at": NOW.isoformat(),
            "main": "main",
            "main_sha": "d" * 40,
            "pr_data": False,
            "branches": [make_branch("t1", unique_patches=2)],
        }
        decisions = branch_hygiene.decide_all(facts, now=NOW)
        body = branch_hygiene.render_report(
            facts, decisions, None, stale_days=14, narrative=None, now=NOW
        )
        self.assertIn("Даних про PR немає", body)


class CollectIntegrationTest(unittest.TestCase):
    """Справжній git-репозиторій: перевіряємо, що git-факти зібрано правильно."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.origin = self.root / "origin.git"
        self.work = self.root / "work"
        self.run_git(self.root, "init", "--bare", "-q", str(self.origin))
        self.run_git(self.root, "init", "-q", "-b", "main", str(self.work))
        self.run_git(self.work, "config", "user.email", "test@example.invalid")
        self.run_git(self.work, "config", "user.name", "Test")
        (self.work / "file.txt").write_text("base\n", encoding="utf-8")
        self.commit("base")
        self.run_git(self.work, "remote", "add", "origin", str(self.origin))
        self.run_git(self.work, "push", "-q", "-u", "origin", "main")

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_git(self, cwd, *args, check=True):
        return subprocess.run(
            ["git", *args], cwd=cwd, check=check, capture_output=True, text=True
        )

    def commit(self, message):
        self.run_git(self.work, "add", "-A")
        self.run_git(self.work, "commit", "-q", "-m", message)

    def collect(self, *extra):
        facts_path = self.root / "facts.json"
        subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "collect",
                "--no-gh",
                "--json",
                str(facts_path),
                *extra,
            ],
            cwd=self.work,
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(facts_path.read_text(encoding="utf-8"))

    def test_collect_classifies_absorbed_and_unique_branches(self):
        # Поглинута гілка: коміт влито в main, tip став предком.
        self.run_git(self.work, "checkout", "-q", "-b", "absorbed")
        (self.work / "a.txt").write_text("a\n", encoding="utf-8")
        self.commit("feat: absorbed")
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "merge", "-q", "--no-ff", "-m", "merge absorbed", "absorbed")

        # Самобутня гілка: коміт лишається тільки на ній.
        self.run_git(self.work, "checkout", "-q", "-b", "unique")
        (self.work / "b.txt").write_text("b\n", encoding="utf-8")
        self.commit("feat: unique")
        self.run_git(self.work, "checkout", "-q", "main")

        # Релізна гілка з тегом.
        self.run_git(self.work, "branch", "release/v9.9")
        self.run_git(self.work, "tag", "v9.9", "main")

        self.run_git(self.work, "push", "-q", "origin", "--all")
        self.run_git(self.work, "push", "-q", "origin", "--tags")

        facts = self.collect()
        by_name = {branch["name"]: branch for branch in facts["branches"]}

        self.assertTrue(by_name["absorbed"]["ancestor"])
        self.assertIsNone(by_name["absorbed"]["unique_patches"])
        self.assertFalse(by_name["unique"]["ancestor"])
        self.assertEqual(by_name["unique"]["unique_patches"], 1)
        self.assertEqual(by_name["release/v9.9"]["tagged_release"], "v9.9")
        self.assertNotIn("main", by_name)

    def test_cleanup_dry_run_deletes_nothing_but_reports_absorbed(self):
        self.run_git(self.work, "checkout", "-q", "-b", "absorbed")
        (self.work / "a.txt").write_text("a\n", encoding="utf-8")
        self.commit("feat: absorbed")
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "merge", "-q", "--no-ff", "-m", "merge absorbed", "absorbed")
        self.run_git(self.work, "push", "-q", "origin", "--all")

        decision_path = self.root / "cleanup.json"
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "cleanup",
                "--facts",
                str(self.collect_path()),
                "--json",
                str(decision_path),
            ],
            cwd=self.work,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertIn("`absorbed` — буде видалено", result.stdout)

        decisions = json.loads(decision_path.read_text(encoding="utf-8"))
        by_name = {item["name"]: item for item in decisions["decisions"]}
        self.assertEqual(by_name["absorbed"]["action"], "delete")
        self.assertTrue(decisions["dry_run"])

        # Нічого не видалено: гілка досі на origin.
        refs = self.run_git(self.work, "ls-remote", "--heads", "origin").stdout
        self.assertIn("refs/heads/absorbed", refs)

    def collect_path(self, pr_facts=None):
        path = self.root / "facts-for-cleanup.json"
        injected = ["--pr-facts", str(pr_facts)] if pr_facts else []
        subprocess.run(
            [sys.executable, str(SCRIPT), "collect", "--no-gh", "--json", str(path),
             *injected],
            cwd=self.work,
            check=True,
            capture_output=True,
            text=True,
        )
        return path

    def make_branches(self):
        """Поглинута гілка + самобутня гілка, обидві на origin.

        Повертає (sha поглинутої гілки, merge-коміт у main).
        """
        self.run_git(self.work, "checkout", "-q", "-b", "absorbed")
        (self.work / "a.txt").write_text("a\n", encoding="utf-8")
        self.commit("feat: absorbed")
        sha = self.run_git(self.work, "rev-parse", "HEAD").stdout.strip()
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "merge", "-q", "--no-ff", "-m", "merge absorbed", "absorbed")
        merge_commit = self.run_git(self.work, "rev-parse", "HEAD").stdout.strip()
        self.run_git(self.work, "checkout", "-q", "-b", "unique")
        (self.work / "b.txt").write_text("b\n", encoding="utf-8")
        self.commit("feat: unique")
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        return sha, merge_commit

    def test_apply_deletes_only_the_absorbed_branch(self):
        sha, merge_commit = self.make_branches()
        pr_facts = self.write_pr_facts("absorbed", sha, merge_commit)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "cleanup",
                "--facts",
                str(self.collect_path(pr_facts)),
                "--apply",
            ],
            cwd=self.work,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertIn("`absorbed` — видалено", result.stdout)

        refs = self.run_git(self.work, "ls-remote", "--heads", "origin").stdout
        self.assertNotIn("refs/heads/absorbed", refs)
        self.assertIn("refs/heads/unique", refs)
        self.assertIn("refs/heads/main", refs)

    # --- локальне дерево ---

    def add_worktree(self, name, branch):
        """worktree всередині репозиторію — так, як .worktrees/ у справжньому."""
        path = self.work / ".worktrees" / name
        self.run_git(self.work, "worktree", "add", "-q", str(path), branch)
        return path

    def absorb(self, branch, filename):
        """Гілка, чий коміт злито в main merge-комітом: tip стає предком.

        Повертає sha merge-коміту — він у графі main, тож годиться на роль
        `mergeCommit` для підставлених PR-фактів.
        """
        self.run_git(self.work, "checkout", "-q", "-b", branch)
        (self.work / filename).write_text("x\n", encoding="utf-8")
        self.commit(f"feat: {branch}")
        sha = self.run_git(self.work, "rev-parse", "HEAD").stdout.strip()
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "merge", "-q", "--no-ff", "-m", f"merge {branch}", branch)
        # Доказ поглинання міряється щодо origin/main, а не щодо локального main.
        self.run_git(self.work, "push", "-q", "origin", "main")
        return sha

    def write_pr_facts(self, branch, sha, merge_commit, *, state="MERGED",
                       base="main", number=1):
        """Підставлені PR-факти: без них чистка тепер відмовляється працювати."""
        path = self.root / f"pr-{branch.replace('/', '-')}.json"
        path.write_text(json.dumps([{
            "number": number,
            "headRefName": branch,
            "baseRefName": base,
            "state": state,
            "isDraft": False,
            "title": f"PR {number}",
            "updatedAt": "2026-10-14T00:00:00Z",
            "mergedAt": "2026-10-14T00:00:00Z" if state == "MERGED" else None,
            "headRefOid": sha,
            "mergeCommit": {"oid": merge_commit},
            "url": f"https://example.invalid/{number}",
            "headRepositoryOwner": {"login": "test"},
        }], ensure_ascii=False), encoding="utf-8")
        return path

    def main_sha(self):
        return self.run_git(self.work, "rev-parse", "main").stdout.strip()

    def collect_local(self, *extra, pr_facts=None):
        facts_path = self.root / "local-facts.json"
        injected = ["--pr-facts", str(pr_facts)] if pr_facts else []
        subprocess.run(
            [sys.executable, str(SCRIPT), "collect-local", "--no-gh",
             "--json", str(facts_path), *injected, *extra],
            cwd=self.work, check=True, capture_output=True, text=True,
        )
        return json.loads(facts_path.read_text(encoding="utf-8"))

    def run_local(self, *extra, pr_facts=None):
        injected = ["--pr-facts", str(pr_facts)] if pr_facts else []
        return subprocess.run(
            [sys.executable, str(SCRIPT), "local", "--no-gh", "--no-out",
             *injected, *extra],
            cwd=self.work, check=False, capture_output=True, text=True,
        )

    def test_collect_local_sees_worktrees_and_branches(self):
        self.absorb("merged", "a.txt")
        self.run_git(self.work, "checkout", "-q", "-b", "wip")
        (self.work / "b.txt").write_text("b\n", encoding="utf-8")
        self.commit("feat: wip")
        self.run_git(self.work, "checkout", "-q", "main")
        worktree = self.add_worktree("wt-wip", "wip")
        (worktree / "dirty.txt").write_text("x\n", encoding="utf-8")

        facts = self.collect_local()
        by_name = {branch["name"]: branch for branch in facts["branches"]}

        self.assertTrue(by_name["merged"]["ancestor"])
        self.assertFalse(by_name["wip"]["ancestor"])
        self.assertEqual(by_name["wip"]["worktree"]["branch"], "wip")
        self.assertEqual(by_name["wip"]["worktree"]["dirty"], 1)
        self.assertTrue(by_name["wip"]["worktree"]["fresh_hours"] < 1)
        self.assertEqual(len([w for w in facts["worktrees"] if w["main"]]), 1)
        self.assertEqual(len([w for w in facts["worktrees"] if w["current"]]), 1)

    def test_local_apply_removes_a_clean_absorbed_worktree_and_branch(self):
        sha = self.absorb("merged", "a.txt")
        worktree = self.add_worktree("wt-merged", "merged")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("`merged` — видалено разом із worktree", result.stdout)
        self.assertFalse(worktree.exists())
        self.assertNotEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/merged",
                         check=False).returncode, 0,
        )
        # робоча гілка й main лишилися
        self.assertEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/main").returncode, 0
        )

    def test_local_apply_respects_a_reservation(self):
        sha = self.absorb("merged", "a.txt")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())
        claims = self.work / ".git" / "agent-work-claims"
        claims.mkdir(exist_ok=True)
        (claims / "7.json").write_text(
            json.dumps({"issue": "7", "status": "implementing", "branch": "merged"}),
            encoding="utf-8",
        )

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("резервування #7", result.stdout)
        self.assertEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/merged").returncode, 0
        )

    def test_local_apply_keeps_a_dirty_worktree(self):
        sha = self.absorb("merged", "a.txt")
        worktree = self.add_worktree("wt-merged", "merged")
        (worktree / "dirty.txt").write_text("x\n", encoding="utf-8")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertIn("worktree брудний", result.stdout)
        self.assertTrue(worktree.exists())
        self.assertEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/merged").returncode, 0
        )

    def test_a_worktree_whose_directory_is_gone_does_not_block_the_branch(self):
        import shutil

        sha = self.absorb("merged", "a.txt")
        worktree = self.add_worktree("wt-merged", "merged")
        shutil.rmtree(worktree)
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())

        facts = self.collect_local(pr_facts=pr_facts)
        by_name = {branch["name"]: branch for branch in facts["branches"]}
        self.assertTrue(by_name["merged"]["worktree"]["prunable"])

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/merged",
                         check=False).returncode, 0,
        )

    def test_report_cli_runs_end_to_end(self):
        """Саме цього тесту бракувало, коли `report` упав на сигнатурі `load_facts`."""
        facts_path = self.root / "report-facts.json"
        facts_path.write_text(json.dumps({
            "collected_at": "2026-10-14T00:00:00+00:00",
            "main": "main",
            "main_sha": "d" * 40,
            "pr_source": "facts",
            "pr_data": True,
            "pr_count": 1,
            "branches": [{
                "name": "t1", "sha": "a" * 40, "ancestor": True,
                "unique_patches": None, "tagged_release": None,
                "tip_date": "2026-10-14T00:00:00+00:00", "tip_author": "T",
                "tip_subject": "feat: x", "prs": [],
            }],
        }, ensure_ascii=False), encoding="utf-8")

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "report", "--facts", str(facts_path), "--no-llm"],
            cwd=self.work, check=False, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("branch-hygiene-report", result.stdout)
        self.assertIn("## Що зроблено", result.stdout)

    def test_cleanup_refuses_when_there_is_no_pr_data(self):
        """Відмова без PR-даних стосується й віддаленої половини, не лише локальної."""
        self.make_branches()
        facts_path = self.collect_path()

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "cleanup", "--facts", str(facts_path), "--apply"],
            cwd=self.work, check=False, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("Нічого не видалено", result.stdout)
        self.assertNotIn("— видалено:", result.stdout)
        refs = self.run_git(self.work, "ls-remote", "--heads", "origin").stdout
        self.assertIn("refs/heads/absorbed", refs)

    def test_report_upsert_reaches_gh_with_the_report_body(self):
        """Нічний issue живе на цьому шляху — тож він мусить мати прогін, не обіцянку."""
        import os
        import stat

        facts_path = self.root / "upsert-facts.json"
        facts_path.write_text(json.dumps({
            "collected_at": "2026-10-14T00:00:00+00:00",
            "main": "main", "main_sha": "d" * 40,
            "pr_source": "facts", "pr_data": True, "pr_count": 1,
            "branches": [{
                "name": "t1", "sha": "a" * 40, "ancestor": True,
                "unique_patches": None, "tagged_release": None,
                "tip_date": "2026-10-14T00:00:00+00:00", "tip_author": "T",
                "tip_subject": "feat: x", "prs": [],
            }],
        }, ensure_ascii=False), encoding="utf-8")

        calls = self.root / "gh-calls.log"
        stdin_copy = self.root / "gh-stdin.log"
        fake_gh = self.root / "bin" / "gh"
        fake_gh.parent.mkdir(exist_ok=True)
        fake_gh.write_text(
            "#!/usr/bin/env bash\n"
            'printf "%s\\n" "$*" >> "$GH_CALLS"\n'
            "for arg in \"$@\"; do\n"
            "  if [ \"$arg\" = \"-\" ]; then cat >> \"$GH_STDIN\"; fi\n"
            "done\n"
            'case "$1 $2" in\n'
            '  "issue list") echo "[]" ;;\n'
            '  "issue create") echo "https://github.com/x/y/issues/999" ;;\n'
            '  "issue edit") echo "edited" ;;\n'
            '  "label create") echo "label" ;;\n'
            "esac\n",
            encoding="utf-8",
        )
        fake_gh.chmod(fake_gh.stat().st_mode | stat.S_IXUSR)

        environment = dict(os.environ)
        environment["PATH"] = f"{fake_gh.parent}:{environment['PATH']}"
        environment["GH_CALLS"] = str(calls)
        environment["GH_STDIN"] = str(stdin_copy)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "report", "--facts", str(facts_path),
             "--no-llm", "--upsert"],
            cwd=self.work, check=False, capture_output=True, text=True, env=environment,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        logged = calls.read_text(encoding="utf-8")
        self.assertIn("issue create", logged)
        self.assertIn("branch-hygiene", logged)
        self.assertIn("branch-hygiene-report", stdin_copy.read_text(encoding="utf-8"))

    def test_apply_refuses_when_there_is_no_pr_data(self):
        """Без даних про PR захист «відкритий PR не чіпаємо» зникає — тож анічого."""
        sha = self.absorb("merged", "a.txt")
        self.run_git(self.work, "push", "-q", "origin", "--all")

        result = self.run_local("--apply", "--fresh-hours", "0")
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("Даних про PR немає", result.stdout)
        self.assertEqual(
            self.run_git(self.work, "show-ref", "--verify", "refs/heads/merged").returncode, 0
        )
        self.assertTrue(sha)

    def test_cleanup_refuses_a_branch_that_moved_after_the_facts(self):
        """Видалення пінить доведений sha: гілка рушила — відмова, не втрата."""
        sha, merge_commit = self.make_branches()
        pr_facts = self.write_pr_facts("absorbed", sha, merge_commit)
        facts_path = self.collect_path(pr_facts)

        self.run_git(self.work, "checkout", "-q", "absorbed")
        (self.work / "later.txt").write_text("later\n", encoding="utf-8")
        self.commit("feat: пізніша робота")
        self.run_git(self.work, "push", "-q", "origin", "absorbed")
        self.run_git(self.work, "checkout", "-q", "main")

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "cleanup", "--facts", str(facts_path), "--apply"],
            cwd=self.work, check=False, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 1, result.stdout)
        refs = self.run_git(self.work, "ls-remote", "--heads", "origin").stdout
        self.assertIn("refs/heads/absorbed", refs)
        self.assertIn("НЕ видалено", result.stderr + result.stdout)

    def test_local_apply_keeps_a_worktree_with_ignored_content(self):
        """`worktree remove` без --force зносить і ігнороване — сторож цього не робить."""
        self.run_git(self.work, "checkout", "-q", "main")
        (self.work / ".gitignore").write_text("local.properties\nbuild/\n", encoding="utf-8")
        self.commit("chore: ignore")
        self.run_git(self.work, "push", "-q", "origin", "main")

        sha = self.absorb("merged", "a.txt")
        worktree = self.add_worktree("wt-merged", "merged")
        (worktree / "local.properties").write_text("sdk.dir=/secret\n", encoding="utf-8")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())

        facts = self.collect_local(pr_facts=pr_facts)
        by_name = {branch["name"]: branch for branch in facts["branches"]}
        self.assertEqual(by_name["merged"]["worktree"]["dirty"], 0)
        self.assertEqual(by_name["merged"]["worktree"]["ignored_blocking"], ["local.properties"])

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertIn("ігнорований вміст", result.stdout)
        self.assertTrue(worktree.exists())
        self.assertTrue((worktree / "local.properties").exists())

    def test_local_apply_ignores_build_residue(self):
        """Відходи збірки — не привід лишати дерево."""
        self.run_git(self.work, "checkout", "-q", "main")
        (self.work / ".gitignore").write_text("build/\n", encoding="utf-8")
        self.commit("chore: ignore")
        self.run_git(self.work, "push", "-q", "origin", "main")

        sha = self.absorb("merged", "a.txt")
        worktree = self.add_worktree("wt-merged", "merged")
        (worktree / "build").mkdir()
        (worktree / "build" / "out.txt").write_text("x\n", encoding="utf-8")
        self.run_git(self.work, "push", "-q", "origin", "--all")
        pr_facts = self.write_pr_facts("merged", sha, self.main_sha())

        result = self.run_local("--apply", "--fresh-hours", "0", pr_facts=pr_facts)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(worktree.exists())

    def test_missing_origin_main_is_a_clear_error(self):
        self.run_git(self.work, "update-ref", "-d", "refs/remotes/origin/main")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "collect", "--no-gh", "--json", "-"],
            cwd=self.work,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("git fetch origin", result.stderr)


class LocalDecideTest(unittest.TestCase):
    def absorbed(self, name="t1", **kwargs):
        branch = make_branch(name, ancestor=True, unique_patches=None)
        branch["reflog_at"] = (NOW - timedelta(days=2)).isoformat()
        branch["worktree"] = kwargs.pop("worktree", None)
        return decide_local(branch, **kwargs)

    def test_proven_absorbed_branch_is_deleted(self):
        verdict = self.absorbed()
        self.assertEqual(verdict["action"], "delete")
        self.assertEqual(verdict["proof"], "ancestor")

    def test_fresh_worktree_is_left_for_the_next_night(self):
        verdict = self.absorbed(worktree=make_worktree(fresh_hours=1.5))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("наступної ночі", verdict["reason"])

    def test_dirty_worktree_is_left_alone(self):
        verdict = self.absorbed(worktree=make_worktree(dirty=3, fresh_hours=48))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("брудний", verdict["reason"])

    def test_reserved_branch_is_left_alone(self):
        verdict = self.absorbed(
            name="t1301-asymmetry", claims=[{"issue": "1301", "status": "implementing"}]
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("#1301", verdict["reason"])

    def test_reservation_matches_by_explicit_branch_and_worktree(self):
        verdict = self.absorbed(
            worktree=make_worktree(),
            claims=[{"issue": "77", "branch": "t1", "worktree": "/repo/.worktrees/w1"}],
        )
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("#77", verdict["reason"])

    def test_main_and_current_worktrees_are_never_removed(self):
        for flags in ({"main": True}, {"current": True}):
            with self.subTest(**flags):
                verdict = self.absorbed(worktree=make_worktree(**flags))
                self.assertEqual(verdict["action"], "keep")

    def test_worktree_outside_the_repository_is_reported_not_removed(self):
        verdict = self.absorbed(worktree=make_worktree(external=True))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("поза репозиторієм", verdict["reason"])

    def test_branch_freezes_while_its_worktree_is_locked(self):
        verdict = self.absorbed(worktree=make_worktree(locked=True))
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("залочено", verdict["reason"])

    def test_branch_with_unpublished_work_is_kept(self):
        branch = make_branch("t9", unique_patches=2, age_days=30)
        branch["reflog_at"] = (NOW - timedelta(days=30)).isoformat()
        branch["worktree"] = None
        verdict = decide_local(branch)
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("PR немає", verdict["reason"])

    def test_open_pr_still_blocks_local_deletion(self):
        branch = make_branch("t9", ancestor=True, prs=[make_pr(5, "OPEN")])
        branch["reflog_at"] = (NOW - timedelta(days=30)).isoformat()
        branch["worktree"] = None
        verdict = decide_local(branch)
        self.assertEqual(verdict["action"], "keep")
        self.assertIn("#5", verdict["reason"])

    def test_protected_local_branch_is_kept(self):
        branch = make_branch("legal/bsl-1.1", ancestor=True, unique_patches=None)
        branch["reflog_at"] = (NOW - timedelta(days=30)).isoformat()
        branch["worktree"] = None
        verdict = decide_local(branch)
        self.assertEqual(verdict["action"], "keep")
        self.assertTrue(verdict["protected"])


class ClaimsTest(unittest.TestCase):
    def test_issue_number_inside_the_branch_name_is_found(self):
        records = [{"issue": "1185"}, {"issue": "1301"}]
        self.assertEqual(branch_hygiene.claimed_by(records, "docs/issue-1185-local"), ["1185"])
        self.assertEqual(branch_hygiene.claimed_by(records, "t1301-asymmetry"), ["1301"])

    def test_a_number_inside_another_number_is_not_a_match(self):
        self.assertEqual(branch_hygiene.claimed_by([{"issue": "301"}], "t1301-asymmetry"), [])
        self.assertEqual(branch_hygiene.claimed_by([{"issue": "1"}], "t1301-asymmetry"), [])

    def test_issue_number_inside_the_worktree_path_is_found(self):
        # Смуги звуться `w<номер>`: гілка без номера у назві все одно захищена,
        # якщо тікет зарезервовано.
        records = [{"issue": "1187"}]
        self.assertEqual(
            branch_hygiene.claimed_by(records, "feat/local-book-duration",
                                      "/repo/.worktrees/w1187"), ["1187"]
        )

    def test_explicit_branch_and_worktree_fields_win(self):
        records = [{"issue": "42", "branch": "wip", "worktree": "/w/wip"}]
        self.assertEqual(branch_hygiene.claimed_by(records, "wip"), ["42"])
        self.assertEqual(
            branch_hygiene.claimed_by(records, "other", "/w/wip"), ["42"]
        )
        self.assertEqual(branch_hygiene.claimed_by(records, "other"), [])


class LocalReportTest(unittest.TestCase):
    def render(self, branches, *, applied=None, dry_run=True, pr_data=True):
        facts = {
            "collected_at": NOW.isoformat(),
            "main": "main",
            "main_sha": "d" * 40,
            "pr_data": pr_data,
            "common_dir": "/repo/.git",
            "claims": [],
            # Так само, як у справжніх фактах: worktree живуть і в гілці, і в списку.
            "worktrees": [b["worktree"] for b in branches if b.get("worktree")],
            "branches": branches,
        }
        decisions = [
            branch_hygiene.decide_local(branch, now=NOW) for branch in branches
        ]
        return branch_hygiene.render_local_report(
            facts, decisions, applied or [], dry_run=dry_run, now=NOW, fresh_hours=6
        ), decisions

    def local_branch(self, name, worktree=None, **kwargs):
        branch = make_branch(name, **kwargs)
        branch["reflog_at"] = (NOW - timedelta(days=2)).isoformat()
        branch["worktree"] = worktree
        return branch

    def test_unpublished_work_is_named_with_a_command(self):
        body, _ = self.render([self.local_branch("t9", unique_patches=2, age_days=30)])
        self.assertIn("## Робота, якої немає в main", body)
        self.assertIn("`t9`", body)
        self.assertIn("git push -u origin t9", body)

    def test_hint_for_a_moved_branch_comes_from_structure(self):
        # Підказка більше не розбирає власну прозу: досить мати PR, злитий у
        # main, і відсутній доказ поглинання.
        branch = self.local_branch(
            "t9", unique_patches=4, prs=[make_pr(1147, "MERGED", sha="b" * 40)]
        )
        body, decisions = self.render([branch])
        self.assertEqual(decisions[0]["merged_into_main"][0]["number"], 1147)
        self.assertIn("git log origin/main..t9", body)

    def test_moved_branch_after_a_merged_pr_is_named(self):
        branch = self.local_branch(
            "t9", unique_patches=4, prs=[make_pr(1147, "MERGED", sha="b" * 40)]
        )
        body, _ = self.render([branch])
        self.assertIn("PR #1147 злито в main, але гілка рухалася далі", body)
        self.assertIn("git log origin/main..t9", body)

    def test_dirty_worktree_is_listed_with_a_command(self):
        body, _ = self.render([self.local_branch("t9", worktree=make_worktree(dirty=2))])
        self.assertIn("## Брудні worktree", body)
        self.assertIn("git worktree remove /repo/.worktrees/w1", body)

    def test_dry_run_lists_what_would_be_removed(self):
        body, _ = self.render([self.local_branch("t9", ancestor=True, unique_patches=None)])
        self.assertIn("Сухий прогін", body)
        self.assertIn("`t9`", body)

    def test_missing_pr_data_is_disclosed(self):
        body, _ = self.render(
            [self.local_branch("t9", unique_patches=2)], pr_data=False
        )
        self.assertIn("Даних про PR немає", body)


class ApiPrsTest(unittest.TestCase):
    def test_merged_pr_maps_to_the_same_shape_as_gh(self):
        mapped = branch_hygiene.map_api_pr(
            {
                "number": 1306,
                "state": "closed",
                "merged_at": "2026-10-14T18:20:00Z",
                "draft": False,
                "title": "fix(scripts): асиметрія",
                "updated_at": "2026-10-14T18:20:00Z",
                "html_url": "https://example.invalid/1306",
                "base": {"ref": "main"},
                "head": {
                    "ref": "t1301-asymmetry",
                    "sha": "a" * 40,
                    "repo": {"owner": {"login": "Samuel-Ku"}},
                },
            }
        )
        self.assertEqual(mapped["state"], "MERGED")
        self.assertEqual(mapped["baseRefName"], "main")
        self.assertEqual(mapped["headRefName"], "t1301-asymmetry")
        self.assertEqual(mapped["headRefOid"], "a" * 40)

    def test_closed_unmerged_pr_is_closed(self):
        mapped = branch_hygiene.map_api_pr(
            {
                "number": 1,
                "state": "closed",
                "merged_at": None,
                "base": {"ref": "main"},
                "head": {"ref": "x", "sha": "b" * 40, "repo": {"owner": {"login": "o"}}},
            }
        )
        self.assertEqual(mapped["state"], "CLOSED")

    def test_pr_from_a_deleted_fork_is_dropped(self):
        self.assertIsNone(
            branch_hygiene.map_api_pr(
                {"number": 2, "state": "open", "base": {"ref": "main"}, "head": {"ref": "x"}}
            )
        )

    def test_unattributed_pr_is_dropped_by_normalize(self):
        raw = [
            {
                "number": 3,
                "headRefName": "t1",
                "baseRefName": "main",
                "state": "MERGED",
                "headRepositoryOwner": {"login": None},
            }
        ]
        self.assertEqual(branch_hygiene.normalize_prs(raw, "Samuel-Ku"), [])


class DeleteBranchTest(unittest.TestCase):
    def test_a_failed_origin_push_falls_back_to_https_with_gh(self):
        calls = []

        def fake_git(*args, **kwargs):
            calls.append(("git", list(args)))
            return subprocess.CompletedProcess(args, 1, "", "ssh: Bad owner or permissions")

        def fake_run(cmd, **kwargs):
            calls.append(("run", list(cmd)))
            if "credential.helper=!gh auth git-credential" in cmd:
                return subprocess.CompletedProcess(cmd, 0, "", "")
            return subprocess.CompletedProcess(cmd, 1, "", "nope")

        with unittest.mock.patch.object(branch_hygiene, "git", fake_git), \
                unittest.mock.patch.object(branch_hygiene, "run", fake_run), \
                unittest.mock.patch.object(branch_hygiene, "repo_slug",
                                           lambda: "owner/repo"):
            ok, error = branch_hygiene.delete_branch("t1", "a" * 40)

        self.assertTrue(ok, error)
        self.assertTrue(any("credential.helper" in " ".join(cmd) for _, cmd in calls))
        self.assertIn("--force-with-lease=refs/heads/t1:" + "a" * 40,
                      " ".join(calls[1][1]))

    def test_both_paths_failing_reports_the_error(self):
        def fake_git(*args, **kwargs):
            return subprocess.CompletedProcess(args, 1, "", "ssh: Bad owner")

        def fake_run(cmd, **kwargs):
            return subprocess.CompletedProcess(cmd, 1, "", "gh: not logged in")

        with unittest.mock.patch.object(branch_hygiene, "git", fake_git), \
                unittest.mock.patch.object(branch_hygiene, "run", fake_run), \
                unittest.mock.patch.object(branch_hygiene, "repo_slug",
                                           lambda: "owner/repo"):
            ok, error = branch_hygiene.delete_branch("t1", "a" * 40)

        self.assertFalse(ok)
        self.assertIn("not logged in", error)


class CleanupSummaryTest(unittest.TestCase):
    def test_a_refusal_does_not_claim_deletions(self):
        decisions = [{
            "name": "t1", "action": "delete", "reason": "поглинуто main",
            "sha": "a" * 40, "protected": False, "release": False, "prs": [],
            "open_prs": [], "merged_prs": [], "closed_prs": [],
            "merged_into_main": [], "proof": "ancestor", "age_days": 1,
        }]
        body = branch_hygiene.render_cleanup_summary(
            decisions, [], dry_run=False, refused="немає PR-даних"
        )
        self.assertIn("Нічого не видалено", body)
        self.assertIn("Поглинутих гілок, які лишилися: 1", body)
        self.assertNotIn("видалено:", body)


class LlmTest(unittest.TestCase):
    def test_unreachable_endpoint_returns_none_instead_of_raising(self):
        narrative = branch_hygiene.llm_narrative(
            {"branches": []},
            token="not-a-real-token",
            url="http://127.0.0.1:9/v1",
            model="deepseek-v4.1-flash",
            session="test",
            timeout=5,
        )
        self.assertIsNone(narrative)


if __name__ == "__main__":
    unittest.main()
