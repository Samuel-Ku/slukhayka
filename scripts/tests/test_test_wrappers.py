import os
import shutil
import signal
import stat
import subprocess
import tempfile
import time
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
        # Свій `TMPDIR` — і свій, а не успадкований (#1265), і ПОЗА копією дерева:
        # тека прогону тут — це `TMPDIR` для всього Gradle, і в спільному `/tmp`
        # вона пережила б SIGKILL як чуже сміття (#1305).
        self.tmp = self.root / "tmp"
        self.tmp.mkdir()

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
            "TMPDIR": str(self.tmp),
            "SLUKHAYKA_GRADLEW": str(self.fake_gradle),
            "WRAPPER_TEST_LOG": str(self.gradle_log),
        }
        if java_home is not None:
            environment["SLUKHAYKA_JAVA_HOME"] = str(java_home)
        return environment

    def run_wrapper(self, *, java_home: Path | None = None, gradle: Path | None = None):
        environment = self.environment(java_home=java_home)
        if gradle is not None:
            environment["SLUKHAYKA_GRADLEW"] = str(gradle)
        return subprocess.run(
            ["bash", str(TEST_ALL)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def write_fast_gradle(self) -> Path:
        """Друга підставна збірка — ОКРЕМИМ файлом, і це не косметика.

        Переписувати ту саму теку, яку зараз виконує живий прогін, не можна: bash
        читає скрипт із диска по ходу виконання, тож переписаний файл міняє його
        на ходу — живий прогін тоді «сам» закінчується, і його пастка прибирає
        теку, яку сторож якраз тримає.
        """
        fast = self.root / "gradlew-fast"
        fast.write_text(
            "#!/usr/bin/env bash\n"
            "# швидка підставна збірка: нею йдуть третій та останній прогони\n"
            "printf '%s\\n' \"$JAVA_HOME\" \"$TMPDIR\" \"$$ $*\" >> \"$WRAPPER_TEST_LOG\"\n"
            "test -d \"$TMPDIR\"\n"
            "exit 0\n",
            encoding="utf-8",
        )
        fast.chmod(fast.stat().st_mode | stat.S_IXUSR)
        return fast

    def write_hanging_gradle(self) -> None:
        """Підставна збірка, яка не закінчується: прогін мусить бути В ПОЛЬОТІ.

        Тільки такий прогін можна вбити SIGKILL'ом у момент роботи — і саме так
        виглядає повний набір, який знесла ескалація `build-slot-guard.py`: його
        тека прогону — це `TMPDIR` для всього Gradle, тож лишена вона не порожня.
        """
        self.fake_gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$JAVA_HOME\" \"$TMPDIR\" \"$$ $*\" >> \"$WRAPPER_TEST_LOG\"\n"
            "sleep 300\n",
            encoding="utf-8",
        )
        self.fake_gradle.chmod(self.fake_gradle.stat().st_mode | stat.S_IXUSR)

    def leftovers(self) -> list:
        return sorted(one.name for one in self.tmp.glob("slukhayka-tests.*"))

    def start_run(self):
        """Прогін у власному сеансі: `kill -KILL` по гурту не дістане набір тестів."""
        return subprocess.Popen(
            ["bash", str(TEST_ALL)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=self.environment(java_home=self.java_home),
            start_new_session=True,
        )

    def wait_for_new_dir(self, known: set, process) -> str:
        """Чекає НОВУ теку (різницю множин, а не «останню зі списку»).

        «Остання зі списку» повернула б ту саму теку вдруге, якби нове ім'я
        сортувалося нижче, — і сторож про гонку двох прогонів порівнював би теку
        саму з собою.
        """
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            fresh = set(self.leftovers()) - known
            if fresh:
                return sorted(fresh)[-1]
            if process.poll() is not None:
                break
            time.sleep(0.05)
        self.fail("прогін не створив теку прогону — сторож нічого не доводив би")

    def test_a_sigkilled_run_is_reaped_by_the_next_run(self):
        """Сторож: тека повного набору зникає на СТАРТІ наступного прогону.

        Пастка над SIGKILL не владна — це фізика, а не недогляд скрипта, — і до
        #1305 теку повного набору не прибирав НІХТО: префікса не було в списку
        прибиральника, бо `test-all.sh` не писав маркера. Тепер пише, і наступний
        прогін прибирає залишок, назвавши теку й байти.

        Межа: сторож бачить лише те, що створено під тим `$TMPDIR`, який він сам
        дає; тека в жорстко прописаному `/tmp` лишилася б для нього невидимою.
        """
        self.write_hanging_gradle()
        victim = self.start_run()
        self.addCleanup(victim.kill)
        left = self.wait_for_new_dir(set(), victim)
        marker = self.tmp / left / "owner.pid"
        self.assertTrue(marker.is_file(), "теку лишено разом із маркером власника")
        self.assertEqual(str(victim.pid), marker.read_text(encoding="utf-8").strip(),
                         "маркер мусить називати САМ прогін, а не хелпер, що створив теку")

        os.killpg(os.getpgid(victim.pid), signal.SIGKILL)
        _out, err = victim.communicate(timeout=60)
        self.assertEqual([left], self.leftovers(),
                         "SIGKILL мусить лишити теку: пастки він не виконує: %s" % err)

        fast = self.write_fast_gradle()
        again = self.run_wrapper(java_home=self.java_home, gradle=fast)

        self.assertEqual(0, again.returncode, again.stderr)
        self.assertIn("прибрано застарілу теку прогону", again.stderr)
        self.assertIn(left, again.stderr)
        self.assertEqual([], self.leftovers(),
                         "залишок SIGKILL'а мусив зникнути на старті наступного прогону")

    def test_two_live_runs_coexist_and_a_sweep_takes_neither(self):
        """Гонка: живу теку не сміє зняти ніхто — і двох живих теж.

        Прибирання судить теку за маркером власника; якби воно судило за часом,
        довгий прогін повного набору втратив би свій `TMPDIR` посеред роботи.
        Тут два СПРАВЖНІ живі прогони (підставна збірка висить), третій прогін
        проходить повз них зі своїм прибиранням — і обидві теки лишаються; після
        смерті обох наступний прогін прибирає обидві.
        """
        self.write_hanging_gradle()
        first = self.start_run()
        self.addCleanup(first.kill)
        first_dir = self.wait_for_new_dir(set(), first)
        second = self.start_run()
        self.addCleanup(second.kill)
        second_dir = self.wait_for_new_dir({first_dir}, second)
        self.assertNotEqual(first_dir, second_dir, "два прогони мусять мати дві теки")

        fast = self.write_fast_gradle()   # третій прогін: швидкий, зі своїм sweep
        third = self.run_wrapper(java_home=self.java_home, gradle=fast)

        self.assertEqual(0, third.returncode, third.stderr)
        self.assertNotIn("прибрано застарілу теку прогону", third.stderr,
                         "живу теку не сміє зняти жодне прибирання")
        still_there = set(self.leftovers())
        self.assertIn(first_dir, still_there, "живу теку не сміє зняти жодне прибирання")
        self.assertIn(second_dir, still_there, "живу теку не сміє зняти жодне прибирання")

        for process in (first, second):
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.communicate(timeout=60)

        again = self.run_wrapper(java_home=self.java_home, gradle=fast)

        self.assertEqual(0, again.returncode, again.stderr)
        self.assertIn(first_dir, again.stderr)
        self.assertIn(second_dir, again.stderr)
        self.assertEqual([], self.leftovers(), "після смерті обох — обидві прибрані")

    def test_a_stale_run_dir_that_cannot_be_removed_does_not_fail_the_run(self):
        """Прибирання не сильніше за `rm`: зайнята тека лишається, прогін іде далі.

        Межа названа прогоном, а не словом: теку, яку не вдалося зняти, прибирання
        називає вголос, а робота не спиняється — інакше чужий залишок валив би
        кожен наступний прогін.
        """
        stuck = self.tmp / "slukhayka-tests.stuck"
        (stuck / "gradle-tmp").mkdir(parents=True)
        os.utime(stuck, (1, 1))
        stuck.chmod(0o500)          # без запису в теці: `rm` не забере її вміст

        self.write_gradle()
        result = self.run_wrapper(java_home=self.java_home)
        stuck.chmod(0o700)          # повернути права, щоб фікстура прибралася

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("прибрати не вдалося", result.stderr)
        self.assertIn("slukhayka-tests.stuck", result.stderr)
        self.assertTrue(stuck.exists(), "тека лишається: прибирання не сильніше за rm")

    def test_an_unavailable_tmpdir_refuses_with_a_named_reason(self):
        """`TMPDIR`, якого немає, — відмова з ПРИЧИНОЮ, а не тихий код 2.

        До #1305 тут стояв `mktemp -d … || exit 2`: причина не називалася взагалі.
        Тепер причину друкує прибиральник — і прогін усе одно не стартує, бо теки
        прогону нема куди створити (вона ж `TMPDIR` для всього Gradle).
        """
        self.write_gradle()
        environment = self.environment(java_home=self.java_home)
        environment["TMPDIR"] = str(self.root / "nope")

        result = subprocess.run(
            ["bash", str(TEST_ALL)], check=False, capture_output=True, text=True,
            env=environment,
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("nope", result.stderr)
        self.assertIn("tmp_budget:", result.stderr,
                      "причину мусить назвати спільний прибиральник, а не тихий код 2")
        self.assertFalse(self.gradle_log.exists(), "збірка не сміє стартувати без теки прогону")

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

    def test_documentation_only_change_runs_nothing(self):
        self.write_gradle()

        result = subprocess.run(
            [
                "bash",
                str(TEST_CHANGED),
                "--changed-file",
                "docs/plans/2026-10-27-retro-agent-environment.md",
                "--changed-file",
                "AGENTS.md",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(java_home=self.java_home),
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("no test partitions to run", result.stdout)
        self.assertFalse(
            self.gradle_log.exists(),
            "documentation-only diff must not reach Gradle",
        )

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

    def write_hanging_gradle(self) -> None:
        """Підставна збірка, яка не закінчується: прогін мусить бути В ПОЛЬОТІ.

        Тільки такий прогін можна вбити SIGKILL'ом «у момент роботи» — і саме
        так виглядає прогін, який знесла ескалація `build-slot-guard.py` після
        `--grace-seconds`, OOM чи перервана сесія.
        """
        self.gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$*\" >> \"$WRAPPER_TEST_LOG\"\n"
            "sleep 300\n",
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

    def run_changed(self, *arguments: str, session: bool = False, environment: dict = None):
        return subprocess.run(
            ["bash", str(TEST_CHANGED), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment() if environment is None else environment,
            # Свій сеанс потрібен лише тому прогону, який сам собі шле TERM:
            # `kill -TERM 0` б'є по гурту процесів, і без нового сеансу він
            # дістав би й сам набір тестів.
            start_new_session=session,
            timeout=60,
        )

    def leftovers(self):
        return sorted(one.name for one in self.tmp.glob("slukhayka-changed-tests.*"))

    def invocations(self) -> list:
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()

    def test_no_scratch_directory_survives_a_run(self):
        """Сторож: тека прогону зникає і на коді 0, і на падінні, і на сигналі.

        Ноги названі за МІСЦЕМ, а не лише за кінцем: `exec` у `test-changed.sh`
        стоїть у ДВОХ місцях — невідома база й відкат селектора на повний набір,
        — і сторож мусить пройти кожне. Одна нога лишала друге місце непіненим:
        воно поверталося до голого `exec`, і сторож був зелений (знахідка огляду
        PR #1297). Друге місце — щоденне: `strings.xml` селектор не відображає.

        Кожна нога доводить, що прогін СПРАВДІ відбувся (лог підставного
        `gradlew` виріс): нога, яку задовольняє ранній вихід, не перевіряє нічого.
        Інший клас теч — тека, лишена після SIGKILL-ескалації
        (`build-slot-guard.py`), — цим сторожем не міряється взагалі: він не
        проходить крізь `exec`.
        """
        self.write_gradle()

        unavailable = self.run_changed("--base", "refs/heads/does-not-exist")
        self.assertEqual(0, unavailable.returncode, unavailable.stderr)
        self.assertIn("unavailable; running the full suite", unavailable.stderr)
        self.assertTrue(self.invocations(), "прогін не виконався — сторож нічого не доводить")
        self.assertEqual([], self.leftovers(), "тека лишилася після першого exec-місця")

        self.write_gradle(exit_status=17)

        failed = self.run_changed("--base", "refs/heads/does-not-exist")
        # `exec` навмисний: код повного набору доходить до викликача незміненим.
        self.assertEqual(17, failed.returncode, failed.stderr)
        self.assertEqual([], self.leftovers(), "тека лишилася після невдалого прогону")

        # Друге місце `exec`: шлях, який не відображається на партицію, віддає
        # прогін повному набору — і прибирає теку так само. Лог тут такий самий
        # обов'язковий, як у першій нозі: `printf …; exit 0` замість прогону
        # лишав би цю ногу пустою, а модуль — зеленим (знахідка огляду #1297).
        self.write_gradle()
        before = len(self.invocations())

        fell_back = self.run_changed(
            "--changed-file", "app/src/main/res/values/strings.xml"
        )
        self.assertEqual(0, fell_back.returncode, fell_back.stderr)
        self.assertIn("fell back to the full suite", fell_back.stdout)
        self.assertGreater(len(self.invocations()), before,
                           "прогін не дійшов до підставного gradlew — нога відкату "
                           "нічого не перевірила")
        self.assertEqual([], self.leftovers(), "тека лишилася після відкату селектора")

        # Сигнал: TERM дістається всьому гурту прогону, тож його бачить і сам
        # wrapper. Код 143 (128+15) пінить, що прогін скінчився САМЕ сигналом, а
        # не раннім виходом; лог пінить, що він до сигналу встиг дійти до
        # підставного `gradlew`. Що саме прибирає теку — пастка на TERM чи на
        # EXIT — ззовні не видно, і сторож на цьому не стоїть: його межа — тека.
        self.write_gradle(signal_the_group=True)
        before = len(self.invocations())

        signalled = self.run_changed(
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
            session=True,
        )
        self.assertEqual(143, signalled.returncode, signalled.stderr)
        self.assertGreater(len(self.invocations()), before,
                           "прогін не дійшов до підставного gradlew — сигнал нічого "
                           "не перевірив")
        self.assertEqual([], self.leftovers(), "тека лишилася після сигналу")

        # Межа прибирання, яке САМЕ не може прибрати теку: `rm`, що завжди
        # повертає 1 (ім'я з оточення — той самий прийом, що `BASH_FUNC_*` у
        # фікстурах оточення). Сторож пінить усі три частини межі: попередження
        # видно, код повного набору не зіпсовано прибиранням, і тека лишається —
        # прибирання не сильніше за `rm`. Це остання нога: вона свідомо лишає
        # теку, і наступна нога впала б на ній.
        self.write_gradle(exit_status=17)
        broken_rm = self.environment()
        broken_rm["BASH_FUNC_rm%%"] = "() { return 1; }"

        unremovable = self.run_changed(
            "--base", "refs/heads/does-not-exist", environment=broken_rm
        )
        self.assertEqual(17, unremovable.returncode, unremovable.stderr)
        self.assertIn("cannot remove", unremovable.stderr)
        self.assertEqual(1, len(self.leftovers()),
                         "тека мусить лишитися: прибирання не сильніше за rm")
        shutil.rmtree(self.tmp / self.leftovers()[0])

    def kill_a_live_run(self, path: str):
        """Прогін, убитий SIGKILL'ом У ПОЛЬОТІ: підставна збірка висить, гурт гине.

        Повертає (ім'я лишеної теки, PID убитого прогону) — і самі ноги, без яких
        сторож нічого не доводив би: прогін мусив дійти до підставної збірки (лог
        виріс) і мусив лишити теку («зникло» інакше означало б «не було»).
        """
        self.write_hanging_gradle()
        victim = subprocess.Popen(
            ["bash", str(TEST_CHANGED), "--changed-file", path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=self.environment(),
            # Свій сеанс: `kill -KILL` по гурту не сміє дістати сам набір тестів.
            start_new_session=True,
        )
        self.addCleanup(victim.kill)
        deadline = time.monotonic() + 60
        while (not self.leftovers() or not self.invocations()) and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(self.leftovers(),
                        "прогін не створив теку — сторож нічого не доводить")
        self.assertTrue(self.invocations(),
                        "прогін не дійшов до підставної збірки — вбивати нема чого")

        os.killpg(os.getpgid(victim.pid), signal.SIGKILL)
        _out, killed_stderr = victim.communicate(timeout=60)

        left = self.leftovers()
        self.assertEqual(1, len(left),
                         "SIGKILL мусить лишити теку: EXIT-пастки він не виконує: %s"
                         % killed_stderr)
        return left[0], victim.pid

    def test_a_scratch_left_by_a_sigkilled_run_is_reaped_by_the_next_run(self):
        """Сторож: тека, лишена SIGKILL'ом, зникає на СТАРТІ наступного прогону.

        Пастка над SIGKILL не владна — це фізика, а не недогляд скрипта, — тож
        тека прогону лишалася назавжди (їх назбиралося 65 у `/tmp`). Прибрати її
        може лише наступний прогін, і це тут відтворено СПРАВЖНІМ SIGKILL:
        підставна збірка висить, прогін убивають `kill -KILL` по гурту (так
        робить ескалація `build-slot-guard.py`, OOM і перервана сесія), тека
        лишається — і наступний прогін мусить її прибрати, назвавши вголос.

        Межа: сторож бачить лише те, що створено під тим `$TMPDIR`, який він сам
        дає. Тека в жорстко прописаному `/tmp` лишилася б для нього невидимою, як
        і в сусіднього сторожа цього класу; і він не міряє, скільки тек уже
        лежить в історії, — він доводить, що залишок зникає.
        """
        path = "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt"
        left, _pid = self.kill_a_live_run(path)

        self.write_gradle()
        again = self.run_changed("--changed-file", path)

        self.assertEqual(0, again.returncode, again.stderr)
        self.assertIn("прибрано застарілу теку прогону", again.stderr)
        self.assertIn(left, again.stderr)
        self.assertEqual([], self.leftovers(),
                         "залишок SIGKILL'а мусив зникнути на старті наступного прогону")

    def test_the_scratch_of_a_killed_run_names_that_run_as_its_owner(self):
        """Маркер називає ВБИТИЙ прогін, а не короткоживучий хелпер.

        Це не дрібниця: `bash` створює теку через `python3 tmp_budget.py claim`,
        і маркер на хелпері зробив би живу теку «застарілою» за мілісекунди
        після створення — сусідній прогін зніс би її посеред роботи. Тому тут
        піниться і сам маркер, і те, що він називає саме той PID, який убито.
        """
        path = "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt"
        left, pid = self.kill_a_live_run(path)

        marker = self.tmp / left / "owner.pid"
        self.assertTrue(marker.is_file(), "теку лишено разом із маркером власника")
        self.assertEqual(str(pid), marker.read_text(encoding="utf-8").strip())


if __name__ == "__main__":
    unittest.main()
