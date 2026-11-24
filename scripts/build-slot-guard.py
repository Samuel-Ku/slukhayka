#!/usr/bin/env python3
"""Один важкий прогін під наглядом пам'яті.

Слот серіалізує збірки (flock), але не спиняє **єдиний** прогін на машині, де
обмаль пам'яті: #1272 показав, як три паралельні збірки довели MemAvailable до
1.1 GB і zram до 4 GB, і їх урятував зовнішній сторож, якого в репозиторії не
було. `docs/agents/local-environment.md` уже фіксує випадок, коли партиція
впала з `OutOfMemoryError` у класах, яких диф не торкався.

Сторож стоїть між слотом і командою:

    .worktrees/gt scripts/build-slot-guard.py -- ./gradlew --no-daemon :app:testPureJvm

Він відмовляється стартувати, коли вільної пам'яті менше за --floor-mb, і
зупиняє **власну** групу процесів, коли пам'ять падає під --critical-mb або
swap виростає за прогін більше ніж на --swap-growth-mb. Чужі процеси не
чіпаються: сигнал іде лише в групу, створену для цього прогону.

Типові пороги виведені з чисел #1272. Пік інкрементального прогону — 3.61 GB,
холодного — 4.74 GB; стан, у якому машина залипала, мав 1.13 GB вільної
пам'яті й 3.91 GB zram. Тому стартовий поріг — 2000 MB: це межа «не залипити
машину», а не «не торкнутися swap» — на 2 GB вільної пам'яті прогін, якому
треба 3.6 GB, усе одно частково піде в swap, але не забере машину з собою.

Swap міряється **приростом** за прогін, а не абсолютом: zram не дренується
сам, тож абсолютна стеля рано чи пізно спинила б кожен наступний прогін
назавжди, а порада «звільни пам'ять» її не виправила б. Приріст означає саме
те, що болить: цей прогін почав витісняти пам'ять.

Якщо пам'ять нічим виміряти (немає /proc/meminfo), сторож не блокує роботу —
він попереджає й виконує команду: fail-open, а не fail-closed.

Коди виходу:
    0..125  код самої команди (як є)
    2       помилка в аргументах або команду не вдалося запустити
    3       відмова до старту: вільної пам'яті менше за --floor-mb
    4       зупинено сторожем: пам'ять або приріст swap перетнули межу
    128+N   сторож або команда загинули від сигналу N

Коди 3 і 4 не відрізнити від команди, яка сама вийшла з таким кодом: сторож
пропускає код команди як є. Хто читає код виходу машинно, мусить дивитися на
stderr — причина там.
"""

from __future__ import annotations

import argparse
import dataclasses
import os
import signal
import subprocess
import sys
import time

REFUSED = 3
STOPPED = 4
USAGE = 2
CLEANUP_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)


class Interrupted(Exception):
    """Сторож отримав сигнал і мусить спершу прибрати за собою."""

    def __init__(self, signum: int) -> None:
        super().__init__(f"signal {signum}")
        self.signum = signum


@dataclasses.dataclass(frozen=True)
class Thresholds:
    floor_mb: int
    critical_mb: int
    swap_growth_mb: int
    poll_seconds: float
    grace_seconds: float


@dataclasses.dataclass(frozen=True)
class MemorySample:
    free_mb: float | None
    swap_mb: float | None


def read_free_mb(path: str) -> float | None:
    """MemAvailable у мегабайтах, або None, якщо його нічим виміряти."""
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def read_swap_mb(path: str) -> float | None:
    """Сумарний Used зі /proc/swaps у мегабайтах, або None."""
    total = 0
    try:
        with open(path, encoding="utf-8") as handle:
            next(handle, None)
            for line in handle:
                parts = line.split()
                if len(parts) >= 4:
                    total += int(parts[3])
    except (OSError, ValueError):
        return None
    return total / 1024


def take_sample(meminfo_path: str, swaps_path: str) -> MemorySample:
    return MemorySample(read_free_mb(meminfo_path), read_swap_mb(swaps_path))


def breach_reason(
    current: MemorySample, thresholds: Thresholds, swap_baseline_mb: float | None
) -> str | None:
    """Причина зупинки на цьому семплі, або None.

    Пам'ять порівнюється з --critical-mb, а не з --floor-mb: стартовий поріг
    означає «не починати на голодній машині», критична межа — «зупинити те, що
    вже почалося». Між ними прогін має право жити.

    Числа друкуються з одним знаком після коми: інакше 799.6 MB округлилося б
    до «800 MB < критичних 800 MB» — повідомлення, яке саме собі суперечить.
    """
    if current.free_mb is not None and current.free_mb < thresholds.critical_mb:
        return (
            f"вільної пам'яті {current.free_mb:.1f} MB "
            f"< критичних {thresholds.critical_mb} MB"
        )
    if current.swap_mb is not None and swap_baseline_mb is not None:
        growth = current.swap_mb - swap_baseline_mb
        if growth > thresholds.swap_growth_mb:
            return (
                f"swap виріс на {growth:.1f} MB за цей прогін "
                f"(стеля {thresholds.swap_growth_mb} MB)"
            )
    return None


def group_alive(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def wait_group_gone(proc: subprocess.Popen, pgid: int, timeout: float) -> bool:
    """Чекає, доки група спорожніє, збираючи власного зомбі.

    `killpg(pgid, 0)` бачить зомбі як живу групу, а прямий нащадок стає зомбі
    одразу після смерті — доки його не збере батько. Без `proc.poll()` цикл
    ніколи не побачив би порожнечу, кожна зупинка коштувала б два `grace`, і
    SIGKILL летів би навіть туди, де все вже стало.
    """
    deadline = time.monotonic() + timeout
    while True:
        proc.poll()
        if not group_alive(pgid):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)


def stop_group(proc: subprocess.Popen, grace_seconds: float) -> None:
    """Спершу ввічливо, потім остаточно — і лише власна група процесів.

    Чекаємо на спорожнення **групи**, а не на вихід прямого нащадка: інакше
    член групи, який знехтував SIGTERM, пережив би сторожа — а разом із ним і
    збірка, яку сторож мав спинити.

    Другий сигнал під час прибирання не має його зривати: якби він знову
    підняв `Interrupted`, ескалація до SIGKILL не відбулася б, і прогін
    пережив би сторожа саме на цьому шляху. Тому на час прибирання сигнали
    ігноруються, а потім повертаються.
    """
    pgid = proc.pid
    previous = {signum: signal.signal(signum, signal.SIG_IGN) for signum in CLEANUP_SIGNALS}
    try:
        try:
            os.killpg(pgid, signal.SIGTERM)
        except ProcessLookupError:
            return
        if wait_group_gone(proc, pgid, grace_seconds):
            return
        try:
            os.killpg(pgid, signal.SIGKILL)
        except ProcessLookupError:
            return
        wait_group_gone(proc, pgid, grace_seconds)
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Запустити команду під наглядом пам'яті.",
        epilog="Коди виходу: 2 — аргументи або запуск, 3 — відмова, 4 — зупинено сторожем.",
    )
    result.add_argument("--floor-mb", type=int, default=2000,
                        help="відмовитись стартувати, якщо вільно менше (типово 2000)")
    result.add_argument("--critical-mb", type=int, default=800,
                        help="зупинити прогін, якщо вільно менше (типово 800)")
    result.add_argument("--swap-growth-mb", type=int, default=2000,
                        help="зупинити прогін, якщо swap виріс більше (типово 2000)")
    result.add_argument("--poll-seconds", type=float, default=1.0,
                        help="інтервал семплінгу, > 0 (типово 1.0)")
    result.add_argument("--grace-seconds", type=float, default=10.0,
                        help="скільки чекати після SIGTERM перед SIGKILL (типово 10)")
    result.add_argument("--meminfo", default="/proc/meminfo",
                        help="звідки читати пам'ять (тести підставляють файл)")
    result.add_argument("--swaps", default="/proc/swaps",
                        help="звідки читати swap (тести підставляють файл)")
    result.add_argument("command", nargs=argparse.REMAINDER,
                        help="команда після --")
    return result


def validate(thresholds: Thresholds) -> str | None:
    if thresholds.critical_mb >= thresholds.floor_mb:
        return (
            f"критична межа ({thresholds.critical_mb} MB) мусить бути нижчою за "
            f"стартовий поріг ({thresholds.floor_mb} MB), інакше кожен прогін "
            f"спинявся б на першому ж семплі"
        )
    if thresholds.poll_seconds <= 0:
        return f"інтервал семплінгу мусить бути додатним, а не {thresholds.poll_seconds}"
    if thresholds.swap_growth_mb < 0:
        return "стеля приросту swap не може бути від'ємною"
    return None


def main(argv: list[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    command = list(arguments.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        print("build-slot-guard: немає команди (передай її після --)", file=sys.stderr)
        return USAGE

    thresholds = Thresholds(
        floor_mb=arguments.floor_mb,
        critical_mb=arguments.critical_mb,
        swap_growth_mb=arguments.swap_growth_mb,
        poll_seconds=arguments.poll_seconds,
        grace_seconds=arguments.grace_seconds,
    )
    complaint = validate(thresholds)
    if complaint is not None:
        print(f"build-slot-guard: {complaint}", file=sys.stderr)
        return USAGE

    start = take_sample(arguments.meminfo, arguments.swaps)
    if start.free_mb is None:
        print(
            "build-slot-guard: пам'ять не читається — виконую команду без нагляду",
            file=sys.stderr,
        )
    elif start.free_mb < thresholds.floor_mb:
        print(
            f"build-slot-guard: ВІДМОВА — вільної пам'яті {start.free_mb:.1f} MB, "
            f"поріг {thresholds.floor_mb} MB.\n"
            f"  Звільни пам'ять (браузер, інші збірки, docker) і повтори; "
            f"інакше збірка залипить машину, як у #1272.",
            file=sys.stderr,
        )
        return REFUSED

    def on_signal(signum, _frame):
        raise Interrupted(signum)

    for signum in CLEANUP_SIGNALS:
        signal.signal(signum, on_signal)

    started = time.monotonic()
    try:
        proc = subprocess.Popen(command, start_new_session=True)
    except FileNotFoundError:
        print(f"build-slot-guard: команди не знайдено: {command[0]}", file=sys.stderr)
        return USAGE
    except OSError as error:
        print(f"build-slot-guard: команду не вдалося запустити: {error}", file=sys.stderr)
        return USAGE

    try:
        while True:
            try:
                proc.wait(timeout=thresholds.poll_seconds)
                break
            except subprocess.TimeoutExpired:
                pass
            reason = breach_reason(
                take_sample(arguments.meminfo, arguments.swaps), thresholds, start.swap_mb
            )
            if reason is not None:
                elapsed = time.monotonic() - started
                print(
                    f"build-slot-guard: ЗУПИНЯЮ через {elapsed:.0f} с — {reason}.\n"
                    f"  Це межа машини, а не помилка тесту: вільни пам'ять і повтори; "
                    f"докладніше — docs/agents/local-environment.md.",
                    file=sys.stderr,
                )
                stop_group(proc, thresholds.grace_seconds)
                return STOPPED
    except Interrupted as interrupted:
        print(
            f"build-slot-guard: сигнал {interrupted.signum} — зупиняю прогін і йду",
            file=sys.stderr,
        )
        stop_group(proc, thresholds.grace_seconds)
        return 128 + interrupted.signum

    code = proc.returncode
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    raise SystemExit(main())
