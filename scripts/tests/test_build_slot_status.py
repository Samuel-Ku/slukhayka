import fcntl
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLI = REPO_ROOT / "scripts" / "build-slot-status.py"
SPEC = importlib.util.spec_from_file_location("build_slot_status", CLI)
MODULE = importlib.util.module_from_spec(SPEC)
# The dataclass decorator resolves annotations through sys.modules, so the
# module must be registered before it executes.
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SlotProbeTest(unittest.TestCase):
    """free|busy is read by taking the lock, not by guessing from files."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.slot = self.root / ".worktrees" / ".build-slot"
        self.slot.parent.mkdir(parents=True)
        self.slot.touch()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_a_free_slot_is_free(self):
        self.assertEqual("free", MODULE.slot_state(self.slot))

    def test_a_held_slot_is_busy(self):
        handle = self.slot.open("a+")
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual("busy", MODULE.slot_state(self.slot))
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)
            handle.close()
        self.assertEqual("free", MODULE.slot_state(self.slot))

    def test_a_missing_slot_is_an_unknown_not_a_free_answer(self):
        with self.assertRaises(MODULE.Unknown) as caught:
            MODULE.slot_state(self.root / ".worktrees" / ".nope")
        self.assertIn("no build slot", str(caught.exception))


class DescribeTest(unittest.TestCase):
    def test_running_comes_before_waiting_and_roles_are_honest(self):
        raw = [
            (20, ["flock", "-w", "7200", ".build-slot", "python3", "guard"], False),
            (21, ["flock", "7200", ".build-slot", "python3", "guard"], True),
        ]

        described = MODULE.describe(Path("/tmp/.build-slot"), raw)

        self.assertEqual([21, 20], [process.pid for process in described])
        self.assertEqual(["running", "waiting"], [process.role for process in described])
        self.assertEqual("flock 7200 .build-slot python3 guard", described[0].command)

    def test_elapsed_seconds_are_formatted_for_a_human(self):
        self.assertEqual("?", MODULE.format_elapsed(None))
        self.assertEqual("5s", MODULE.format_elapsed(5))
        self.assertEqual("1m05s", MODULE.format_elapsed(65))
        self.assertEqual("1h01m", MODULE.format_elapsed(3700))


class ProcessScanTest(unittest.TestCase):
    def test_a_process_naming_the_slot_is_found_and_classified(self):
        with tempfile.TemporaryDirectory() as directory:
            slot = Path(directory) / ".build-slot"
            slot.touch()
            process = subprocess.Popen(
                [sys.executable, "-c", "import time; time.sleep(30)", str(slot)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                deadline = time.monotonic() + 5
                entries = []
                while time.monotonic() < deadline:
                    entries = MODULE.process_entries(slot)
                    if any(pid == process.pid for pid, _, _ in entries):
                        break
                    time.sleep(0.05)
                matches = [pid for pid, _, _ in entries]
                self.assertIn(process.pid, matches)
                described = MODULE.describe(slot, entries)
                found = [item for item in described if item.pid == process.pid]
                self.assertEqual(1, len(found))
                self.assertEqual("waiting", found[0].role)
                self.assertIsNotNone(found[0].elapsed_seconds)
            finally:
                process.terminate()
                process.wait(timeout=5)


class CliTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.slot = self.root / ".worktrees" / ".build-slot"

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_cli(self, *arguments: str):
        return subprocess.run(
            [sys.executable, str(CLI), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_a_missing_tree_is_an_unknown_exit(self):
        result = self.run_cli("--root", str(self.root))
        self.assertEqual(2, result.returncode)
        self.assertIn("no build slot", result.stderr)

    def test_a_free_slot_is_reported_as_free(self):
        self.slot.parent.mkdir(parents=True)
        self.slot.touch()
        result = self.run_cli("--root", str(self.root))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("free", result.stdout)
        self.assertIn(str(self.slot), result.stdout)

    def test_json_carries_the_slot_state_and_processes(self):
        self.slot.parent.mkdir(parents=True)
        self.slot.touch()
        result = self.run_cli("--root", str(self.root), "--json")
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(str(self.slot), payload["slot"])
        self.assertEqual("free", payload["state"])
        self.assertEqual([], payload["processes"])  # nothing names this slot


if __name__ == "__main__":
    unittest.main()
