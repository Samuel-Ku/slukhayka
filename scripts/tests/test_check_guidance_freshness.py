"""Тести scripts/check_guidance_freshness.py.

Кожен тест має власний тимчасовий git-репозиторій: перевірка порівнює робочу
копію з ревізією, тож синтетична історія тут і є фікстурою. Жоден тест не
ходить у мережу й не чіпає справжній репозиторій.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# The pre-commit hook runs this module through the real repository, and git
# exports its own GIT_* variables (GIT_INDEX_FILE, GIT_DIR, …) into the hook.
# An inherited GIT_INDEX_FILE makes `git commit` in the synthetic fixture fail
# with an empty stderr; the fixture must own its repository completely.
_GIT_ENV_KEYS = (
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_PREFIX",
)


def clean_git_environment() -> dict:
    return {key: value for key, value in os.environ.items() if key not in _GIT_ENV_KEYS}

REPO_ROOT = Path(__file__).resolve().parents[2]
FRESHNESS = REPO_ROOT / "scripts" / "check_guidance_freshness.py"


class GuidanceFreshnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="guidance-freshness-"))
        self.git = ["git", "-C", str(self.temp)]
        self.run_git("init", "-q")
        self.run_git("config", "user.email", "tests@example.invalid")
        self.run_git("config", "user.name", "Guidance tests")
        (self.temp / "AGENTS.md").write_text("# Guidance\n\nBase version.\n", encoding="utf-8")
        (self.temp / "CONTRIBUTING.md").write_text("# Contributing\n", encoding="utf-8")
        (self.temp / "docs" / "agents").mkdir(parents=True)
        (self.temp / "docs" / "agents" / "swarm.md").write_text("# Swarm\n", encoding="utf-8")
        (self.temp / "docs" / "CODEMAPS").mkdir(parents=True)
        (self.temp / "docs" / "CODEMAPS" / "README.md").write_text("# Codemaps\n", encoding="utf-8")
        self.run_git("add", "-A")
        self.run_git("commit", "-q", "-m", "guidance baseline")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.temp, ignore_errors=True)

    def run_git(self, *arguments):
        completed = subprocess.run(
            self.git + list(arguments), capture_output=True, text=True,
            env=clean_git_environment(), check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        return completed.stdout

    def run_check(self, *arguments, env=None):
        environment = clean_git_environment()
        environment.update(env or {})
        completed = subprocess.run(
            [sys.executable, str(FRESHNESS), "--root", str(self.temp), *arguments],
            capture_output=True, text=True, env=environment, check=False,
        )
        return completed.returncode, completed.stdout, completed.stderr

    def test_clean_copy_matches_the_base(self):
        code, out, err = self.run_check("--base", "HEAD")
        self.assertEqual(0, code, out + err)
        self.assertIn("PASS", out)

    def test_a_modified_guidance_file_is_named(self):
        (self.temp / "AGENTS.md").write_text("# Guidance\n\nStale edit.\n", encoding="utf-8")
        code, out, err = self.run_check("--base", "HEAD")
        self.assertEqual(1, code, out + err)
        self.assertIn("AGENTS.md", out)
        self.assertIn("DRIFT", out)

    def test_an_untracked_guidance_file_is_named(self):
        (self.temp / "docs" / "agents" / "local-environment.md").write_text(
            "# Local environment\n", encoding="utf-8"
        )
        code, out, _ = self.run_check("--base", "HEAD")
        self.assertEqual(1, code, out)
        self.assertIn("local-environment.md", out)

    def test_a_missing_guidance_file_is_named(self):
        (self.temp / "docs" / "agents" / "swarm.md").unlink()
        code, out, _ = self.run_check("--base", "HEAD")
        self.assertEqual(1, code, out)
        self.assertIn("missing", out)

    def test_one_committed_change_moves_the_base_forward(self):
        (self.temp / "AGENTS.md").write_text("# Guidance\n\nCommitted change.\n", encoding="utf-8")
        self.run_git("add", "-A")
        self.run_git("commit", "-q", "-m", "guidance change")
        self.assertEqual(0, self.run_check("--base", "HEAD")[0])
        code, out, _ = self.run_check("--base", "HEAD~1")
        self.assertEqual(1, code, out)

    def test_an_unreadable_base_is_unknown_not_fresh(self):
        code, out, err = self.run_check("--base", "no-such-ref")
        self.assertEqual(2, code, out + err)
        self.assertNotIn("PASS", out + err)
        self.assertIn("fetch", err)

    def test_an_inherited_git_dir_cannot_make_a_foreign_copy_look_like_ours(self):
        """A hook exports GIT_DIR; it must not turn /tmp into the real repo."""
        foreign = tempfile.mkdtemp(prefix="not-a-repo-with-git-dir-")
        environment = clean_git_environment()
        environment.update({
            "GIT_DIR": str(REPO_ROOT / ".git"),
            "GIT_WORK_TREE": str(REPO_ROOT),
        })
        completed = subprocess.run(
            [sys.executable, str(FRESHNESS), "--root", foreign],
            capture_output=True, text=True, check=False, env=environment,
        )
        self.assertEqual(2, completed.returncode, completed.stdout + completed.stderr)
        self.assertNotIn("PASS", completed.stdout + completed.stderr)

    def test_a_non_repository_is_unknown(self):
        completed = subprocess.run(
            [sys.executable, str(FRESHNESS), "--root", tempfile.mkdtemp(prefix="not-a-repo-")],
            capture_output=True, text=True, check=False,
            env=clean_git_environment(),
        )
        self.assertEqual(2, completed.returncode, completed.stdout + completed.stderr)


if __name__ == "__main__":
    unittest.main()
