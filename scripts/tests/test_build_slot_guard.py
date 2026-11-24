"""Тести сторожа слота: обидві гілки на фейкових числах пам'яті.

Реальне вичерпання пам'яті тут не потрібне й небажане: сторож читає
MemAvailable і swap із файлів, які підставляє тест, тож відмова, зупинка й
сигнали перевіряються за секунди.

Тести синхронізуються на файлі-маркері, який створює сама команда, а не на
фіксованому `sleep`: інакше на завантаженому раннері перевірка обганяла б старт
сторожа і тест падав би не з тієї причини.

Кожен тест зупинки додатково доводить, що процеси прогону **справді зникли**:
сторож, який нічого не сигналить, не має права проходити цей набір.
"""

import collections
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GUARD = REPO_ROOT / "scripts" / "build-slot-guard.py"

GuardedCommand = collections.namedtuple("GuardedCommand", "argv marker pid_file")


def meminfo_text(available_mb: int) -> str:
    return (
        "MemTotal:       16000000 kB\n"
        "MemFree:          300000 kB\n"
        f"MemAvailable:   {available_mb * 1024} kB\n"
    )


def swaps_text(zram_used_mb: int) -> str:
    return (
        "Filename\t\t\t\tType\t\tSize\t\tUsed\t\tPriority\n"
        "/swap/swapfile                          file\t\t16244732\t0\t\t0\n"
        f"/dev/zram0                              partition\t16243708\t{zram_used_mb * 1024}\t\t100\n"
    )


class BuildSlotGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.meminfo = self.root / "meminfo"
        self.swaps = self.root / "swaps"
        self.meminfo.write_text(meminfo_text(8000), encoding="utf-8")
        self.swaps.write_text(swaps_text(0), encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    # ── помічники ────────────────────────────────────────────────────────────

    def guard_argv(self, command, **options):
        argv = [
            sys.executable,
            str(GUARD),
            "--meminfo",
            str(self.meminfo),
            "--swaps",
            str(self.swaps),
        ]
        for key, value in options.items():
            argv += [f"--{key.replace('_', '-')}", str(value)]
        return argv + ["--"] + [str(part) for part in command]

    def run_guard(self, command, **options):
        return subprocess.run(
            self.guard_argv(command, **options), capture_output=True, text=True, timeout=60
        )

    def start_guard(self, command, **options):
        return subprocess.Popen(
            self.guard_argv(command, **options),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def guarded_command(self, seconds: int = 30, ignore_term: bool = False) -> GuardedCommand:
        """Команда, яка лишає по собі маркер і власний pid та живе довго."""
        token = uuid.uuid4().hex[:8]
        marker = self.root / f"started-{token}"
        pid_file = self.root / f"pid-{token}"
        trap = 'trap "" TERM; ' if ignore_term else ""
        script = (
            f"{trap}echo $$ > {pid_file}; : > {marker}; "
            f"while :; do sleep {seconds}; done"
        )
        return GuardedCommand(["sh", "-c", script], marker, pid_file)

    def wait_for(self, path: Path, timeout: float = 15.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if path.exists():
                return True
            time.sleep(0.02)
        return False

    def wait_gone(self, pid: int, timeout: float = 5.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.05)
        return False

    def read_pid(self, path: Path) -> int:
        return int(path.read_text(encoding="utf-8").strip())

    # ── відмова ──────────────────────────────────────────────────────────────

    def test_refuses_below_the_default_floor(self):
        # 1900 MB — нижче типового порогу 2000 MB, без жодних прапорців.
        self.meminfo.write_text(meminfo_text(1900), encoding="utf-8")
        marker = self.root / "ran"
        done = self.run_guard(["touch", str(marker)])
        self.assertEqual(3, done.returncode)
        self.assertFalse(marker.exists(), "команда не мала запускатися")
        self.assertIn("ВІДМОВА", done.stderr)
        self.assertIn("1900.0 MB", done.stderr, "повідомлення мусить називати справжнє число")

    def test_refuses_with_explicit_floor(self):
        self.meminfo.write_text(meminfo_text(200), encoding="utf-8")
        done = self.run_guard(["true"], floor_mb=1000)
        self.assertEqual(3, done.returncode)

    # ── прохід ───────────────────────────────────────────────────────────────

    def test_passes_the_command_exit_code_through(self):
        done = self.run_guard(["sh", "-c", "exit 7"])
        self.assertEqual(7, done.returncode)

    def test_runs_when_memory_cannot_be_measured(self):
        self.meminfo.write_text("MemTotal:       16000000 kB\n", encoding="utf-8")
        done = self.run_guard(["sh", "-c", "exit 5"])
        self.assertEqual(5, done.returncode)
        self.assertIn("без нагляду", done.stderr)

    def test_keeps_running_between_the_critical_line_and_the_start_floor(self):
        # 500 MB — нижче стартового порогу (1000), але вище критичної межі (200):
        # прогін, який уже почався, має право жити.
        token = uuid.uuid4().hex[:8]
        marker = self.root / f"started-{token}"
        pid_file = self.root / f"pid-{token}"
        command = ["sh", "-c", f"echo $$ > {pid_file}; : > {marker}; sleep 2"]
        runner = self.start_guard(
            command, floor_mb=1000, critical_mb=200, poll_seconds=0.1
        )
        self.assertTrue(self.wait_for(marker), "команда не стартувала")
        self.meminfo.write_text(meminfo_text(500), encoding="utf-8")
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(0, runner.returncode, f"сторож зупинив прогін зарано: {stderr}")

    # ── зупинка ──────────────────────────────────────────────────────────────

    def test_stops_and_kills_the_run_when_memory_falls(self):
        guarded = self.guarded_command()
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=2
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(4, runner.returncode)
        self.assertIn("ЗУПИНЯЮ", stderr)
        self.assertTrue(self.wait_gone(pid), f"процес {pid} пережив сторожа")

    def test_stop_costs_one_grace_not_two(self):
        # Прямий нащадок стає зомбі одразу; якби цикл чекав на групу, не
        # збираючи його, кожна зупинка коштувала б два grace і завжди доходила
        # б до SIGKILL.
        guarded = self.guarded_command()
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=1.0
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
        started = time.monotonic()
        stdout, stderr = runner.communicate(timeout=30)
        elapsed = time.monotonic() - started
        self.assertEqual(4, runner.returncode)
        self.assertTrue(self.wait_gone(pid))
        self.assertLess(elapsed, 1.8, f"зупинка коштувала два grace: {elapsed:.2f} с")

    def test_kills_a_group_member_that_ignores_sigterm(self):
        guarded = self.guarded_command(ignore_term=True)
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=0.5
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
        started = time.monotonic()
        stdout, stderr = runner.communicate(timeout=30)
        elapsed = time.monotonic() - started
        self.assertEqual(4, runner.returncode)
        self.assertTrue(self.wait_gone(pid), "SIGTERM було проігноровано, SIGKILL не прийшов")
        self.assertGreaterEqual(elapsed, 0.5 - 0.05, "ескалація мала почекати grace")

    def test_second_signal_during_cleanup_does_not_abort_it(self):
        guarded = self.guarded_command(ignore_term=True)
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=1.0
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
        time.sleep(0.5)  # сторож уже в SIGTERM-фазі прибирання
        runner.send_signal(signal.SIGTERM)
        runner.send_signal(signal.SIGTERM)
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(
            4, runner.returncode, f"другий сигнал зірвав прибирання: rc={runner.returncode} {stderr}"
        )
        self.assertTrue(
            self.wait_gone(pid), "прогін пережив сторожа — саме та форма, яку сторож мав прибрати"
        )

    def test_kills_a_grandchild_in_the_run_group(self):
        token = uuid.uuid4().hex[:8]
        marker = self.root / f"started-{token}"
        grandchild = self.root / f"grandchild-{token}"
        command = ["sh", "-c", f"sleep 600 & echo $! > {grandchild}; : > {marker}; wait"]
        runner = self.start_guard(
            command, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=2
        )
        self.assertTrue(self.wait_for(marker), "команда не стартувала")
        self.assertTrue(self.wait_for(grandchild), "внук не стартував")
        pid = self.read_pid(grandchild)
        self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(4, runner.returncode)
        self.assertTrue(self.wait_gone(pid, timeout=2), "внук пережив зупинку групи")

    def test_stops_when_swap_grows_beyond_the_ceiling(self):
        self.swaps.write_text(swaps_text(5000), encoding="utf-8")
        guarded = self.guarded_command()
        runner = self.start_guard(
            guarded.argv,
            floor_mb=1000,
            critical_mb=400,
            swap_growth_mb=2000,
            poll_seconds=0.1,
            grace_seconds=2,
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        self.swaps.write_text(swaps_text(8000), encoding="utf-8")
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(4, runner.returncode)
        self.assertIn("swap виріс", stderr)
        self.assertTrue(self.wait_gone(pid, timeout=2), "прогін пережив зупинку за swap")

    def test_does_not_stop_on_swap_that_was_already_high(self):
        # 5000 MB у swap — спадок попередніх прогонів; цей прогін його не чіпав.
        self.swaps.write_text(swaps_text(5000), encoding="utf-8")
        done = self.run_guard(
            ["sh", "-c", "sleep 1; exit 0"],
            floor_mb=1000,
            critical_mb=400,
            swap_growth_mb=2000,
            poll_seconds=0.1,
        )
        self.assertEqual(
            0, done.returncode, f"абсолютна стеля спинила б кожен прогін: {done.stderr}"
        )

    def test_stop_message_does_not_round_over_the_line(self):
        # 818790 kB = 799.6 MB: без десяткової воно читалося б як
        # «800 MB < критичних 800 MB».
        self.meminfo.write_text(meminfo_text(1000), encoding="utf-8")
        guarded = self.guarded_command()
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=800, poll_seconds=0.1, grace_seconds=2
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        self.meminfo.write_text(
            "MemTotal:       16000000 kB\nMemAvailable:   818790 kB\n", encoding="utf-8"
        )
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(4, runner.returncode)
        self.assertIn("799.6", stderr)
        self.assertNotIn("800 MB < критичних 800 MB", stderr)

    def test_stops_the_run_when_the_guard_itself_is_terminated(self):
        guarded = self.guarded_command()
        runner = self.start_guard(
            guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=2
        )
        self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
        pid = self.read_pid(guarded.pid_file)
        runner.send_signal(signal.SIGTERM)
        stdout, stderr = runner.communicate(timeout=30)
        self.assertEqual(128 + signal.SIGTERM, runner.returncode)
        self.assertTrue(
            self.wait_gone(pid),
            "прогін пережив сторожа — саме та форма, яку сторож мав прибрати",
        )

    def test_does_not_touch_a_process_outside_its_group(self):
        decoy = subprocess.Popen(["sleep", "20"], start_new_session=True)
        try:
            guarded = self.guarded_command()
            runner = self.start_guard(
                guarded.argv, floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=2
            )
            self.assertTrue(self.wait_for(guarded.marker), "команда не стартувала")
            self.meminfo.write_text(meminfo_text(100), encoding="utf-8")
            stdout, stderr = runner.communicate(timeout=30)
            self.assertEqual(4, runner.returncode)
            time.sleep(0.2)
            os.kill(decoy.pid, 0)  # кине ProcessLookupError, якщо сторож зачепив чуже
        finally:
            decoy.kill()
            decoy.wait()

    # ── аргументи ────────────────────────────────────────────────────────────

    def test_refuses_inverted_thresholds(self):
        done = self.run_guard(["true"], floor_mb=400, critical_mb=1000)
        self.assertEqual(2, done.returncode)
        self.assertIn("нижчою за стартовий поріг", done.stderr)

    def test_refuses_zero_poll_interval(self):
        done = self.run_guard(["true"], poll_seconds=0)
        self.assertEqual(2, done.returncode)
        self.assertIn("інтервал семплінгу", done.stderr)

    def test_refuses_without_a_command(self):
        done = self.run_guard([])
        self.assertEqual(2, done.returncode)
        self.assertIn("немає команди", done.stderr)

    def test_reports_a_missing_binary_as_a_usage_error(self):
        done = self.run_guard([str(self.root / "нема-такої-команди")])
        self.assertEqual(2, done.returncode)
        self.assertIn("не знайдено", done.stderr)


if __name__ == "__main__":
    unittest.main()
