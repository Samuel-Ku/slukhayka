import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLI = REPO_ROOT / "scripts" / "test_count_guard.py"
TEST_DIR = Path("app/src/test/java/com/example")


def kotlin_test(names: list[str], class_name: str = "AlphaTest") -> str:
    functions = "\n".join(
        f"    @Test\n    fun `{name}`() {{\n    }}\n" for name in names
    )
    return (
        "package com.example\n\n"
        "import org.junit.Test\n\n"
        f"class {class_name} {{\n"
        f"{functions}"
        "}\n"
    )


class TestCountGuardTest(unittest.TestCase):
    """The guard is a git-diff witness: a changed test file that loses tests must
    name each loss in a commit body (#1493)."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.git("init", "-q", "-b", "main")
        self.write("README.md", "fixture\n")
        self.test_file = self.root / TEST_DIR / "AlphaTest.kt"
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
                *arguments,
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def rev(self) -> str:
        return self.git("rev-parse", "HEAD").stdout.strip()

    def write(self, path: str, content: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def write_test(self, names: list[str]) -> None:
        self.write(str(TEST_DIR / "AlphaTest.kt"), kotlin_test(names))

    def commit(self, message: str) -> None:
        added = self.git("add", "-A")
        self.assertEqual(0, added.returncode, added.stderr)
        committed = self.git("commit", "-q", "-m", message)
        self.assertEqual(0, committed.returncode, committed.stderr)

    def run_guard(self, base: str | None = None):
        return subprocess.run(
            [
                "python3",
                str(CLI),
                "--repo",
                str(self.root),
                "--base",
                base if base is not None else self.base,
            ],
            check=False,
            capture_output=True,
            text=True,
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

    def test_deleting_the_whole_test_file_requires_a_declaration(self):
        removed = self.git("rm", "-q", "app/src/test/java/com/example/AlphaTest.kt")
        self.assertEqual(0, removed.returncode, removed.stderr)
        self.commit("drop the whole class")

        result = self.run_guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("first", result.stdout)

    def test_a_whole_file_deletion_with_a_declaration_passes(self):
        removed = self.git("rm", "-q", "app/src/test/java/com/example/AlphaTest.kt")
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

    def test_a_missing_base_is_an_unknown_not_a_pass(self):
        self.write("README.md", "fixture changed\n")
        self.commit("touch the readme")

        result = self.run_guard(base="refs/heads/does-not-exist")

        self.assertEqual(2, result.returncode)
        self.assertIn("cannot read", result.stderr)


if __name__ == "__main__":
    unittest.main()
