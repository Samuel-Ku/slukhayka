#!/usr/bin/env python3
"""Хто тримає build-слот і хто чекає — однією командою (#1493).

Слот — це `flock` на `.worktrees/.build-slot` усередині `.worktrees/gt`. Доти
зайнятість з'ясовували ручним `flock -n` і `pgrep -af gradlew`; у сесії #1486 це
коштувало одинадцяти окремих спроб, і кожна відповідала лише «зайнято».

Команда відповідає двома фактами й однією межею:

  * **free|busy** — проба взяти замок неблокуючим ексклюзивним `flock` на мить;
    взяли й одразу відпустили — слот вільний;
  * **хто там** — процеси, чий командний рядок називає шлях слота (за
    `/proc/<pid>/cmdline`): pid, час від старту, `running` (має дітей — тобто
    вже виконується) чи `waiting` (дітей немає — стоїть у черзі `flock -w`);
  * **межа** — `flock` не віддає PID власника, тож «хто саме тримає» тут
    ВИВОДИТЬСЯ зі списку процесів, а не читається з замка; процес, чий командний
    рядок переписано (`exec …`), у список не потрапить. На не-Linux (немає
    `/proc`) відповідь — код 2, а не «вільно».

Коди виходу: 0 — стан надруковано; 2 — стан невідомий (немає git-кореня, немає
теки `.worktrees`, немає `/proc`). Зі смуги корінь резолвиться в саму смугу —
щоб читати спільний слот, передай `--root` кореня репозиторію.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

SLOT_RELATIVE = Path(".worktrees") / ".build-slot"
PROC = Path("/proc")


class Unknown(Exception):
    """The state cannot be read; a guess here would be the silent green."""


@dataclass(frozen=True)
class Process:
    pid: int
    role: str
    elapsed_seconds: int | None
    command: str


def repository_root(root: Path | None) -> Path:
    if root is not None:
        return root.resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise Unknown(f"not inside a git tree: {result.stderr.strip()}")
    return Path(result.stdout.strip())


def slot_state(slot: Path) -> str:
    if not slot.exists():
        raise Unknown(f"no build slot at {slot}")
    try:
        handle = slot.open("a+")
    except OSError as error:
        raise Unknown(f"cannot open the build slot: {error}") from error
    try:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return "busy"
        try:
            fcntl.flock(handle, fcntl.LOCK_UN)
        except OSError:
            pass
        return "free"
    finally:
        handle.close()


def process_entries(slot: Path) -> list[tuple[int, list[str], bool]]:
    """`(pid, cmdline, has_children)` for every process naming the slot."""
    if not PROC.is_dir():
        raise Unknown("no /proc on this system")
    found: list[tuple[int, list[str], bool]] = []
    for entry in PROC.iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        try:
            raw = (entry / "cmdline").read_bytes()
        except OSError:
            continue
        parts = [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part]
        if not parts or str(slot) not in " ".join(parts):
            continue
        children = children_of(pid)
        found.append((pid, parts, bool(children)))
    return found


def children_of(pid: int) -> list[int]:
    try:
        raw = (PROC / str(pid) / "task" / str(pid) / "children").read_text(encoding="utf-8")
    except OSError:
        return []
    return [int(part) for part in raw.split() if part.isdigit()]


def started_seconds_ago(pid: int) -> int | None:
    """Rounded seconds since the process started, from /proc; None if unknown."""
    try:
        stat = (PROC / str(pid) / "stat").read_text(encoding="utf-8")
        uptime = float((PROC / "uptime").read_text(encoding="utf-8").split()[0])
        ticks = os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError, IndexError):
        return None
    # `comm` may contain spaces and parentheses; the fields after the last `)`
    # are positional from field 3, and starttime is field 22.
    fields = stat.rsplit(")", 1)[-1].split()
    if len(fields) < 20:
        return None
    starttime = int(fields[19])
    return max(0, int(uptime - starttime / ticks))


def describe(slot: Path, raw: list[tuple[int, list[str], bool]]) -> list[Process]:
    described = []
    for pid, cmdline, has_children in raw:
        described.append(
            Process(
                pid=pid,
                role="running" if has_children else "waiting",
                elapsed_seconds=started_seconds_ago(pid),
                command=" ".join(cmdline),
            )
        )
    # Whoever is running comes first; ties by pid keep the output deterministic.
    return sorted(described, key=lambda item: (item.role != "running", item.pid))


def format_elapsed(seconds: int | None) -> str:
    if seconds is None:
        return "?"
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}h{minutes:02d}m"
    if minutes:
        return f"{minutes}m{seconds:02d}s"
    return f"{seconds}s"


def print_status(root: Path, slot: Path, state: str, processes: list[Process], as_json: bool) -> None:
    if as_json:
        print(
            json.dumps(
                {
                    "slot": str(slot),
                    "state": state,
                    "processes": [asdict(process) for process in processes],
                },
                ensure_ascii=False,
            )
        )
        return
    print(f"build slot: {slot} — {state}")
    if not processes:
        print("  no process names the slot")
    for process in processes:
        print(
            f"  {process.role:7s} pid={process.pid} {format_elapsed(process.elapsed_seconds):>6s}  "
            f"{process.command}"
        )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--root", type=Path, default=None, help="repository root; derived from git by default")
    result.add_argument("--json", action="store_true", help="print the machine-readable shape")
    return result


def main(argv: list[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    try:
        root = repository_root(arguments.root)
        slot = root / SLOT_RELATIVE
        state = slot_state(slot)
        processes = describe(slot, process_entries(slot))
    except Unknown as error:
        print(f"build slot status: {error}", file=sys.stderr)
        return 2
    print_status(root, slot, state, processes, arguments.json)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
