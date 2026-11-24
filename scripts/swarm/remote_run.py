#!/usr/bin/env python3
"""Run one command on the worker without losing it when the ssh channel dies.

Why this exists. The swarm runner (`scripts/swarm/run-issue.sh`) covers tickets,
but ordinary lanes still ran long commands as hand-written `ssh … 'command'`
loops. That has three failure modes measured in one session: the local job dies
and the remote Gradle keeps holding the shared build slot (40+ minutes in
#1499's session), the exit code arrives through a pipe nobody checks, and the
only progress signal is a `tail` on the next ssh call.

This command keeps the useful part of the swarm mechanism and drops the ticket
part:

  * a preflight `ssh <host> true` — an unreachable worker is exit 3 and no job
    is started;
  * the remote shell writes its PID to `~/.swarm-jobs/<tag>.pid` and `exec`s
    `timeout -k 30 …`, so the local side can always stop the whole process
    group, not just the ssh connection;
  * the remote environment carries `REMOTE_RUN_TAG=<tag>`; cleanup and
    `--kill-orphans` kill ONLY processes whose `/proc/<pid>/environ` carries
    that marker — a recycled PID without it is left alone (the same rule as
    `run-issue.sh`, adapted because the command may be anything, not `dsh`);
  * output is streamed to the terminal and to `<tag>.log`;
  * an EXIT trap fires the stop command even on SIGINT/SIGTERM, and a remote
    `timeout` is the hard limit when the local side is SIGKILLed.

The build-slot lives on the worker: `--status` prints it (via the worker's own
`scripts/build-slot-status.py`) plus this runner's pid files.

Usage:
    scripts/swarm/remote-run.py <host> --dir <remote-dir> -- <command…>
    scripts/swarm/remote-run.py <host> --status
    scripts/swarm/remote-run.py <host> --kill-orphans
    scripts/swarm/remote-run.py <host> --dir <dir> --dry-run -- <command…>

Options: --tag NAME (default `remote-run-<UTC-stamp>-<pid>`; keep the
`remote-run-` prefix so `--kill-orphans` finds it), --timeout SECONDS (default
3600), --slot-root optional override for the build-slot root.

Limits, named:
  * the command is an argv list, NOT a shell line — pass `-- bash -lc '…'` when
    shell syntax is really needed, so quoting stays visible;
  * the marker lives in `/proc/<pid>/environ`; on a system without `/proc`
    cleanup cannot verify ownership and refuses instead of killing;
  * if the worker reboots, the pid file is stale: `--kill-orphans` names and
    removes it without killing anything;
  * the log is host-local; it is not copied back by this command.

Exit codes: 0 — remote command succeeded; 1 — remote command failed; 2 — bad
usage / preflight could not run; 3 — ssh preflight failed; 4 — remote command
timed out (remote `timeout` returns 124); 5 — slot/status or kill-orphans
subcommand failed; 130 — interrupted locally (the remote group is stopped first).
Env: REMOTE_RUN_SSH — path to ssh (default `ssh`; tests point it at a fake).
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import signal
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SSH = os.environ.get("REMOTE_RUN_SSH", "ssh")
SSH_OPTIONS = ("-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15")
JOB_DIR = "$HOME/.swarm-jobs"
TAG_PREFIX = "remote-run-"
# The tag lands in a remote shell word, a file name and a /proc marker. Only
# these characters are accepted; the prefix check alone let `--tag
# 'remote-run-x; true'` end the shell early (and report exit 0 without running
# the command), which is the injection this pattern exists to stop.
TAG_RE = re.compile(r"^remote-run-[A-Za-z0-9._-]+$")
TIMEOUT_EXIT = 124


class RemoteRunError(Exception):
    """A refusal that must not be reported as a successful run."""


def quote(value: str) -> str:
    return shlex.quote(value)


def remote_job(tag: str, directory: str, timeout: int, command: list[str]) -> str:
    """The remote shell: pid file -> exec timeout -> the command."""
    cmd = shlex.join(command)
    return (
        f"cd {quote(directory)} || exit 2\n"
        f"mkdir -p {JOB_DIR}\n"
        f"echo $$ > {JOB_DIR}/{tag}.pid\n"
        f"exec env REMOTE_RUN_TAG={tag} timeout -k 30 {timeout}s {cmd}"
    )


def remote_stop(tag: str) -> str:
    return f"""p={JOB_DIR}/{tag}.pid
if [ -f "$p" ]; then
  pid=$(cat "$p" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    if tr '\\0' '\\n' < /proc/"$pid"/environ 2>/dev/null | grep -Fqx 'REMOTE_RUN_TAG={tag}'; then
      kill -TERM -- -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
      sleep 2
      kill -KILL -- -"$pid" 2>/dev/null || true
      echo "remote-run: групу $pid погашено"
    else
      echo "remote-run: pid $pid не наш (немає маркера) — не чіпаю"
    fi
  else
    echo "remote-run: група вже мертва"
  fi
  rm -f "$p"
else
  echo "remote-run: pid-файлу немає"
fi"""


def remote_status(directory: str, slot_root: str | None) -> str:
    if slot_root is not None:
        root_setup = f"root={quote(slot_root)}"
    else:
        root_setup = (
            "root=''\n"
            f"repo=$(git -C {quote(directory)} rev-parse --path-format=absolute "
            f"--git-common-dir 2>/dev/null || true)\n"
            '[ -n "$repo" ] && root=$(dirname "$repo")'
        )
    return (
        f"{root_setup}\n"
        "slot_rc=0\n"
        'if [ -f "$root/scripts/build-slot-status.py" ]; then\n'
        '  python3 "$root/scripts/build-slot-status.py" --root "$root"\n'
        "  slot_rc=$?\n"
        "else\n"
        '  echo "remote-run: build-slot-status.py на воркері немає" >&2\n'
        "  slot_rc=5\n"
        "fi\n"
        "found=no\n"
        f"for p in {JOB_DIR}/{TAG_PREFIX}*.pid; do\n"
        '  [ -f "$p" ] || continue\n'
        "  found=yes\n"
        '  pid=$(cat "$p" 2>/dev/null || true)\n'
        '  echo "pid-file $(basename "$p") pid=${pid:-?}"\n'
        "  [ -n \"$pid\" ] && tr '\\0' ' ' < /proc/\"$pid\"/cmdline 2>/dev/null | head -c 300 && echo\n"
        "done\n"
        '[ "$found" = yes ] || echo "remote-run: власних pid-файлів немає"\n'
        "exit $slot_rc"
    )


def remote_kill_orphans() -> str:
    return f"""killed=0
for p in {JOB_DIR}/{TAG_PREFIX}*.pid; do
  [ -f "$p" ] || continue
  tag=$(basename "$p" .pid)
  pid=$(cat "$p" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    if tr '\\0' '\\n' < /proc/"$pid"/environ 2>/dev/null | grep -Fqx "REMOTE_RUN_TAG=$tag"; then
      kill -TERM -- -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
      sleep 2
      kill -KILL -- -"$pid" 2>/dev/null || true
      echo "remote-run: погашено осиротілу групу $pid ($(basename "$p"))"
      killed=$((killed + 1))
    else
      echo "remote-run: pid $pid не наш для $tag — лишаю (файл $(basename "$p"))"
      continue
    fi
  else
    echo "remote-run: застарілий pid-файл $(basename "$p") — прибираю"
  fi
  rm -f "$p"
done
echo "remote-run: осиротілих груп погашено: $killed"
"""


def ssh(host: str, command: str) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            [SSH, *SSH_OPTIONS, host, command],
            check=False, capture_output=True, text=True,
        )
    except OSError as error:
        raise RemoteRunError(f"не вдалося запустити {SSH}: {error}") from error


def preflight(host: str) -> None:
    result = ssh(host, "true")
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise RemoteRunError(
            f"воркер {host} недоступний по ssh (exit {result.returncode})"
            + (f": {detail[-1]}" if detail else "")
        )


def stream(host: str, command: str, log_path: Path) -> int:
    with log_path.open("w", encoding="utf-8") as log:
        try:
            process = subprocess.Popen(
                [SSH, *SSH_OPTIONS, host, command],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )
        except OSError as error:
            raise RemoteRunError(f"не вдалося запустити {SSH}: {error}") from error
        try:
            for line in process.stdout or []:
                sys.stdout.write(line)
                log.write(line)
                log.flush()
            return process.wait()
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()


def stop(host: str, tag: str) -> None:
    # Best effort: a dead channel is not a reason to lose the command's exit
    # code, but the failure is printed instead of swallowed.
    try:
        result = ssh(host, remote_stop(tag))
    except RemoteRunError as error:
        print(f"WARN  не вдалося прибрати віддалену групу: {error}", file=sys.stderr)
        return
    for line in (result.stdout or "").splitlines():
        print(f"  {line}")
    if result.returncode != 0:
        print(f"WARN  remote-run: stop вийшов із кодом {result.returncode}", file=sys.stderr)


def default_tag() -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{TAG_PREFIX}{stamp}-{os.getpid()}"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Ad-hoc запуск команди на воркері з PID, стрімом і прибиранням групи.",
        epilog="Команда — це argv, не shell-рядок; для shell-синтаксису: -- bash -lc '…'.",
    )
    parser.add_argument("host", help="ssh-хост воркера, напр. user@192.0.2.1")
    parser.add_argument("--dir", default=".", help="робоча тека на воркері (типово поточна)")
    parser.add_argument("--tag", help="ім'я запуску (типово remote-run-<UTC>-<pid>)")
    parser.add_argument("--timeout", type=int, default=3600, help="жорсткий ліміт, секунд (типово 3600)")
    parser.add_argument("--slot-root", help="корінь репозиторію для build-slot-status (типово --dir)")
    parser.add_argument("--status", action="store_true", help="показати build-слот і pid-файли, вийти")
    parser.add_argument("--kill-orphans", action="store_true", help="погасити власні осиротілі групи, вийти")
    parser.add_argument("--dry-run", action="store_true", help="надрукувати віддалені команди, нічого не запускати")
    raw = list(argv[1:])
    if "--" in raw:
        split = raw.index("--")
        options, command = raw[:split], raw[split + 1:]
    else:
        options, command = raw, []
    args = parser.parse_args(options)
    args.command = command
    return args


def main(argv: list[str]) -> int:
    try:
        args = parse_args(argv)
    except SystemExit as error:
        return 2 if error.code else 0
    if args.status and args.kill_orphans:
        print("FAIL  --status і --kill-orphans взаємовиключні", file=sys.stderr)
        return 2
    if not (args.status or args.kill_orphans) and not args.command:
        print("FAIL  немає команди: додай її після --", file=sys.stderr)
        return 2
    if args.timeout <= 0:
        print("FAIL  --timeout мусить бути додатним", file=sys.stderr)
        return 2
    if args.status:
        if args.dry_run:
            print(remote_status(args.dir, args.slot_root))
            return 0
        try:
            preflight(args.host)
            result = ssh(args.host, remote_status(args.dir, args.slot_root))
        except RemoteRunError as error:
            print(f"FAIL  {error}", file=sys.stderr)
            return 3
        if result.returncode != 0:
            print(result.stdout, end="", file=sys.stderr)
            print(result.stderr, end="", file=sys.stderr)
            return 5
        print(result.stdout, end="")
        return 0
    if args.kill_orphans:
        if args.dry_run:
            print(remote_kill_orphans())
            return 0
        try:
            preflight(args.host)
            result = ssh(args.host, remote_kill_orphans())
        except RemoteRunError as error:
            print(f"FAIL  {error}", file=sys.stderr)
            return 3
        if result.returncode != 0:
            print(result.stdout, end="", file=sys.stderr)
            print(result.stderr, end="", file=sys.stderr)
            return 5
        print(result.stdout, end="")
        return 0

    tag = args.tag or default_tag()
    if not TAG_RE.fullmatch(tag):
        print(
            f"FAIL  --tag мусить мати вигляд '{TAG_PREFIX}<літери/цифри/._->': "
            "ім'я потрапляє в shell, pid-файл і маркер /proc, тож пробіли, лапки, ';' "
            "і '$' заборонені",
            file=sys.stderr,
        )
        return 2
    job = remote_job(tag, args.dir, args.timeout, args.command)
    stop_cmd = remote_stop(tag)
    if args.dry_run:
        print(f"REMOTE_HOST={args.host}")
        print(f"REMOTE_TAG={tag}")
        print(f"REMOTE_JOB={job}")
        print(f"REMOTE_STOP={stop_cmd}")
        return 0

    try:
        preflight(args.host)
    except RemoteRunError as error:
        print(f"FAIL  {error}", file=sys.stderr)
        return 3

    log_dir = Path(".remote-runs")
    log_dir.mkdir(exist_ok=True)
    log_path = log_dir / f"{tag}.log"
    caught = {"signal": None}

    def handle(signum, _frame):
        caught["signal"] = signum
        raise KeyboardInterrupt()

    old_int = signal.signal(signal.SIGINT, handle)
    old_term = signal.signal(signal.SIGTERM, handle)
    try:
        try:
            code = stream(args.host, job, log_path)
        except KeyboardInterrupt:
            print("remote-run: локальний сигнал — гашу віддалену групу", file=sys.stderr)
            return 130
    finally:
        stop(args.host, tag)
        signal.signal(signal.SIGINT, old_int)
        signal.signal(signal.SIGTERM, old_term)

    print(f"remote-run: лог {log_path}")
    if code == TIMEOUT_EXIT:
        print(f"FAIL  команда перевищила --timeout {args.timeout}s (remote timeout 124)", file=sys.stderr)
        return 4
    if code != 0:
        print(f"FAIL  віддалена команда вийшла з кодом {code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
