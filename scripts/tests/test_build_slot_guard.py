"""Тести сторожа слота: обидві гілки на фейкових числах пам'яті.

Реальне вичерпання пам'яті тут не потрібне й небажане: сторож читає
MemAvailable і swap із файлів, які підставляє тест, тож обидві гілки — відмова
до старту і зупинка під час прогону — перевіряються за секунди.
"""

import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GUARD = REPO_ROOT / "scripts" / "build-slot-guard.py"


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

    def run_guard(self, command, **options):
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
        argv += ["--"] + [str(part) for part in command]
        return subprocess.run(argv, capture_output=True, text=True, timeout=60)

    def drop_memory_after(self, seconds: float, available_mb: int) -> None:
        def drop():
            time.sleep(seconds)
            self.meminfo.write_text(meminfo_text(available_mb), encoding="utf-8")

        threading.Thread(target=drop, daemon=True).start()

    def test_refuses_before_start_when_memory_is_below_the_floor(self):
        self.meminfo.write_text(meminfo_text(200), encoding="utf-8")
        marker = self.root / "ran"
        done = self.run_guard(["touch", str(marker)], floor_mb=1000)
        self.assertEqual(3, done.returncode)
        self.assertFalse(marker.exists(), "команда не мала запускатися")
        self.assertIn("ВІДМОВА", done.stderr)
        self.assertIn("200 MB", done.stderr)

    def test_passes_the_command_exit_code_through(self):
        done = self.run_guard(["sh", "-c", "exit 7"])
        self.assertEqual(7, done.returncode)

    def test_runs_when_memory_cannot_be_measured(self):
        self.meminfo.write_text("MemTotal:       16000000 kB\n", encoding="utf-8")
        done = self.run_guard(["sh", "-c", "exit 5"])
        self.assertEqual(5, done.returncode)

    def test_stops_its_own_run_when_memory_falls_below_the_critical_line(self):
        self.drop_memory_after(0.4, 100)
        started = time.monotonic()
        done = self.run_guard(
            ["sleep", "30"], floor_mb=1000, critical_mb=400, poll_seconds=0.1, grace_seconds=2
        )
        elapsed = time.monotonic() - started
        self.assertEqual(4, done.returncode)
        self.assertLess(elapsed, 20, "зупинка мала статися швидко")
        self.assertIn("ЗУПИНЯЮ", done.stderr)

    def test_keeps_running_between_the_critical_line_and_the_start_floor(self):
        # 500 MB — нижче стартового порогу (1000), але вище критичної межі (200):
        # прогін, який уже почався, має право жити.
        self.drop_memory_after(0.4, 500)
        done = self.run_guard(
            ["sh", "-c", "sleep 1; exit 0"],
            floor_mb=1000,
            critical_mb=200,
            poll_seconds=0.1,
        )
        self.assertEqual(0, done.returncode, f"сторож зупинив прогін зарано: {done.stderr}")

    def test_stops_when_swap_exceeds_the_ceiling(self):
        self.swaps.write_text(swaps_text(5000), encoding="utf-8")
        done = self.run_guard(
            ["sleep", "30"], critical_mb=200, swap_ceiling_mb=4000, poll_seconds=0.1, grace_seconds=2
        )
        self.assertEqual(4, done.returncode)
        self.assertIn("swap", done.stderr)

    def test_does_not_touch_a_process_outside_its_group(self):
        decoy = subprocess.Popen(["sleep", "20"], start_new_session=True)
        try:
            self.drop_memory_after(0.3, 100)
            done = self.run_guard(
                ["sleep", "30"], critical_mb=400, poll_seconds=0.1, grace_seconds=2
            )
            self.assertEqual(4, done.returncode)
            time.sleep(0.2)
            os.kill(decoy.pid, 0)  # кине ProcessLookupError, якщо сторож зачепив чуже
        finally:
            decoy.kill()
            decoy.wait()


if __name__ == "__main__":
    unittest.main()
