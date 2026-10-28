"""Тести scripts/goldens.sh: прапорці, повний набір, зміни, pin.

Gradle не запускається: у тимчасовому git-репозиторії лежить підставний
`scripts/test-all.sh`, який лише записує args-file. Тому тести перевіряють
рівно те, що може тихо зламатися: record/verify-прапорці, selectedClasses,
refusals, список змінених еталонів і формат pin-пари.
"""

import hashlib
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GOLDENS = REPO_ROOT / "scripts" / "goldens.sh"

FAKE_TEST_ALL = (
    "#!" + "/usr/bin/env bash\n"
    "set -eu\n"
    "cat \"$SLUKHAYKA_GRADLE_ARGS_FILE\" >> \"$GOLDENS_TEST_LOG\"\n"
    "printf -- '---\\n' >> \"$GOLDENS_TEST_LOG\"\n"
    "if [ -n \"${GOLDENS_TEST_NEW_PNG:-}\" ]; then\n"
    "  touch \"$GOLDENS_REPO_ROOT/app/src/test/snapshots/$GOLDENS_TEST_NEW_PNG\"\n"
    "fi\n"
    "exit \"${GOLDENS_TEST_EXIT:-0}\"\n"
)


class GoldensScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.mkdtemp(prefix="goldens-")
        self.root = Path(self.temp)
        (self.root / "scripts").mkdir()
        self.log = self.root / "args.log"
        fake = self.root / "scripts" / "test-all.sh"
        fake.write_text(FAKE_TEST_ALL, encoding="utf-8")
        fake.chmod(fake.stat().st_mode | stat.S_IXUSR)
        self.snapshots = self.root / "app" / "src" / "test" / "snapshots"
        self.snapshots.mkdir(parents=True)
        self.png = self.snapshots / "known.png"
        self.png.write_bytes(b"golden-bytes")
        self.git("init", "-q")
        self.git("config", "user.email", "tests@example.invalid")
        self.git("config", "user.name", "Goldens tests")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "fixtures")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.temp, ignore_errors=True)

    def git(self, *arguments):
        environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith("GIT_")
        }
        environment.update({
            "PATH": "/usr/bin:/bin",
            "HOME": self.temp,
            "LC_ALL": "C",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
        })
        return subprocess.run(
            ["git", "-C", str(self.root), *arguments],
            capture_output=True, text=True, env=environment, check=False,
        )

    def run_goldens(self, *arguments, **environment):
        env = {
            "PATH": "/usr/bin:/bin",
            "HOME": self.temp,
            "LC_ALL": "C",
            "GOLDENS_REPO_ROOT": str(self.root),
            "GOLDENS_TEST_LOG": str(self.log),
        }
        env.update(environment)
        return subprocess.run(
            ["bash", str(GOLDENS), *arguments],
            capture_output=True, text=True, env=env, check=False,
        )

    def invocations(self):
        if not self.log.exists():
            return []
        return [line for line in self.log.read_text(encoding="utf-8").splitlines()
                if line and line != "---"]

    def test_record_passes_the_record_flag_and_selected_classes(self):
        result = self.run_goldens("record", "com.example.SnapshotTest")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("еталони не змінилися", result.stdout)
        self.assertEqual(
            [
                ":app:testComposeRoborazzi",
                "-Ptest.selectedClasses=com.example.SnapshotTest",
                "-Proborazzi.test.record=true",
            ],
            self.invocations(),
        )

    def test_verify_passes_verify_and_not_record(self):
        result = self.run_goldens("verify", "com.example.SnapshotTest")
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = self.invocations()
        self.assertIn("-Proborazzi.test.record=false", arguments)
        self.assertNotIn("-Proborazzi.test.record=true", arguments)

    def test_record_all_is_explicit_and_drops_the_class_filter(self):
        result = self.run_goldens("record", "--all")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("повний набір", result.stdout)
        arguments = self.invocations()
        self.assertIn(":app:testComposeRoborazzi", arguments)
        self.assertFalse(any(arg.startswith("-Ptest.selectedClasses=") for arg in arguments))

    def test_refuses_missing_classes_without_all(self):
        result = self.run_goldens("record")
        self.assertEqual(2, result.returncode)
        self.assertIn("немає класів", result.stderr)
        self.assertEqual([], self.invocations())

    def test_all_and_classes_are_mutually_exclusive(self):
        result = self.run_goldens("record", "--all", "com.example.SnapshotTest")
        self.assertEqual(2, result.returncode)
        self.assertIn("взаємовиключні", result.stderr)

    def test_an_unknown_subcommand_refuses(self):
        result = self.run_goldens("draw")
        self.assertEqual(2, result.returncode)
        self.assertIn("невідома підкоманда", result.stderr)

    def test_a_gradle_failure_propagates(self):
        result = self.run_goldens("record", "com.example.SnapshotTest", GOLDENS_TEST_EXIT="5")
        self.assertEqual(5, result.returncode, result.stderr)

    def test_changed_goldens_are_listed_including_untracked(self):
        result = self.run_goldens(
            "record", "com.example.SnapshotTest", GOLDENS_TEST_NEW_PNG="new.png",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("new.png", result.stdout)
        self.assertIn("файлів змінено: 1", result.stdout)

    def test_pin_prints_the_digest_and_the_guard_pair(self):
        digest = hashlib.sha256(b"golden-bytes").hexdigest()
        result = self.run_goldens("pin", "known.png")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"known.png sha256={digest}", result.stdout)
        self.assertIn(f'"{digest[:32]}" + "{digest[32:]}"', result.stdout)

    def test_pin_refuses_a_file_outside_the_snapshots_directory(self):
        outside = self.root / "outside.png"
        outside.write_bytes(b"x")
        result = self.run_goldens("pin", str(outside))
        self.assertEqual(2, result.returncode)
        self.assertIn("не лежить у app/src/test/snapshots", result.stderr)

    def test_pin_refuses_a_missing_file(self):
        result = self.run_goldens("pin", "nope.png")
        self.assertEqual(2, result.returncode)
        self.assertIn("файл не знайдено", result.stderr)


if __name__ == "__main__":
    unittest.main()
