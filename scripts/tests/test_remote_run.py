"""Тести scripts/swarm/remote_run.py на підставному ssh.

Стиль як у test_merge_pr.py: скрипт запускається процесом, а мережевий ssh
підмінюється змінною REMOTE_RUN_SSH. Жоден тест не ходить у мережу й не
торкається справжнього воркера.

Перевіряється саме те, чим цей раннер відрізняється від ручного ssh-циклу:
dry-run не запускає нічого; preflight відмовляє до старту; exit-код команди
доходить; лог пишеться; stop викликається навіть після збою; timeout
перетворюється на код 4; --status/--kill-orphans будують правильні команди.
"""

import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REMOTE_RUN = REPO_ROOT / "scripts" / "swarm" / "remote_run.py"

FAKE_SSH = r"""#!/usr/bin/env python3
import json
import os
import sys

arguments = sys.argv[1:]
command = arguments[-1] if arguments else ""
log = os.environ["FAKE_SSH_LOG"]
with open(log, "a", encoding="utf-8") as handle:
    handle.write(json.dumps(arguments) + "\n")

if command == "true":
    code = int(os.environ.get("FAKE_PREFLIGHT_EXIT", "0"))
    if code:
        print("preflight: ssh refused", file=sys.stderr)
    sys.exit(code)

mode = os.environ.get("FAKE_SSH_MODE", "ok")
if mode == "fail":
    print("remote command failed", file=sys.stderr)
    sys.exit(7)
if mode == "timeout":
    print("remote timeout", file=sys.stderr)
    sys.exit(124)
print(os.environ.get("FAKE_SSH_STDOUT", "remote-ok"))
code = int(os.environ.get("FAKE_SSH_COMMAND_EXIT", "0"))
if code:
    print(os.environ.get("FAKE_SSH_STDERR", ""), end="", file=sys.stderr)
sys.exit(code)
"""

HOST = "worker@example.invalid"
DIR = "/srv/lane with space"


class RemoteRunTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.mkdtemp(prefix="remote-run-")
        self.root = Path(self.temp)
        self.ssh = self.root / "ssh"
        self.ssh.write_text(FAKE_SSH, encoding="utf-8")
        self.ssh.chmod(self.ssh.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        self.log = self.root / "ssh.log"
        self.workdir = self.root / "lane"
        self.workdir.mkdir()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.temp, ignore_errors=True)

    def run_cli(self, *arguments, **environment):
        env = {
            "PATH": "/usr/bin:/bin",
            "HOME": self.temp,
            "LC_ALL": "C",
            "REMOTE_RUN_SSH": str(self.ssh),
            "FAKE_SSH_LOG": str(self.log),
        }
        env.update(environment)
        completed = subprocess.run(
            [sys.executable, str(REMOTE_RUN), *arguments],
            capture_output=True, text=True, cwd=str(self.workdir), env=env, check=False,
        )
        return completed

    def ssh_calls(self):
        if not self.log.exists():
            return []
        return [" ".join(json.loads(line)) for line in self.log.read_text(encoding="utf-8").splitlines() if line]

    def test_dry_run_prints_quoted_job_and_touches_no_ssh(self):
        completed = self.run_cli(
            HOST, "--dir", DIR, "--dry-run", "--", "bash", "-lc", "echo hi",
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("REMOTE_JOB=cd '/srv/lane with space'", completed.stdout)
        self.assertIn("REMOTE_RUN_TAG=remote-run-", completed.stdout)
        self.assertIn("3600s bash -lc 'echo hi'", completed.stdout)
        self.assertIn("REMOTE_STOP=", completed.stdout)
        self.assertEqual([], self.ssh_calls(), "dry-run мусить не викликати ssh")

    def test_preflight_refusal_exits_3_without_starting_the_job(self):
        completed = self.run_cli(
            HOST, "--dir", DIR, "--", "true", FAKE_PREFLIGHT_EXIT="1",
        )
        self.assertEqual(3, completed.returncode, completed.stdout + completed.stderr)
        calls = self.ssh_calls()
        self.assertEqual(1, len(calls), calls)
        self.assertTrue(calls[0].endswith(" true"), calls[0])

    def test_a_failed_command_exits_1_and_writes_its_log(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--", "false", FAKE_SSH_MODE="fail")
        self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("remote command failed", completed.stdout)
        logs = list((self.workdir / ".remote-runs").glob("remote-run-*.log"))
        self.assertEqual(1, len(logs))
        self.assertIn("remote command failed", logs[0].read_text(encoding="utf-8"))
        calls = self.ssh_calls()
        self.assertGreaterEqual(len(calls), 3, calls)  # preflight + job + stop
        self.assertTrue(any("REMOTE_RUN_TAG=remote-run-" in line for line in calls), calls)

    def test_success_streams_output_and_exits_0(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--", "echo", "hello")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("remote-ok", completed.stdout)
        self.assertTrue(any("REMOTE_RUN_TAG=" in line for line in self.ssh_calls()))

    def test_remote_timeout_is_exit_4(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--", "sleep", "99", FAKE_SSH_MODE="timeout")
        self.assertEqual(4, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("124", completed.stderr)

    def test_status_builds_the_slot_command(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--status")
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("remote-ok", completed.stdout)
        calls = self.ssh_calls()
        self.assertEqual(2, len(calls))  # preflight + status
        self.assertIn("build-slot-status.py", calls[-1])
        self.assertIn("--root", calls[-1])

    def test_kill_orphans_only_touches_its_own_marker(self):
        completed = self.run_cli(HOST, "--kill-orphans")
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        calls = self.ssh_calls()
        self.assertEqual(2, len(calls))  # preflight + kill-orphans
        self.assertIn("REMOTE_RUN_TAG=$tag", calls[-1])
        self.assertIn("grep -Fqx", calls[-1])
        self.assertIn("kill -TERM", calls[-1])

    def test_status_and_kill_orphans_are_mutually_exclusive(self):
        completed = self.run_cli(HOST, "--status", "--kill-orphans")
        self.assertEqual(2, completed.returncode)
        self.assertIn("взаємовиключні", completed.stderr)

    def test_a_foreign_tag_is_refused(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--tag", "other", "--", "true")
        self.assertEqual(2, completed.returncode)
        self.assertIn("remote-run-", completed.stderr)

    def test_a_tag_cannot_inject_shell_syntax(self):
        """The tag lands in the remote shell; only the safe alphabet is allowed."""
        for tag in ("remote-run-x; true", "remote-run-x y", "remote-run-x$(touch /tmp/pwned)"):
            with self.subTest(tag=tag):
                completed = self.run_cli(
                    HOST, "--dir", DIR, "--tag", tag, "--", "true",
                )
                self.assertEqual(2, completed.returncode, completed.stdout + completed.stderr)
                self.assertIn("заборонені", completed.stderr)
        self.assertEqual([], self.ssh_calls(), "injection must not reach ssh")

    def test_status_surfaces_a_failing_slot_script(self):
        completed = self.run_cli(
            HOST, "--dir", DIR, "--status",
            FAKE_SSH_COMMAND_EXIT="5",
            FAKE_SSH_STDERR="slot script: cannot read /proc\n",
        )
        self.assertEqual(5, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("slot script", completed.stderr)

    def test_status_dry_run_honours_the_slot_root_override(self):
        completed = self.run_cli(
            HOST, "--dir", DIR, "--status", "--slot-root", "/custom/root", "--dry-run",
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("root=/custom/root", completed.stdout)
        self.assertNotIn("--git-common-dir", completed.stdout)

    def test_status_dry_run_derives_the_root_when_not_overridden(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--status", "--dry-run")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("--git-common-dir", completed.stdout)

    def test_kill_orphans_spares_a_pid_file_of_another_tag(self):
        module = self.load_module()
        tag_file = "remote-run-A"
        process = subprocess.Popen(
            ["sleep", "30"],
            env={"PATH": "/usr/bin:/bin", "REMOTE_RUN_TAG": "remote-run-B"},
        )
        try:
            jobs = Path(self.temp) / ".swarm-jobs"
            jobs.mkdir()
            (jobs / f"{tag_file}.pid").write_text(str(process.pid), encoding="utf-8")
            result = subprocess.run(
                ["bash", "-c", module.remote_kill_orphans()],
                capture_output=True, text=True, check=False,
                env={"PATH": "/usr/bin:/bin", "HOME": self.temp, "LC_ALL": "C"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIsNone(process.poll(), "a PID marked with another tag must survive")
            self.assertTrue((jobs / f"{tag_file}.pid").exists(), "the foreign pid file is left for a human")
        finally:
            process.kill()
            process.wait()

    def test_kill_orphans_kills_the_group_of_its_own_tag(self):
        module = self.load_module()
        tag = "remote-run-C"
        process = subprocess.Popen(
            ["sleep", "30"],
            env={"PATH": "/usr/bin:/bin", "REMOTE_RUN_TAG": tag},
            start_new_session=True,
        )
        try:
            jobs = Path(self.temp) / ".swarm-jobs"
            jobs.mkdir()
            (jobs / f"{tag}.pid").write_text(str(process.pid), encoding="utf-8")
            result = subprocess.run(
                ["bash", "-c", module.remote_kill_orphans()],
                capture_output=True, text=True, check=False,
                env={"PATH": "/usr/bin:/bin", "HOME": self.temp, "LC_ALL": "C"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("погашено", result.stdout)
            process.wait(timeout=5)
            self.assertIsNotNone(process.poll())
            self.assertFalse((jobs / f"{tag}.pid").exists())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()

    def load_module(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "remote_run_module", REPO_ROOT / "scripts" / "swarm" / "remote_run.py",
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_a_missing_command_is_refused(self):
        completed = self.run_cli(HOST, "--dir", DIR)
        self.assertEqual(2, completed.returncode)
        self.assertIn("немає команди", completed.stderr)

    def test_a_positive_timeout_is_required(self):
        completed = self.run_cli(HOST, "--dir", DIR, "--timeout", "0", "--", "true")
        self.assertEqual(2, completed.returncode)
        self.assertIn("додатним", completed.stderr)


if __name__ == "__main__":
    unittest.main()
