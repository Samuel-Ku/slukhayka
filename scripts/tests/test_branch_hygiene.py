import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
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
            base="main"):
    stamp = (NOW - timedelta(days=updated_days)).isoformat()
    return {
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

    def run_git(self, cwd, *args):
        return subprocess.run(
            ["git", *args], cwd=cwd, check=True, capture_output=True, text=True
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

    def collect_path(self):
        path = self.root / "facts-for-cleanup.json"
        subprocess.run(
            [sys.executable, str(SCRIPT), "collect", "--no-gh", "--json", str(path)],
            cwd=self.work,
            check=True,
            capture_output=True,
            text=True,
        )
        return path

    def make_branches(self):
        """Поглинута гілка + самобутня гілка, обидві на origin."""
        self.run_git(self.work, "checkout", "-q", "-b", "absorbed")
        (self.work / "a.txt").write_text("a\n", encoding="utf-8")
        self.commit("feat: absorbed")
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "merge", "-q", "--no-ff", "-m", "merge absorbed", "absorbed")
        self.run_git(self.work, "checkout", "-q", "-b", "unique")
        (self.work / "b.txt").write_text("b\n", encoding="utf-8")
        self.commit("feat: unique")
        self.run_git(self.work, "checkout", "-q", "main")
        self.run_git(self.work, "push", "-q", "origin", "--all")

    def test_apply_deletes_only_the_absorbed_branch(self):
        self.make_branches()
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "cleanup",
                "--facts",
                str(self.collect_path()),
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
