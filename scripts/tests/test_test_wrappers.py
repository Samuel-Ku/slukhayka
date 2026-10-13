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

    def environment(self, *, java_home: Path | None = None) -> dict:
        """Оточення підставних `gradlew` і `java` — своє, а не успадковане (#1265).

        CI-раннер приносить у процес власні змінні, і серед них може бути
        експортована функція (`BASH_FUNC_printf%%`, `BASH_FUNC_cat%%`), яка
        затіняє команду ВСЕРЕДИНІ підставного `gradlew`: він тоді не пише журнал
        викликів, і тест бачить порожнечу — при незмінних файлах фікстури. Саме
        так фікстура #1260 і почервоніла в CI, хоч локально була зелена.

        `scripts/tests/test_mutate.py` задає оточення так само — свіжим
        словником, а не `os.environ`. Імена тут — лише свої: `WRAPPER_TEST_LOG`
        читає підставний інструмент, `SLUKHAYKA_*` — вхід самого wrapper'а.
        """
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "SLUKHAYKA_GRADLEW": str(self.fake_gradle),
            "WRAPPER_TEST_LOG": str(self.gradle_log),
        }
        if java_home is not None:
            environment["SLUKHAYKA_JAVA_HOME"] = str(java_home)
        return environment

    def run_wrapper(self, *, java_home: Path | None = None):
        return subprocess.run(
            ["bash", str(TEST_ALL)],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(java_home=java_home),
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

        result = subprocess.run(
            [
                "bash",
                str(TEST_CHANGED),
                "--changed-file",
                "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(java_home=self.java_home),
        )

        self.assertEqual(0, result.returncode, result.stderr)
        invocations = self.gradle_log.read_text(encoding="utf-8")
        self.assertIn(":app:testPureJvm", invocations)
        self.assertIn("-Ptest.selectedClasses=", invocations)
        self.assertIn("com.slukhayka.audiobooks.player.SmartRewindTest", invocations)
        self.assertNotIn(":app:testComposeRoborazzi", invocations)

    def test_unavailable_base_falls_back_to_full_suite(self):
        self.write_gradle()

        result = subprocess.run(
            ["bash", str(TEST_CHANGED), "--base", "refs/heads/does-not-exist"],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(java_home=self.java_home),
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("unavailable; running the full suite", result.stderr)
        invocations = self.gradle_log.read_text(encoding="utf-8")
        self.assertIn(":app:testPureJvm", invocations)
        self.assertIn(":app:testComposeRoborazzi", invocations)


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

    def environment(self) -> dict:
        """Оточення підставних `gradlew`, `npm` і `npx` — своє, а не успадковане.

        Те саме, що в `FullSuiteWrapperTest.environment` (#1265): команда
        всередині підставного інструмента не мусить залежати від того, що
        раннер експортував у процес. Імена — лише свої: `WRAPPER_TEST_LOG` для
        підставних інструментів, `SLUKHAYKA_*` — вхід wrapper'а.
        """
        return {
            "PATH": f"{self.bin}:/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            # Дім JDK виводиться з самого підставного `java`, а не з окремого
            # `self.java_home`: значення те саме — `java` лежить у `<дім>/bin`,
            # — і видно, ЩО туди кладеться.
            "SLUKHAYKA_JAVA_HOME": str(self.java.parent.parent),
            "SLUKHAYKA_GRADLEW": str(self.gradle),
            "WRAPPER_TEST_LOG": str(self.log),
        }

    def run_changed(self, *arguments: str):
        return subprocess.run(
            ["bash", str(TEST_CHANGED), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(),
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


class ChangedTestWrapperScratchTest(unittest.TestCase):
    """`test-changed.sh` мусить прибрати свою теку за БУДЬ-ЯКОГО кінця (#1280).

    `exec` замінює процес, а замінений процес не виконує EXIT-пастку: доки
    прибирання стояло лише в пастці, кожен відкат на повний набір лишав по собі
    порожню теку `slukhayka-changed-tests.*` у `$TMPDIR`. Порожня тека сама по
    собі нешкідлива; шкідливий tmpfs: `/tmp` тримає їх у RAM, і вичерпання цієї
    ФС уже тричі зупиняло роботу. Тому тут три кінці одного прогону — код 0,
    падіння і TERM, — і жоден не сміє лишити теку.

    Межа сторожа названа тут, а не в звіті: він бачить лише те, що створено під
    тим `$TMPDIR`, який він сам і дає. Тека, лишена деінде (наприклад, у
    жорстко прописаному `/tmp`), лишилася б для нього невидимою — як і те, чий
    обробник її зніс.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        # Свій `TMPDIR`: тека прогону мусить бути видна тесту, а не ділитися з
        # рештою набору (той, хто лишив теку в спільному `/tmp`, лишає її в CI).
        self.tmp = self.root / "tmpdir"
        self.tmp.mkdir()
        self.java_home = self.root / "jdk-21"
        self.java_home.joinpath("bin").mkdir(parents=True)
        self.java = self.java_home / "bin/java"
        self.java.write_text(
            "#!/usr/bin/env bash\necho 'openjdk version \"21.0.8\"' >&2\n",
            encoding="utf-8",
        )
        self.java.chmod(self.java.stat().st_mode | stat.S_IXUSR)
        self.gradle = self.root / "gradlew"
        self.log = self.root / "invocations.log"

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_gradle(self, exit_status: int = 0, signal_the_group: bool = False) -> None:
        """Підставний `gradlew`: пише виклик у журнал і кінчається тим, що просять.

        `signal_the_group` — той самий TERM, що його дістає справжній прогін,
        зупинений ззовні: `kill -TERM 0` б'є по всьому гурту процесів, тож його
        бачить і сам wrapper, а не лише `gradlew`.
        """
        signal = "kill -TERM 0\n" if signal_the_group else ""
        self.gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$*\" >> \"$WRAPPER_TEST_LOG\"\n"
            f"{signal}"
            f"exit {exit_status}\n",
            encoding="utf-8",
        )
        self.gradle.chmod(self.gradle.stat().st_mode | stat.S_IXUSR)

    def environment(self) -> dict:
        """Своє оточення, а не успадковане (#1265) — і свій `TMPDIR`."""
        return {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "TMPDIR": str(self.tmp),
            "SLUKHAYKA_JAVA_HOME": str(self.java_home),
            "SLUKHAYKA_GRADLEW": str(self.gradle),
            "WRAPPER_TEST_LOG": str(self.log),
        }

    def run_changed(self, *arguments: str, session: bool = False):
        return subprocess.run(
            ["bash", str(TEST_CHANGED), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(),
            # Свій сеанс потрібен лише тому прогону, який сам собі шле TERM:
            # `kill -TERM 0` б'є по гурту процесів, і без нового сеансу він
            # дістав би й сам набір тестів.
            start_new_session=session,
            timeout=60,
        )

    def leftovers(self):
        return sorted(one.name for one in self.tmp.glob("slukhayka-changed-tests.*"))

    def test_no_scratch_directory_survives_a_run(self):
        """Сторож: тека прогону зникає і на коді 0, і на падінні, і на сигналі."""
        self.write_gradle()

        green = self.run_changed("--base", "refs/heads/does-not-exist")
        self.assertEqual(0, green.returncode, green.stderr)
        self.assertTrue(self.log.exists(), "прогін не виконався — сторож нічого не доводить")
        self.assertEqual([], self.leftovers(), "тека лишилася після успішного прогону")

        self.write_gradle(exit_status=17)

        red = self.run_changed("--base", "refs/heads/does-not-exist")
        # `exec` навмисний: код повного набору доходить до викликача незміненим.
        self.assertEqual(17, red.returncode, red.stderr)
        self.assertEqual([], self.leftovers(), "тека лишилася після невдалого прогону")

        # Сигнал: TERM дістається всьому гурту прогону, тож його бачить і сам
        # wrapper, і прогін скінчився сигналом, а не роботою (код 143 — це
        # 128+15). Що саме прибирає теку — пастка на TERM чи на EXIT — ззовні не
        # видно, і сторож на цьому не стоїть: його межа — тека, а не те, чий
        # обробник її зніс.
        self.write_gradle(signal_the_group=True)

        signalled = self.run_changed(
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
            session=True,
        )
        self.assertNotEqual(0, signalled.returncode, signalled.stderr)
        self.assertEqual([], self.leftovers(), "тека лишилася після сигналу")


if __name__ == "__main__":
    unittest.main()
