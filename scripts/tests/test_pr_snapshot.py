import json
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "pr-snapshot.py"


def run(args, cwd=None):
    return subprocess.run(
        ["python3", str(SCRIPT), "--repo", "Samuel-Ku/slukhayka", *args],
        capture_output=True,
        text=True,
        cwd=cwd or REPO_ROOT,
    )


def pull(number, *, draft=False, rollup="default", issues=(), review=None):
    if rollup == "default":
        rollup = [{"state": "SUCCESS"}]
    payload = {
        "number": number,
        "title": f"PR {number}",
        "isDraft": draft,
        "baseRefName": "main",
        "headRefOid": f"{number:040d}",
        "url": f"https://github.com/Samuel-Ku/slukhayka/pull/{number}",
        "statusCheckRollup": rollup,
        "closingIssuesReferences": [{"number": issue} for issue in issues],
        "labels": [],
        "reviewDecision": review,
    }
    return payload


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

    def fixture(self, pulls, comments=None):
        path = self.root / "pulls.json"
        payload = pulls if comments is None else {"pulls": pulls, "comments": comments}
        path.write_text(json.dumps(payload), encoding="utf-8")
        return str(path)

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

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims)])

        self.assertEqual(0, completed.returncode, completed.stderr)
        output = completed.stdout
        self.assertIn("prs=3", output)
        self.assertIn("#1 draft base=main head=0000000 checks=0ok/0fail/1pend", output)
        self.assertIn("issues=#1242 last-comment=alice@2026-10-24 reservation=alice:prepared-reserved", output)
        self.assertIn("#2 ready base=main", output)
        self.assertIn("checks=1ok/1fail/1pend/1skip", output)
        self.assertIn("issues=#1240 last-comment=none", output)
        self.assertIn("#3 ready base=main", output)
        self.assertIn("checks=unknown issues=none reservation=absent", output)
        self.assertNotIn("#3 ready base=main head=0000000 checks=unknown issues=none reservation=absent last-comment", output)

    def test_comments_can_be_skipped_on_request(self):
        fixture = self.fixture(
            [pull(1, issues=[1242])],
            comments={"1242": {"user": {"login": "alice"}, "created_at": "2026-10-24T09:00:00Z"}},
        )

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims), "--no-comments"])

        self.assertNotIn("last-comment", completed.stdout)

    def test_review_decision_is_flagged(self):
        fixture = self.fixture([pull(7, review="CHANGES_REQUESTED")])

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims)])

        self.assertIn("flags=changes-requested", completed.stdout)

    def test_full_payload_is_written_separately_from_stdout(self):
        fixture = self.fixture([pull(1)])
        full = self.root / "full.json"

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims),
                         "--full-json", str(full)])

        self.assertNotIn('"pullRequests"', completed.stdout)
        saved = json.loads(full.read_text(encoding="utf-8"))
        self.assertEqual(1, len(saved["pullRequests"]))
        self.assertIn("capturedAt", saved)

    def test_pagination_does_not_truncate_the_list(self):
        fixture = self.fixture([pull(number) for number in range(1, 151)])

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims)])

        self.assertIn("prs=150", completed.stdout)
        self.assertEqual(150, completed.stdout.count("\n#"))

    def test_head_drift_is_explicit_and_nonzero(self):
        fixture = self.fixture([pull(5)])

        same = run(["--fixture", fixture, "--claims-dir", str(self.claims),
                    "--expect-head", f"5={5:040d}"])
        moved = run(["--fixture", fixture, "--claims-dir", str(self.claims),
                     "--expect-head", "5=deadbeef"])

        self.assertEqual(0, same.returncode, same.stdout)
        self.assertEqual(4, moved.returncode, moved.stdout)
        self.assertIn("head-drift: #5", moved.stdout)

    def test_missing_pr_is_a_drift_not_a_clean_state(self):
        fixture = self.fixture([pull(5)])

        completed = run(["--fixture", fixture, "--claims-dir", str(self.claims),
                         "--expect-head", "999=deadbeef"])

        self.assertEqual(4, completed.returncode)
        self.assertIn("head-drift: #999 is not open any more", completed.stdout)

    def test_unreadable_github_is_reported_and_never_empty(self):
        completed = run(["--gh", "/bin/false"])

        self.assertEqual(3, completed.returncode)
        self.assertIn("github-unavailable", completed.stderr)
        self.assertNotIn("prs=0", completed.stdout)


if __name__ == "__main__":
    unittest.main()
