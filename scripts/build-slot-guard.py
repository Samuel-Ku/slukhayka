#!/usr/bin/env python3
"""Один важкий прогін під наглядом пам'яті.

Слот серіалізує збірки (flock), але не спиняє **єдиний** прогін на машині, де
обмаль пам'яті: #1272 показав, як три паралельні збірки довели MemAvailable до
1.1 GB і zram до 4 GB, і їх урятував зовнішній сторож, якого в репозиторії не
було. `docs/agents/local-environment.md` уже фіксує випадок, коли партиція
впала з `OutOfMemoryError` у класах, яких диф не торкався.

Сторож стоїть між слотом і командою:

    .worktrees/gt scripts/build-slot-guard.py -- ./gradlew :app:testPureJvm

Він відмовляється стартувати, коли вільної пам'яті менше за поріг, і зупиняє
**власне** дерево процесів, якщо під час прогону пам'ять провалюється нижче
критичної межі або swap переростає стелю. Чужі процеси не чіпаються: сигнал
іде лише в групу, створену для цього прогону.

Коди виходу:
    0..125  код самої команди (як є)
    2       помилка в аргументах
    3       відмова до старту: вільної пам'яті менше за --floor-mb
    4       зупинено сторожем: пам'ять або swap перетнули межу під час прогону
    128+N   команда загинула від сигналу N
"""

from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time

REFUSED = 3
STOPPED = 4


def read_memavailable_kb(path: str) -> int | None:
    """Вільна пам'ять у кілобайтах, або None, якщо її нічим виміряти."""
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        return None
    return None


def read_swap_used_kb(path: str) -> int | None:
    """Сумарний Used зі /proc/swaps (zram і файл разом), або None."""
    total = 0
    try:
        with open(path, encoding="utf-8") as handle:
            next(handle, None)
            for line in handle:
                parts = line.split()
                if len(parts) >= 4:
                    total += int(parts[3])
    except (OSError, ValueError, StopIteration):
        return None
    return total


def breach(arguments: argparse.Namespace) -> str | None:
    """Причина зупинки на цьому семплі, або None.

    Порівнюється з --critical-mb, а не з --floor-mb: стартовий поріг означає
    «не починати на голодній машині», критична межа — «зупинити те, що вже
    почалося». Між ними прогін має право жити.
    """
    free_kb = read_memavailable_kb(arguments.meminfo)
    if free_kb is not None and free_kb < arguments.critical_mb * 1024:
        return f"вільної пам'яті {free_kb / 1024:.0f} MB < критичних {arguments.critical_mb} MB"
    swap_kb = read_swap_used_kb(arguments.swaps)
    if swap_kb is not None and swap_kb > arguments.swap_ceiling_mb * 1024:
        return f"swap {swap_kb / 1024:.0f} MB > стелі {arguments.swap_ceiling_mb} MB"
    return None


def stop_group(proc: subprocess.Popen, grace_seconds: float) -> None:
    """Спершу ввічливо, потім остаточно — і лише власна група процесів."""
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=grace_seconds)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        pass


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Запустити команду під наглядом пам'яті.",
        epilog="Коди виходу: 2 — аргументи, 3 — відмова, 4 — зупинено сторожем.",
    )
    result.add_argument("--floor-mb", type=int, default=1000,
                        help="відмовитись стартувати, якщо вільно менше (типово 1000)")
    result.add_argument("--critical-mb", type=int, default=400,
                        help="зупинити прогін, якщо вільно менше (типово 400)")
    result.add_argument("--swap-ceiling-mb", type=int, default=4000,
                        help="зупинити прогін, якщо swap більший (типово 4000)")
    result.add_argument("--poll-seconds", type=float, default=1.0,
                        help="інтервал семплінгу (типово 1.0)")
    result.add_argument("--grace-seconds", type=float, default=10.0,
                        help="скільки чекати після SIGTERM перед SIGKILL (типово 10)")
    result.add_argument("--meminfo", default="/proc/meminfo",
                        help="звідки читати пам'ять (тести підставляють файл)")
    result.add_argument("--swaps", default="/proc/swaps",
                        help="звідки читати swap (тести підставляють файл)")
    result.add_argument("command", nargs=argparse.REMAINDER,
                        help="команда після --")
    return result


def main(argv: list[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    command = list(arguments.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        print("build-slot-guard: немає команди (передай її після --)", file=sys.stderr)
        return 2

    free_kb = read_memavailable_kb(arguments.meminfo)
    if free_kb is not None and free_kb < arguments.floor_mb * 1024:
        print(
            f"build-slot-guard: ВІДМОВА — вільної пам'яті {free_kb / 1024:.0f} MB, "
            f"поріг {arguments.floor_mb} MB.\n"
            f"  Звільни пам'ять (браузер, інші збірки, docker) і повтори; "
            f"інакше збірка залипить машину, як у #1272.",
            file=sys.stderr,
        )
        return REFUSED

    started = time.monotonic()
    proc = subprocess.Popen(command, start_new_session=True)
    try:
        while True:
            try:
                proc.wait(timeout=arguments.poll_seconds)
                break
            except subprocess.TimeoutExpired:
                pass
            reason = breach(arguments)
            if reason is not None:
                elapsed = time.monotonic() - started
                print(
                    f"build-slot-guard: ЗУПИНЯЮ через {elapsed:.0f} с — {reason}.\n"
                    f"  Це межа машини, а не помилка тесту: вільни пам'ять і повтори; "
                    f"докладніше — docs/agents/local-environment.md.",
                    file=sys.stderr,
                )
                stop_group(proc, arguments.grace_seconds)
                return STOPPED
    except KeyboardInterrupt:
        stop_group(proc, arguments.grace_seconds)
        raise

    code = proc.returncode
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    raise SystemExit(main())
