import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLI = REPO_ROOT / "scripts" / "test_count_guard.py"
TEST_DIR = Path("app/src/test/java/com/example")
TEST_PATH = TEST_DIR / "AlphaTest.kt"


def kotlin_test(names: list[str], class_name: str = "AlphaTest") -> str:
    functions = "\n".join(f"    @Test\n    fun `{name}`() {{\n    }}\n" for name in names)
    return (
        "package com.example\n\n"
        "import org.junit.Test\n\n"
        f"class {class_name} {{\n"
        f"{functions}"
        "}\n"
    )


class TestCountGuardTest(unittest.TestCase):
    """The guard is a git-diff witness: a changed test file that loses tests must
    name each loss in a commit body (#1493). The fixture isolates git from the
    host's config — a machine with `commit.gpgsign=true` must not fail setUp."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.hostile_config = self.root / "hostile-gitconfig"
        self.hostile_config.write_text("[commit]\n\tgpgsign = true\n", encoding="utf-8")
        self.env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root),
            "LC_ALL": "C",
            # A hostile global config is the point: the fixture must not depend
            # on the host's signing hooks or identity (#1495 review, finding 7).
            "GIT_CONFIG_GLOBAL": str(self.hostile_config),
            "GIT_CONFIG_SYSTEM": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1",
        }
        self.git("init", "-q", "-b", "main")
        self.write("README.md", "fixture\n")
        self.write_test(["first", "second", "third"])
        self.commit("base")
        self.base = self.rev()

    def tearDown(self):
        self.temp_dir.cleanup()

    def git(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "git",
                "-C",
                str(self.root),
                "-c",
                "user.name=Guard",
                "-c",
                "user.email=guard@example.test",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null",
                *arguments,
            ],
            check=False,
            capture_output=True,
            text=True,
            env=self.env,
        )

    def rev(self) -> str:
        return self.git("rev-parse", "HEAD").stdout.strip()

    def write(self, path: str, content: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def write_test(self, names: list[str], path: Path = TEST_PATH, class_name: str = "AlphaTest") -> None:
        self.write(str(path), kotlin_test(names, class_name))

    def commit(self, message: str) -> None:
        added = self.git("add", "-A")
        self.assertEqual(0, added.returncode, added.stderr)
        committed = self.git("commit", "-q", "-m", message)
        self.assertEqual(0, committed.returncode, committed.stderr)

    def run_guard(self, base: str | None = None, head: str = "HEAD", env: dict | None = None):
        return subprocess.run(
            [
                sys.executable,
                str(CLI),
                "--repo",
                str(self.root),
                "--base",
                base if base is not None else self.base,
                "--head",
                head,
            ],
            check=False,
            capture_output=True,
            text=True,
            env=env if env is not None else self.env,
        )

    def test_removing_a_test_without_a_declared_removal_fails(self):
        self.write_test(["first", "third"])
        self.commit("drop the second test")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("removed test: app/src/test/java/com/example/AlphaTest.kt: second", result.stdout)
        self.assertIn("without a declared removal", result.stderr)

    def test_a_declared_removal_passes(self):
        self.write_test(["first", "third"])
        self.commit("drop the second test\n\ntest-removal: second")

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_a_declaration_for_another_test_does_not_cover_the_removal(self):
        self.write_test(["first", "third"])
        self.commit("drop the second test\n\ntest-removal: third")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("second", result.stdout)

    def test_a_dotted_declaration_covers_the_bare_name(self):
        self.write_test(["first", "third"])
        self.commit("drop\n\ntest-removal: com.example.AlphaTest.second")

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_a_rewritten_file_lists_every_lost_test(self):
        self.write_test(["first"])
        self.commit("rewrite the file down to one test")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("second", result.stdout)
        self.assertIn("third", result.stdout)

    def test_adding_tests_passes(self):
        self.write_test(["first", "second", "third", "fourth"])
        self.commit("add a test")

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("no undeclared test removal", result.stdout)

    def test_a_renamed_test_is_a_removal(self):
        # Same file, one name out, one name in: the old name is a removal even
        # though the count did not fall.
        self.write_test(["first", "second", "fourth"])
        self.commit("rename third to fourth")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("third", result.stdout)

    def test_renaming_the_file_keeps_its_tests(self):
        # Pins `--find-renames`: without it git reports D+A, every test of the
        # old file counts as removed, and this green case would be a false red.
        moved = self.git("mv", "app/src/test/java/com/example/AlphaTest.kt", "app/src/test/java/com/example/BetaTest.kt")
        self.assertEqual(0, moved.returncode, moved.stderr)
        self.commit("rename the test file")

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("no undeclared test removal", result.stdout)

    def test_deleting_the_whole_test_file_requires_a_declaration(self):
        removed = self.git("rm", "-q", str(TEST_PATH))
        self.assertEqual(0, removed.returncode, removed.stderr)
        self.commit("drop the whole class")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("first", result.stdout)

    def test_a_whole_file_deletion_with_a_declaration_passes(self):
        removed = self.git("rm", "-q", str(TEST_PATH))
        self.assertEqual(0, removed.returncode, removed.stderr)
        self.commit(
            "drop the class\n\ntest-removal: first\ntest-removal: second\n"
            "test-removal: third"
        )

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_a_non_test_change_passes(self):
        self.write("README.md", "fixture changed\n")
        self.commit("touch the readme")

        result = self.run_guard()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_an_empty_diff_is_green(self):
        result = self.run_guard(base=self.base, head=self.base)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("0 changed test file(s)", result.stdout)

    def test_a_non_ascii_test_path_is_still_seen(self):
        # `git diff --name-status` quotes non-ASCII paths unless -z is used; a
        # quoted path silently missed TEST_ROOT and the guard exited 0 (#1495
        # review, finding 2).
        path = TEST_DIR / "ЛокальTest.kt"
        self.write_test(["перший", "другий"], path=path, class_name="ЛокальTest")
        self.commit("add a non-ASCII test file")
        base_with_file = self.rev()
        self.write_test(["перший"], path=path, class_name="ЛокальTest")
        self.commit("drop the second test")

        result = self.run_guard(base=base_with_file)

        self.assertEqual(1, result.returncode)
        self.assertIn("другий", result.stdout)

    def test_a_stale_base_does_not_invent_a_removal(self):
        # The PR forks before main adds a test: a double diff (base tip vs head)
        # would report that main-side addition as removed; the merge-base diff
        # must not (#1495 review, finding 3).
        self.git("checkout", "-q", "-b", "feature")
        self.write_test(["first", "second", "third", "fourth"])
        self.commit("feature adds a test")
        self.git("checkout", "-q", "main")
        self.write_test(["first", "second", "third", "main-only"])
        self.commit("main adds a test")

        result = self.run_guard(base="main", head="feature")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("no undeclared test removal", result.stdout)

    def test_an_unreadable_blob_is_an_unknown_not_a_green(self):
        self.write_test(["first", "third"])
        self.commit("drop the second test")
        blob = self.git("rev-parse", f"{self.base}:{TEST_PATH}").stdout.strip()
        loose = self.root / ".git" / "objects" / blob[:2] / blob[2:]
        self.assertTrue(loose.exists(), f"expected a loose object at {loose}")
        loose.unlink()

        result = self.run_guard()

        self.assertEqual(2, result.returncode)
        self.assertIn("cannot read", result.stderr)

    def test_a_missing_git_is_an_unknown_not_a_verdict(self):
        env = {"PATH": "/nonexistent", "HOME": str(self.root), "LC_ALL": "C"}

        result = self.run_guard(env=env)

        self.assertEqual(2, result.returncode)
        self.assertIn("cannot run git", result.stderr)

    def test_a_missing_base_is_an_unknown_not_a_pass(self):
        self.write("README.md", "fixture changed\n")
        self.commit("touch the readme")

        result = self.run_guard(base="refs/heads/does-not-exist")

        self.assertEqual(2, result.returncode)
        self.assertIn("cannot read", result.stderr)


if __name__ == "__main__":
    unittest.main()
