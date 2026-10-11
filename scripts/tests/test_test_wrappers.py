import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TEST_ALL = REPO_ROOT / "scripts" / "test-all.sh"
TEST_CHANGED = REPO_ROOT / "scripts" / "test-changed.sh"


class FullSuiteWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("test-all.sh", "test-changed.sh", "test_partitions.py", "test-controlled-app.py"):
            source = REPO_ROOT / "scripts" / name
            target = scripts / name
            target.write_bytes(source.read_bytes())
            target.chmod(source.stat().st_mode)
        self.test_all = scripts / "test-all.sh"
        self.test_changed = scripts / "test-changed.sh"
        fixture = self.root / "app/src/test/java/com/slukhayka/audiobooks/player/SmartRewindTest.kt"
        fixture.parent.mkdir(parents=True)
        fixture.write_text(
            "package com.slukhayka.audiobooks.player\nclass SmartRewindTest\n",
            encoding="utf-8",
        )
        self.java_home = self.root / "jdk-21"
        self.java_home.joinpath("bin").mkdir(parents=True)
        self.java = self.java_home / "bin/java"
        self.java.write_text(
            "#!/usr/bin/env bash\necho 'openjdk version \"21.0.8\"' >&2\n",
            encoding="utf-8",
        )
        self.java.chmod(self.java.stat().st_mode | stat.S_IXUSR)
        self.gradle_log = self.root / "gradle.log"
        self.fake_gradle = self.root / "gradlew"

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_gradle(self, exit_status: int = 0) -> None:
        self.fake_gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$JAVA_HOME\" \"$TMPDIR\" \"$$ $*\" >> \"$WRAPPER_TEST_LOG\"\n"
            "test -d \"$TMPDIR\"\n"
            f"exit {exit_status}\n",
            encoding="utf-8",
        )
        self.fake_gradle.chmod(self.fake_gradle.stat().st_mode | stat.S_IXUSR)

    def run_wrapper(self, *, java_home: Path | None = None):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": "/usr/bin:/bin",
                "SLUKHAYKA_GRADLEW": str(self.fake_gradle),
                "WRAPPER_TEST_LOG": str(self.gradle_log),
            }
        )
        if java_home is not None:
            environment["SLUKHAYKA_JAVA_HOME"] = str(java_home)
        else:
            environment.pop("SLUKHAYKA_JAVA_HOME", None)
        return subprocess.run(
            ["bash", str(self.test_all)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def test_uses_jdk_21_and_a_fresh_temp_then_cleans_it(self):
        self.write_gradle()

        result = self.run_wrapper(java_home=self.java_home)

        self.assertEqual(0, result.returncode, result.stderr)
        log_lines = self.gradle_log.read_text(encoding="utf-8").splitlines()
        java_home, temp_path = log_lines[:2]
        invocations = "\n".join(log_lines)
        self.assertEqual(str(self.java_home), java_home)
        self.assertIn("slukhayka-tests.", temp_path)
        self.assertFalse(Path(temp_path).exists())
        self.assertIn(":app:testPureJvm", invocations)
        self.assertIn(":app:testRoomNativeSdk35", invocations)
        self.assertIn(":app:testRoomNativeSdk36", invocations)
        self.assertIn(":app:testRoomNativeDefault", invocations)
        self.assertIn(":app:testRoomRobolectricOnly", invocations)
        self.assertIn(":app:testComposeRoborazzi", invocations)

    def test_propagates_gradle_failure_and_still_cleans_temp(self):
        self.write_gradle(exit_status=17)

        result = self.run_wrapper(java_home=self.java_home)

        self.assertEqual(17, result.returncode)
        temp_path = self.gradle_log.read_text(encoding="utf-8").splitlines()[1]
        self.assertFalse(Path(temp_path).exists())

    def test_rejects_a_non_21_override_with_an_actionable_error(self):
        self.java.write_text(
            "#!/usr/bin/env bash\necho 'openjdk version \"17.0.12\"' >&2\n",
            encoding="utf-8",
        )
        self.java.chmod(self.java.stat().st_mode | stat.S_IXUSR)
        self.write_gradle()

        result = self.run_wrapper(java_home=self.java_home)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("JDK 21", result.stderr)
        self.assertFalse(self.gradle_log.exists())

    def test_cleans_temp_when_interrupted(self):
        self.fake_gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$JAVA_HOME\" \"$TMPDIR\" \"$$ $*\" >> \"$WRAPPER_TEST_LOG\"\n"
            "kill -TERM \"$PPID\"\n"
            "sleep 0.1\n"
            "exit 143\n",
            encoding="utf-8",
        )
        self.fake_gradle.chmod(self.fake_gradle.stat().st_mode | stat.S_IXUSR)

        result = self.run_wrapper(java_home=self.java_home)

        self.assertNotEqual(0, result.returncode)
        temp_path = self.gradle_log.read_text(encoding="utf-8").splitlines()[1]
        self.assertFalse(Path(temp_path).exists())

    def test_changed_wrapper_runs_only_the_selected_test_class(self):
        self.write_gradle()
        environment = os.environ.copy()
        environment.update(
            {
                "SLUKHAYKA_JAVA_HOME": str(self.java_home),
                "SLUKHAYKA_GRADLEW": str(self.fake_gradle),
                "WRAPPER_TEST_LOG": str(self.gradle_log),
            }
        )

        result = subprocess.run(
            [
                "bash",
                str(self.test_changed),
                "--changed-file",
                "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        invocations = self.gradle_log.read_text(encoding="utf-8")
        self.assertIn(":app:testPureJvm", invocations)
        self.assertIn("-Ptest.selectedClasses=", invocations)
        self.assertIn("com.slukhayka.audiobooks.player.SmartRewindTest", invocations)
        self.assertNotIn(":app:testComposeRoborazzi", invocations)

    def test_unavailable_base_fails_closed_for_controlled_app_with_overrides(self):
        self.write_gradle()
        environment = os.environ.copy()
        environment.update(
            {
                "SLUKHAYKA_JAVA_HOME": str(self.java_home),
                "SLUKHAYKA_GRADLEW": str(self.fake_gradle),
                "WRAPPER_TEST_LOG": str(self.gradle_log),
            }
        )

        result = subprocess.run(
            ["bash", str(self.test_changed), "--base", "refs/heads/does-not-exist"],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn(
            "Base ref 'refs/heads/does-not-exist' is unavailable; running all six legs and the explicit controlled App target.",
            result.stderr,
        )
        if os.uname().sysname == "Darwin":
            self.assertIn("Custom Gradle/JVM overrides are unsupported for this exact controlled target", result.stderr)
            invocations = self.gradle_log.read_text(encoding="utf-8")
            self.assertIn(":app:testPureJvm", invocations)
            self.assertIn(":app:testRoomNativeSdk35", invocations)
            self.assertIn(":app:testRoomNativeSdk36", invocations)
            self.assertIn(":app:testRoomNativeDefault", invocations)
            self.assertIn(":app:testRoomRobolectricOnly", invocations)
            self.assertIn(":app:testComposeRoborazzi", invocations)
        else:
            self.assertIn(
                "Controlled App target supports reviewed macOS tools only. Linux is unsupported until a separate pin review.",
                result.stderr,
            )
            self.assertFalse(self.gradle_log.exists())


class ChangedTestWrapperWebCheckTest(unittest.TestCase):
    """`select` maps `web/**` to the web check instead of the full suite; this
    pins what `test-changed.sh` then runs for it."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "invocations.log"
        self.java_home = self.root / "jdk-21"
        self.java_home.joinpath("bin").mkdir(parents=True)
        self.java = self.java_home / "bin/java"
        self.java.write_text(
            "#!/usr/bin/env bash\necho 'openjdk version \"21.0.8\"' >&2\n",
            encoding="utf-8",
        )
        self.java.chmod(self.java.stat().st_mode | stat.S_IXUSR)
        self.gradle = self.root / "gradlew"

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_web_command(self, name: str, exit_status: int = 0) -> None:
        target = self.bin / name
        target.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s %s cwd=%s\\n' \"$(basename \"$0\")\" \"$*\" \"$PWD\" "
            ">> \"$WRAPPER_TEST_LOG\"\n"
            f"exit {exit_status}\n",
            encoding="utf-8",
        )
        target.chmod(target.stat().st_mode | stat.S_IXUSR)

    def write_gradle(self) -> None:
        self.gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf 'gradle %s\\n' \"$*\" >> \"$WRAPPER_TEST_LOG\"\n",
            encoding="utf-8",
        )
        self.gradle.chmod(self.gradle.stat().st_mode | stat.S_IXUSR)

    def run_changed(self, *arguments: str):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.bin}:/usr/bin:/bin",
                "SLUKHAYKA_JAVA_HOME": str(self.java_home),
                "SLUKHAYKA_GRADLEW": str(self.gradle),
                "WRAPPER_TEST_LOG": str(self.log),
            }
        )
        return subprocess.run(
            ["bash", str(TEST_CHANGED), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def test_web_path_runs_the_web_check_and_no_android_partition(self):
        self.write_web_command("npm")
        self.write_web_command("npx")
        self.write_gradle()

        result = self.run_changed("--changed-file", "web/src/ui/adaptiveLayout.ts")

        self.assertEqual(0, result.returncode, result.stderr)
        web_root = REPO_ROOT / "web"
        self.assertEqual(
            [
                f"npm ci cwd={web_root}",
                f"npx tsc --noEmit cwd={web_root}",
                f"npx vitest run cwd={web_root}",
            ],
            self.log.read_text(encoding="utf-8").splitlines(),
        )

    def test_failing_web_check_stops_before_the_next_step(self):
        self.write_web_command("npm", exit_status=9)
        self.write_web_command("npx")
        self.write_gradle()

        result = self.run_changed("--changed-file", "web/src/ui/adaptiveLayout.ts")

        self.assertEqual(9, result.returncode)
        self.assertEqual(
            [f"npm ci cwd={REPO_ROOT / 'web'}"],
            self.log.read_text(encoding="utf-8").splitlines(),
        )


if __name__ == "__main__":
    unittest.main()
