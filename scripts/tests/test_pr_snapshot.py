import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "pr-snapshot.py"

SHA = {
    "base": "b" * 40,
    "head": "a" * 40,
}


def clean_git_env():
    """A pre-commit hook may export GIT_DIR/GIT_WORK_TREE; the temp clone must not see them."""
    return {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}


def run(args, cwd=None):
    # GIT_DIR from a pre-commit hook would point the script at the OUTER repo,
    # not at the temp clone the remote-derivation test creates.
    return subprocess.run(
        ["python3", str(SCRIPT), *args],
        capture_output=True,
        text=True,
        cwd=cwd or REPO_ROOT,
        env=clean_git_env(),
    )


def pull(number, *, draft=False, rollup="default", issues=(), review=None):
    if rollup == "default":
        rollup = [{"state": "SUCCESS"}]
    return {
        "number": number,
        "title": f"PR {number}",
        "isDraft": draft,
        "baseRefName": "main",
        "baseRefOid": SHA["base"],
        "headRefOid": f"{number:040d}",
        "url": f"https://github.com/Samuel-Ku/slukhayka/pull/{number}",
        "statusCheckRollup": rollup,
        "closingIssuesReferences": [{"number": issue} for issue in issues],
        "labels": [],
        "reviewDecision": review,
    }


def fake_gh(directory, pulls, *, comment_output=None, comment_exit=0):
    """A gh stand-in for the shapes the real CLI returns (fixtures cannot)."""
    path = Path(directory) / "fake-gh"
    body = [
        "#!/bin/sh",
        'case "$1" in',
        "  pr)",
        "    cat <<'PULLS'",
        json.dumps(pulls),
        "PULLS",
        "    ;;",
        "  api)",
    ]
    if comment_output is not None:
        body.append("    cat <<'COMMENT'")
        body.append(comment_output)
        body.append("COMMENT")
        body.append(f"    exit {comment_exit}")
    else:
        body.append("    exit 1")
    body += ["    ;;", "  *) exit 1 ;;", "esac"]
    path.write_text("\n".join(body) + "\n", encoding="utf-8")
    path.chmod(0o755)
    return str(path)


class PrSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.claims = self.root / "claims"
        self.claims.mkdir()
        self.claims.joinpath("1242.json").write_text(json.dumps({
            "issue": 1242,
            "ownerThread": "alice",
            "reservation": "importplan",
            "status": "prepared-reserved",
        }), encoding="utf-8")

    def fixture(self, pulls, comments=None, truncated=None):
        path = self.root / "pulls.json"
        payload = {"pulls": pulls}
        if comments is not None:
            payload["comments"] = comments
        if truncated is not None:
            payload["truncated"] = truncated
        path.write_text(json.dumps(payload), encoding="utf-8")
        return str(path)

    def base_args(self, fixture):
        return ["--repo", "Samuel-Ku/slukhayka", "--fixture", fixture,
                "--claims-dir", str(self.claims)]

    def test_compact_mode_reports_state_checks_issues_and_reservation(self):
        fixture = self.fixture([
            pull(1, draft=True, rollup=[{"state": "PENDING"}], issues=[1242]),
            pull(2, rollup=[
                {"state": "SUCCESS"},
                {"conclusion": "FAILURE"},
                {"state": "IN_PROGRESS"},
                {"state": "SKIPPED"},
            ], issues=[1240]),
            pull(3, rollup=None),
        ], comments={"1242": {
            "user": {"login": "alice"},
            "created_at": "2026-10-24T09:00:00Z",
        }})

        completed = run(self.base_args(fixture))

        self.assertEqual(0, completed.returncode, completed.stderr)
        output = completed.stdout
        self.assertIn("prs=3", output)
        self.assertIn(f"#1 draft base=main@{SHA['base']} head={1:040d}", output)
        self.assertIn("checks=0ok/0fail/1pend", output)
        self.assertIn("issues=#1242 reservation=alice:prepared-reserved "
                      "last-comment=#1242:alice@2026-10-24", output)
        self.assertIn("divergence=both", output)
        self.assertIn("#2 ready base=main", output)
        self.assertIn("checks=1ok/1fail/1pend/1skip", output)
        self.assertIn("issues=#1240 reservation=absent last-comment=#1240:none", output)
        self.assertIn("divergence=none", output)
        self.assertIn("#3 ready base=main", output)
        self.assertIn("checks=unknown issues=none reservation=absent", output)

    def test_empty_rollup_is_unknown_not_all_green(self):
        fixture = self.fixture([pull(4, rollup=[])])

        completed = run(self.base_args(fixture))

        self.assertIn("checks=unknown", completed.stdout)
        self.assertNotIn("0ok/0fail/0pend", completed.stdout)

    def test_legacy_status_context_error_is_a_failure(self):
        fixture = self.fixture([pull(5, rollup=[{"state": "ERROR"}])])

        completed = run(self.base_args(fixture))

        self.assertIn("checks=0ok/1fail/0pend", completed.stdout)

    def test_comment_only_divergence_is_explicit(self):
        # GitHub says work happened on the linked issue; the local registry is silent.
        fixture = self.fixture([pull(6, issues=[1240])], comments={"1240": {
            "user": {"login": "bob"},
            "created_at": "2026-10-24T09:00:00Z",
        }})

        completed = run(self.base_args(fixture))

        self.assertIn("reservation=absent", completed.stdout)
        self.assertIn("divergence=comment-only", completed.stdout)

    def test_local_only_divergence_is_explicit(self):
        fixture = self.fixture([pull(7, issues=[1242])], comments={})

        completed = run(self.base_args(fixture))

        self.assertIn("last-comment=#1242:none", completed.stdout)
        self.assertIn("divergence=local-only", completed.stdout)

    def test_zero_comment_shape_from_gh_is_none_not_invented(self):
        # `gh api ... --jq '.[-1]|…'` returns a null-filled object for no comments.
        gh = fake_gh(
            self.root,
            [pull(8, issues=[1242])],
            comment_output='{"created_at":null,"user":{"login":null}}',
        )

        completed = run(["--repo", "Samuel-Ku/slukhayka",
                         "--claims-dir", str(self.claims), "--gh", gh])

        self.assertIn("last-comment=#1242:none", completed.stdout)
        self.assertNotIn("None@", completed.stdout)

    def test_unavailable_comments_are_explicit(self):
        gh = fake_gh(self.root, [pull(9, issues=[1242])], comment_exit=1)

        completed = run(["--repo", "Samuel-Ku/slukhayka",
                         "--claims-dir", str(self.claims), "--gh", gh])

        self.assertIn("last-comment=#1242:unavailable", completed.stdout)

    def test_repo_is_derived_from_the_git_remote(self):
        work = self.root / "clone"
        work.mkdir()
        env = clean_git_env()
        subprocess.run(["git", "init", "-q"], cwd=work, check=True, env=env)
        subprocess.run(
            ["git", "remote", "add", "origin", "git@github.com:Owner/Name.git"],
            cwd=work, check=True, env=env,
        )
        fixture = self.fixture([pull(10)])

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims)], cwd=work)

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("repo=Owner/Name", completed.stdout)

    def test_truncation_at_the_gh_limit_is_announced(self):
        fixture = self.fixture([pull(number) for number in range(1, 1001)])

        completed = run(self.base_args(fixture))

        self.assertIn("prs=1000", completed.stdout)
        self.assertIn("truncated: gh returned at least 1000 open PRs", completed.stdout)

    def test_full_payload_is_written_separately_from_stdout(self):
        fixture = self.fixture([pull(1, issues=[1242])], comments={"1242": {
            "user": {"login": "alice"},
            "created_at": "2026-10-24T09:00:00Z",
        }})
        full = self.root / "full.json"

        completed = run([*self.base_args(fixture), "--full-json", str(full)])

        self.assertNotIn('"pullRequests"', completed.stdout)
        saved = json.loads(full.read_text(encoding="utf-8"))
        self.assertEqual(1, len(saved["pullRequests"]))
        self.assertIn("capturedAt", saved)
        self.assertEqual("alice@2026-10-24", saved["comments"]["1242"])

    def test_full_payload_is_not_written_when_github_is_unavailable(self):
        full = self.root / "full.json"

        completed = run(["--repo", "Samuel-Ku/slukhayka", "--gh", "/bin/false",
                         "--full-json", str(full)])

        self.assertEqual(3, completed.returncode)
        self.assertFalse(full.exists())

    def test_pagination_does_not_truncate_the_list(self):
        fixture = self.fixture([pull(number) for number in range(1, 151)])

        completed = run(self.base_args(fixture))

        self.assertIn("prs=150", completed.stdout)
        self.assertEqual(150, completed.stdout.count("\n#"))
        self.assertNotIn("truncated:", completed.stdout)

    def test_head_drift_is_explicit_and_nonzero(self):
        fixture = self.fixture([pull(5)])

        same = run([*self.base_args(fixture), "--expect-head", f"5={5:040d}"])
        moved = run([*self.base_args(fixture), "--expect-head", "5=deadbeef"])

        self.assertEqual(0, same.returncode, same.stdout)
        self.assertEqual(4, moved.returncode, moved.stdout)
        self.assertIn("head-drift: #5", moved.stdout)

    def test_invalid_expect_head_is_refused(self):
        fixture = self.fixture([pull(5)])

        for bad in ("five=deadbeef", "5=", "5=not-a-sha"):
            completed = run([*self.base_args(fixture), "--expect-head", bad])
            self.assertEqual(2, completed.returncode, bad)
            self.assertNotIn("Traceback", completed.stderr)

    def test_missing_pr_is_a_drift_not_a_clean_state(self):
        fixture = self.fixture([pull(5)])

        completed = run([*self.base_args(fixture), "--expect-head", "999=deadbeef"])

        self.assertEqual(4, completed.returncode)
        self.assertIn("head-drift: #999 is not open any more", completed.stdout)

    def test_unreadable_registry_does_not_crash(self):
        broken = self.claims / "broken.json"
        broken.write_text("not a claim", encoding="utf-8")
        fixture = self.fixture([pull(11, issues=[1242])], comments={})

        completed = run(self.base_args(fixture))

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("reservation=alice:prepared-reserved", completed.stdout)

    def test_unreadable_github_is_reported_and_never_empty(self):
        completed = run(["--repo", "Samuel-Ku/slukhayka", "--gh", "/bin/false"])

        self.assertEqual(3, completed.returncode)
        self.assertIn("github-unavailable", completed.stderr)
        self.assertNotIn("prs=0", completed.stdout)


if __name__ == "__main__":
    unittest.main()
