"""Тести scripts/review_gate.py на підставному `gh`.

Стиль той самий, що в test_merge_pr.py: скрипт запускається як процес, а
мережевий `gh` підмінюється змінною REVIEW_GATE_GH. Жоден тест не ходить у
мережу й не читає справжній репозиторій.

Перевіряється рівно те, що робить гейт механічним: PASS лише за свіжою міткою
`review:pass`; відмова (код 1) за відсутньої/старої мітки й неготового PR;
невідомість (код 2) — ніколи не PASS.
"""

import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REVIEW_GATE = REPO_ROOT / "scripts" / "review_gate.py"

FAKE_GH = r"""#!/usr/bin/env python3
import json
import os
import sys

arguments = sys.argv[1:]
if os.environ.get("FAKE_FAIL") and os.environ["FAKE_FAIL"] in " ".join(arguments):
    print("gh: simulated failure", file=sys.stderr)
    sys.exit(1)
if arguments[:2] == ["pr", "view"]:
    print(os.environ.get("FAKE_PR_JSON", "{}"))
    sys.exit(0)
if arguments[:2] == ["repo", "view"]:
    print(os.environ.get("FAKE_REPO_JSON", '{"nameWithOwner": "owner/repo"}'))
    sys.exit(0)
if arguments[:1] == ["api"] and "/commits/" in arguments[1]:
    raw = os.environ.get(
        "FAKE_HEAD_DATE", '{"commit": {"committer": {"date": "2026-10-27T10:00:00Z"}}}'
    )
    if "--jq" in arguments:
        value = json.loads(raw)["commit"]["committer"]["date"]
        print(value)
    else:
        print(raw)
    sys.exit(0)
if arguments[:1] == ["api"] and arguments[1].endswith("/timeline"):
    # The real call is `gh api --paginate --slurp`; without --slurp the pages
    # are concatenated arrays that json.loads cannot read. Fail loudly so a
    # future edit that drops the flag reddens the suite instead of silently
    # passing on one-page fixtures.
    if "--slurp" not in arguments:
        print("fake gh: timeline must be read with --paginate --slurp", file=sys.stderr)
        sys.exit(4)
    if os.environ.get("FAKE_TIMELINE_PAGES"):
        print(os.environ["FAKE_TIMELINE_PAGES"])
    else:
        events = json.loads(os.environ.get("FAKE_TIMELINE_JSON", "[]"))
        print(json.dumps([events]))
    sys.exit(0)
print(f"fake gh: unexpected arguments {arguments}", file=sys.stderr)
sys.exit(2)
"""

HEAD_SHA = "0123456789abcdef0123456789abcdef01234567"


def pr_json(state="OPEN", draft=False, labels=(), head=HEAD_SHA):
    return json.dumps({
        "state": state,
        "isDraft": draft,
        "headRefOid": head,
        "headRefName": "t1492-retro-guardrails",
        "labels": [{"name": name} for name in labels],
    })


def timeline(*events):
    return json.dumps([
        {"event": event, "created_at": created_at, **({"label": {"name": label}} if label else {})}
        for event, label, created_at in events
    ])


class ReviewGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.mkdtemp(prefix="review-gate-")
        self.gh = Path(self.temp) / "gh"
        self.gh.write_text(FAKE_GH, encoding="utf-8")
        self.gh.chmod(self.gh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.temp, ignore_errors=True)

    def run_gate(self, *arguments, **environment):
        env = dict(os.environ)
        env.update({
            "REVIEW_GATE_GH": str(self.gh),
            "REVIEW_GATE_REPO": "owner/repo",
            "FAKE_PR_JSON": pr_json(),
            "FAKE_TIMELINE_JSON": "[]",
        })
        env.update({key: value for key, value in environment.items() if value is not None})
        completed = subprocess.run(
            [sys.executable, str(REVIEW_GATE), *arguments],
            capture_output=True, text=True, env=env, check=False,
        )
        return completed.returncode, completed.stdout, completed.stderr

    def test_a_fresh_label_passes(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON=timeline(
                ("labeled", "review:pass", "2026-10-27T11:00:00Z"),
            ),
        )
        self.assertEqual(0, code, out)
        self.assertIn("PASS", out)

    def test_the_latest_label_event_wins(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON=timeline(
                ("labeled", "review:pass", "2026-10-27T09:00:00Z"),
                ("labeled", "review:pass", "2026-10-27T12:00:00Z"),
            ),
        )
        self.assertEqual(0, code, out)

    def test_a_missing_label_refuses(self):
        code, out, _ = self.run_gate("1492", FAKE_PR_JSON=pr_json(labels=[]))
        self.assertEqual(1, code, out)
        self.assertIn("FAIL", out)
        self.assertIn("review:pass", out)

    def test_a_label_that_predates_a_force_push_refuses(self):
        # The fake emits the same shape as the API; keep the after_commit key
        # on the event so the fixture stays honest.
        timeline_json = json.dumps([
            {"event": "labeled", "created_at": "2026-10-27T11:00:00Z",
             "label": {"name": "review:pass"}},
            {"event": "head_ref_force_pushed", "created_at": "2026-10-27T11:20:00Z",
             "after_commit": "fedcba9876543210fedcba9876543210fedcba98"},
        ])
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON=timeline_json,
        )
        self.assertEqual(1, code, out)
        self.assertIn("перезаписали", out)

    def test_a_force_push_before_the_label_passes(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON=json.dumps([
                {"event": "head_ref_force_pushed", "created_at": "2026-10-27T09:00:00Z",
                 "after_commit": "fedcba9876543210fedcba9876543210fedcba98"},
                {"event": "labeled", "created_at": "2026-10-27T11:00:00Z",
                 "label": {"name": "review:pass"}},
            ]),
        )
        self.assertEqual(0, code, out)

    def test_a_label_on_a_later_timeline_page_is_found(self):
        """A long timeline is paginated; the verdict may live on page two."""
        pages = json.dumps([
            [{"event": "commented", "created_at": "2026-10-27T09:00:00Z"}],
            [{"event": "labeled", "created_at": "2026-10-27T11:00:00Z",
              "label": {"name": "review:pass"}}],
        ])
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_PAGES=pages,
        )
        self.assertEqual(0, code, out)

    def test_a_label_older_than_head_refuses(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON=timeline(
                ("labeled", "review:pass", "2026-10-27T09:00:00Z"),
            ),
            FAKE_HEAD_DATE='{"commit": {"committer": {"date": "2026-10-27T10:00:00Z"}}}',
        )
        self.assertEqual(1, code, out)
        self.assertIn("старіший", out)

    def test_a_draft_cannot_pass(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(draft=True, labels=["review:pass"]),
            FAKE_TIMELINE_JSON=timeline(("labeled", "review:pass", "2026-10-27T11:00:00Z")),
        )
        self.assertEqual(1, code, out)
        self.assertIn("чернетка", out)

    def test_a_closed_pr_cannot_pass(self):
        code, out, _ = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(state="MERGED", labels=["review:pass"]),
        )
        self.assertEqual(1, code, out)
        self.assertIn("не відкритий", out)

    def test_a_failing_gh_is_unknown_not_a_pass(self):
        code, out, err = self.run_gate("1492", FAKE_FAIL="pr view")
        self.assertEqual(2, code, out + err)
        self.assertNotIn("PASS", out + err)

    def test_a_label_without_an_event_is_unknown(self):
        code, out, err = self.run_gate(
            "1492",
            FAKE_PR_JSON=pr_json(labels=["review:pass"]),
            FAKE_TIMELINE_JSON="[]",
        )
        self.assertEqual(2, code, out + err)

    def test_a_non_numeric_pr_is_a_usage_error(self):
        code, out, err = self.run_gate("not-a-number")
        self.assertEqual(2, code, out + err)

    def test_help_exits_zero(self):
        code, out, _ = self.run_gate("--help")
        self.assertEqual(0, code, out)
        self.assertIn("usage", out)
        self.assertIn("review:pass", out)


if __name__ == "__main__":
    unittest.main()
