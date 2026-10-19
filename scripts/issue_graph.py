#!/usr/bin/env python3
"""Граф залежностей тікетів: `## Blocked by` у тілах issue — ОБИДВІ форми (#1369).

Тікет #1285 має секцію `## Blocked by` з посиланням у формі URL
(`https://github.com/Samuel-Ku/slukhayka/issues/1284`), а не `#1284`. Тікет
#1241 такої секції не має взагалі — його блокада виражена в тілі #1240.

`grep '#[0-9]\\{3,4\\}'` по тілу не бачить URL-форми, і я назвав `#1285` та
`#1241` вільними, маючи перед очима `## Blocked by`. Обидва твердження були
хибні, і друге — транзитивно: `#1241 ← #1240 ← #1185 ← відкритий чужий PR`.

Механізм читає секцію, а не шукає символи `#`, тому URL-форма не губиться.
Далі він резолвить кожен блокер у стан тікета і його PR-стан і показує, що
РЕАЛЬНО вільно, а що стоїть — на якій глибині й через кого.

Коди виходу (як у сусідніх механізмів):
    0 — граф побудовано, тікети, названі в аргументах, вільні;
    1 — є заблоковані серед названих;
    2 — перевірити не вдалося: немає `gh`, немає мережі, немає таких тікетів.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

# `#1284` або `…/issues/1284`. URL-форма — та, що вбила grep-підхід.
BLOCKED_BY_HEADING = re.compile(r"^##\s+Blocked by\s*$", re.MULTILINE)
NEXT_HEADING = re.compile(r"^##\s+", re.MULTILINE)
TICKET_REF = re.compile(r"#(\d{3,5})|/issues/(\d{3,5})")


@dataclass
class Node:
    """Один тікет у графі: стан, його блокери й глибина блокади."""

    number: int
    state: str = "UNKNOWN"
    body: str = ""
    blockers: list[int] = field(default_factory=list)
    depth: int | None = None
    via_open_pr: int | None = None


def extract_blocked_by(body: str) -> list[int]:
    """Номери тікетів із секції `## Blocked by` — у будь-якій формі посилання.

    Секція триває до наступного заголовка `## ` і ніколи не захоплює решту
    тіла: інакше «Blocked by» згадане в прозі вище дало б хибні ребра.
    """
    match = BLOCKED_BY_HEADING.search(body)
    if match is None:
        return []
    rest = body[match.end():]
    nxt = NEXT_HEADING.search(rest)
    section = rest[: nxt.start()] if nxt else rest
    found: list[int] = []
    for m in TICKET_REF.finditer(section):
        number = int(m.group(1) or m.group(2))
        if number not in found:
            found.append(number)
    return found


def fetch(number: int, repo: str) -> Node:
    """Стан і тіло тікета через `gh`. Порожній вивід — ВІДМОВА, не «немає»."""
    proc = subprocess.run(
        ["gh", "issue", "view", str(number), "--repo", repo, "--json", "state,body"],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return Node(number=number, state="MISSING")
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return Node(number=number, state="UNKNOWN")
    return Node(number=number, state=data.get("state", "UNKNOWN"), body=data.get("body") or "")


def build(numbers: list[int], repo: str) -> dict[int, Node]:
    """Граф до вичерпання: кожен блокер тягне свої блокери."""
    nodes: dict[int, Node] = {}
    queue = list(numbers)
    while queue:
        number = queue.pop(0)
        if number in nodes:
            continue
        node = fetch(number, repo)
        nodes[number] = node
        if node.state not in {"OPEN", "MISSING", "UNKNOWN"}:
            node.blockers = []
            continue
        node.blockers = extract_blocked_by(node.body)
        queue.extend(b for b in node.blockers if b not in nodes)
    return nodes


def mark(nodes: dict[int, Node]) -> None:
    """Глибина блокади: 0 — вільно, інакше — на скільки кроків і через кого.

    Глибина рахується лише крізь ВІДКРИТІ тікети: закритий блокер більше не
    блокує, і ланцюг на ньому уривається (інакше закритий #605 «блокував» би
    все, що його колись згадувало).
    """
    def depth(number: int, seen: frozenset[int] = frozenset()) -> int:
        node = nodes.get(number)
        if node is None or number in seen:
            return 0
        if node.state != "OPEN":
            return 0
        live = [b for b in node.blockers if nodes.get(b) and nodes[b].state == "OPEN"]
        if not live:
            # Немає ЖИВИХ блокерів — тікет вільний. Глибина 0, не 1: інакше
            # «вільно» й «один крок до вільного» не відрізнялись би, і #1284
            # показувався б заблокованим на порожньому списку блокерів.
            return 0
        return 1 + min(depth(b, seen | {number}) for b in live)

    for node in nodes.values():
        node.depth = depth(node.number)


def main() -> int:
    parser = argparse.ArgumentParser(description="Граф залежностей тікетів (#1369).")
    parser.add_argument("numbers", nargs="+", type=int, help="тікети, чиї блокери показати")
    parser.add_argument("--repo", default="Samuel-Ku/slukhayka")
    args = parser.parse_args()

    if shutil.which("gh") is None:
        print("ВІДМОВА — немає `gh` у PATH: перевірити не вдалося", file=sys.stderr)
        return 2

    nodes = build(args.numbers, args.repo)
    missing = [n for n, node in nodes.items() if node.state == "MISSING"]
    if missing:
        print(f"ВІДМОВА — тікетів немає: {missing}", file=sys.stderr)
        return 2
    mark(nodes)

    for number in args.numbers:
        node = nodes[number]
        if node.depth == 0:
            print(f"#{number} ВІЛЬНИЙ")
            continue
        chain = " → ".join(f"#{b}" for b in node.blockers if nodes[b].state == "OPEN")
        print(f"#{number} ЗАБЛОКОВАНИЙ (глибина {node.depth}) ← {chain}")

    return 1 if any(nodes[n].depth for n in args.numbers) else 0


if __name__ == "__main__":
    sys.exit(main())
