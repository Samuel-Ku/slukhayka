#!/usr/bin/env python3
"""Ґард зростання app/lint-baseline.xml (#1222).

`app/lint-baseline.xml` — придушення боргу, який lint знайшов один раз. Нове
порушення ПОЗА baseline валить `:app:lintDebug` (це вже так), але сам список
придушення ріс мовчки: 1083 записи MissingTranslation на трьох локалях, і ніщо
не помічало, що записів побільшало.

`check-growth <base-ref> [<head-ref>]` рахує записи в HEAD і в базовому ref —
ОКРЕМО MissingTranslation, ОКРЕМО кожне інше правило — і падає на прирості
(exit 1). Зменшення — норма: записи прибирають, коли порушення полагоджено.

Чому MissingTranslation окремо. Це 1083 з 1313 записів, і саме він росте від
кожної нової літери інтерфейсу (UI навмисно український, `values-pl` — лише
частковий override Material3). Решта правил росте від коду, і змішувати їх в
одному числі означало б ховати приріст перекладу за шумом решти.

Чому порівняння з базовим ref, а не з бюджетом у файлі. Бюджет («дозволено
1083») — це друге джерело правди, яке саме починає тихо рости. Базовий ref —
це те, що вже в main; приріст проти нього видно в дифі PR і не потребує
окремого числа, яке хтось мусить пам'ятати оновлювати.

Виняток для законного додавання — один, і він не в PR, а на рівні власника:
репозиторна змінна LINT_BASELINE_GROWTH_ALLOWANCE (той самий механізм, що
BASE_SYNC_MAX_AGE_HOURS у .github/workflows/ci.yml). Формат:
`Правило=+N[,Правило=+N]`, напр. `MissingTranslation=3`. Порожня змінна = нуль
допуску. Змінна — не частина PR, тож її не «забувають у коді»: її ставить і
знімає власник, а ґард щоразу друкує, скільком записам вона дала пройти.

Виклик (локально, з кореня репозиторію):
    python3 scripts/lint_baseline.py check-growth origin/main HEAD
    python3 scripts/lint_baseline.py check-growth origin/main --worktree
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
BASELINE_DEFAULT = "app/lint-baseline.xml"
MISSING_TRANSLATION = "MissingTranslation"

USAGE_EPILOG = """\
Приклади:
  python3 scripts/lint_baseline.py check-growth origin/main HEAD
  python3 scripts/lint_baseline.py check-growth origin/main --worktree
"""


class BaselineError(Exception):
    """Baseline не такий, яким його пише lint — писати в нього небезпечно."""


# --------------------------------------------------------------------------
# Модель запису
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Location:
    file: str
    line: int | None = None
    column: int | None = None


@dataclass(frozen=True)
class Entry:
    """Один `<issue>` baseline: рівно ті атрибути, які пише lint."""

    issue_id: str
    message: str
    error_lines: tuple[str, ...]  # errorLine1, errorLine2 — може бути порожньо
    locations: tuple[Location, ...]

    @property
    def file(self) -> str:
        return self.locations[0].file if self.locations else ""

    @property
    def line(self) -> int:
        if not self.locations or self.locations[0].line is None:
            return -1
        return self.locations[0].line

    @property
    def column(self) -> int:
        if not self.locations or self.locations[0].column is None:
            return -1
        return self.locations[0].column


def entry_from_xml(element: ET.Element) -> Entry:
    """`<issue>` зі звіту або baseline → Entry (зайві атрибути відкидаються).

    Звіт несе ще severity/category/priority/summary/explanation; baseline їх не
    зберігає, тож у моделі їх немає — і `add` фізично не може дописати у файл
    те, чого там не буває.
    """
    error_lines = tuple(
        element.get(f"errorLine{index}")
        for index in (1, 2)
        if element.get(f"errorLine{index}") is not None
    )
    return Entry(
        issue_id=element.get("id", ""),
        message=element.get("message", ""),
        error_lines=error_lines,
        locations=tuple(_flatten_locations(element)),
    )


def _flatten_locations(element: ET.Element):
    """Усі `<location>` запису, у порядку документа.

    Baseline тримає кілька location сіблінгами (так пише lint: `IconDuplicates`
    має два), а звіт для вторинних місць вкладає `<location>` один в один.
    Розгортаємо — щоб запис у baseline мав ту саму форму, що й решта файлу.
    """
    for child in element:
        if child.tag != "location":
            continue
        line = child.get("line")
        column = child.get("column")
        yield Location(
            file=child.get("file", ""),
            line=int(line) if line is not None else None,
            column=int(column) if column is not None else None,
        )
        yield from _flatten_locations(child)


# --------------------------------------------------------------------------
# Розбір файлу baseline
# --------------------------------------------------------------------------


@dataclass
class Baseline:
    """Файл як послідовність СИРИХ записів плюс проміжки між ними.

    Сирі тексти й проміжки зберігаються, щоб `add` вставляв лише новий запис, а
    решту файлу не переписував: 14 445 рядків не мають пройти через мій
    форматер навіть якщо він на один пробіл розійдеться з lint. Проміжки тут не
    зайвина: у файлі три місця, де між записами стоїть не один порожній рядок
    (2×4 і 1×31 нових рядків — сліди ручних правок), і «нормалізувати» їх
    означало б переписати чуже.
    """

    raw: str
    head: str  # усе до першого запису, включно з порожнім рядком
    blocks: list[str]  # сирий текст кожного запису, кожен кінчається "</issue>\n"
    spans: list[tuple[int, int]]  # межі блоків у raw
    gaps: list[str]  # текст МІЖ записами (len == len(blocks) - 1)
    tail: str  # "\\n</issues>\\n"
    entries: list[Entry]

    def render(self) -> str:
        out = [self.head]
        for index, block in enumerate(self.blocks):
            out.append(block)
            if index < len(self.gaps):
                out.append(self.gaps[index])
        out.append(self.tail)
        return "".join(out)

_BLOCK_RE = re.compile(r"^    <issue\b.*?^    </issue>\n", re.MULTILINE | re.DOTALL)


def parse_baseline_text(raw: str, source: str = BASELINE_DEFAULT) -> Baseline:
    """Розібрати файл і довести, що він збирається назад байт-у-байт.

    Якщо ні — краще відмовитись одразу: `add` пише у файл, який не розуміє
    цілком, а «майже розуміє» тут означає зіпсований baseline.
    """
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise BaselineError(f"{source}: не парситься як XML: {error}") from error
    children = [child for child in root if child.tag == "issue"]
    matches = list(_BLOCK_RE.finditer(raw))
    if len(children) != len(matches):
        raise BaselineError(
            f"{source}: XML бачить {len(children)} записів <issue>, "
            f"а текстовий розбір — {len(matches)}; файл не у формі lint, "
            "перегенеруй його (updateLintBaseline) або полагодь вручну"
        )
    if not matches:
        raise BaselineError(f"{source}: у файлі немає жодного запису <issue>")
    spans = [(match.start(), match.end()) for match in matches]
    blocks = [match.group(0) for match in matches]
    gaps = [raw[spans[i][1] : spans[i + 1][0]] for i in range(len(spans) - 1)]
    tail = raw[spans[-1][1] :]
    if "</issues>" not in tail:
        raise BaselineError(f"{source}: після останнього запису немає закритого </issues>")
    baseline = Baseline(
        raw=raw,
        head=raw[: spans[0][0]],
        blocks=blocks,
        spans=spans,
        gaps=gaps,
        tail=tail,
        entries=[entry_from_xml(child) for child in children],
    )
    if baseline.render() != raw:
        raise BaselineError(f"{source}: файл не збирається назад байт-у-байт")
    return baseline


def count_by_rule(entries: list[Entry]) -> Counter:
    return Counter(entry.issue_id for entry in entries)


# --------------------------------------------------------------------------
# check-growth
# --------------------------------------------------------------------------


def read_git_file(ref: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise BaselineError(
            f"git show {ref}:{path} не вдався — чи існує '{ref}' і файл у ньому? "
            f"({result.stderr.strip() or 'без stderr'})"
        )
    return result.stdout


def parse_allowance(spec: str, known_rules: set[str]) -> dict[str, int]:
    """LINT_BASELINE_GROWTH_ALLOWANCE → {правило: дозволений приріст}.

    Помилка в змінній — падіння, а не «нуль допуску»: мовчазний нуль виглядав
    би як звичайна червона збірка й автор шукав би причину в коді, а не в
    змінній. Невідоме правило — теж падіння (одруківка в id не має тихо
    дозволяти нічого).
    """
    allowance: dict[str, int] = {}
    for chunk in spec.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if "=" not in chunk:
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: '{chunk}' без '=' — формат Правило=+N"
            )
        rule, _, value = chunk.partition("=")
        rule = rule.strip()
        value = value.strip().lstrip("+")
        if not rule or not value.isdigit():
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: '{chunk}' не Правило=+N (N — ціле ≥ 0)"
            )
        if rule not in known_rules:
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: правила '{rule}' немає ні в base, "
                f"ні в HEAD — одруківка? Відомі: {', '.join(sorted(known_rules))}"
            )
        allowance[rule] = allowance.get(rule, 0) + int(value)
    return allowance


def summarize_counts(counts: Counter) -> tuple[int, int]:
    """(MissingTranslation, решта правил)."""
    missing = counts.get(MISSING_TRANSLATION, 0)
    return missing, sum(counts.values()) - missing


def growth_rows(
    base_counts: Counter, head_counts: Counter, allowance: dict[str, int]
) -> tuple[list[tuple], list[tuple], list[tuple]]:
    """(приріст без дозволу, приріст у межах дозволу, зменшення) — по правилах.

    Порівнюються кількості ПО КОЖНОМУ правилу, а не одне число на файл: інакше
    −5 UnusedResources і +5 MissingTranslation дали б «нуль» і сховали приріст
    перекладів — рівно те, що тікет просив бачити окремо.
    """
    over: list[tuple] = []
    allowed: list[tuple] = []
    shrunk: list[tuple] = []
    for rule in sorted(set(base_counts) | set(head_counts)):
        base_count = base_counts.get(rule, 0)
        head_count = head_counts.get(rule, 0)
        if head_count == base_count:
            continue
        row = (rule, base_count, head_count, head_count - base_count, allowance.get(rule, 0))
        if head_count > base_count + row[4]:
            over.append(row)
        elif head_count > base_count:
            allowed.append(row)
        else:
            shrunk.append(row)
    return over, allowed, shrunk


def cmd_check_growth(args: argparse.Namespace) -> int:
    baseline = args.baseline
    if args.worktree:
        path = Path(baseline)
        if not path.is_absolute():
            path = REPO_ROOT / path
        raw_head = path.read_text(encoding="utf-8")
        head_label = f"{baseline} (робоче дерево)"
    else:
        raw_head = read_git_file(args.head_ref, baseline)
        head_label = f"{baseline} @ {args.head_ref}"
    raw_base = read_git_file(args.base_ref, baseline)

    try:
        head_entries = parse_baseline_text(raw_head, head_label).entries
    except BaselineError as error:
        raise BaselineError(f"HEAD: {error}") from error
    try:
        base_entries = parse_baseline_text(raw_base, f"{baseline} @ {args.base_ref}").entries
    except BaselineError as error:
        raise BaselineError(f"{args.base_ref}: {error}") from error

    base_counts = count_by_rule(base_entries)
    head_counts = count_by_rule(head_entries)
    known_rules = set(base_counts) | set(head_counts)
    allowance = parse_allowance(os.environ.get("LINT_BASELINE_GROWTH_ALLOWANCE", ""), known_rules)

    base_missing, base_other = summarize_counts(base_counts)
    head_missing, head_other = summarize_counts(head_counts)

    print(f"{baseline}: {args.base_ref} → {head_label}")
    print(f"  {'MissingTranslation':<20} {base_missing:>5} → {head_missing:>5}  {_delta(head_missing - base_missing)}")
    print(f"  {'решта правил':<20} {base_other:>5} → {head_other:>5}  {_delta(head_other - base_other)}")
    print(f"  {'разом':<20} {sum(base_counts.values()):>5} → {sum(head_counts.values()):>5}  "
          f"{_delta(sum(head_counts.values()) - sum(base_counts.values()))}")

    over, allowed, shrunk = growth_rows(base_counts, head_counts, allowance)
    for rule, base_count, head_count, delta, _extra in shrunk:
        print(f"  · {rule}: {base_count} → {head_count} {_delta(delta)}")

    for rule, base_count, head_count, delta, extra in allowed:
        used = f"дозвіл {extra}, використано {delta}" if extra else "дозвіл не витрачено"
        print(f"  · {rule}: {base_count} → {head_count} (приріст +{delta}, {used})")

    if not over:
        if allowed:
            print("Ґард зростання lint-baseline: приріст лише в межах дозволу.")
            details = "; ".join(f"`{rule}` +{delta} (дозвіл {extra})" for rule, _, _, delta, extra in allowed)
            _summary(f"⚠️ `app/lint-baseline.xml`: приріст у межах дозволу проти `{args.base_ref}` — {details}")
        else:
            print("Ґард зростання lint-baseline: приросту немає.")
            _summary(
                f"✅ `app/lint-baseline.xml`: без приросту проти `{args.base_ref}` "
                f"({sum(base_counts.values())} → {sum(head_counts.values())} записів)"
            )
        return 0

    for rule, base_count, head_count, delta, extra in over:
        detail = f"приріст +{delta} ({base_count} → {head_count})"
        if extra:
            detail += f", дозвіл {extra} вичерпано"
        print(f"::error::{baseline}: {rule}: {detail}", file=sys.stderr)
    print(
        "\nЗапис у baseline — це придушення, а не полагодження. Якщо порушення "
        "справжнє (напр. новий рядок uk без перекладу), рішення про приріст ухвалює "
        "власник: він виставляє репозиторну змінну "
        "LINT_BASELINE_GROWTH_ALLOWANCE=<Правило>=+N і знімає її, коли ланка "
        "змержиться. Вставити запис із звіту lint без ручного копіювання метаданих: "
        "python3 scripts/lint_baseline.py add",
        file=sys.stderr,
    )
    _summary(
        f"❌ `app/lint-baseline.xml`: приріст проти `{args.base_ref}` — "
        + "; ".join(f"`{rule}` +{delta}" for rule, _, _, delta, _ in over)
    )
    return 1


def _delta(value: int) -> str:
    if value > 0:
        return f"+{value}"
    if value < 0:
        return f"{value} ↓"
    return "0"


def _summary(line: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass  # підсумок — зручність, не ґард: його невдача не має валити крок


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lint_baseline.py",
        description="Ґард зростання app/lint-baseline.xml: падає, коли записів побільшало (#1222).",
        epilog=USAGE_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--baseline", default=BASELINE_DEFAULT, help=f"шлях до baseline (типово {BASELINE_DEFAULT})")
    subparsers = parser.add_subparsers(dest="command", required=True)

    growth = subparsers.add_parser(
        "check-growth",
        help="падає, якщо записів у baseline побільшало проти базового ref",
    )
    growth.add_argument("base_ref", help="базовий ref, напр. origin/main")
    growth.add_argument("head_ref", nargs="?", default="HEAD", help="ref із новим baseline (типово HEAD)")
    growth.add_argument(
        "--worktree",
        action="store_true",
        help="брати head-бік із робочого дерева (для перевірки ще не закоміченого baseline)",
    )
    growth.set_defaults(func=cmd_check_growth)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except BaselineError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
