#!/usr/bin/env python3
"""Змінений Kotlin-тест не може тихо втратити тести (#1493).

Чому це сторож, а не ритуал огляду. У сесії #1486 скриптовий едит тестового
файлу обірвав його на першій закривальній дужці: дванадцять чинних тестів
адаптера зникли разом із фікстурами, файл компілювався, клас лишався зеленим, і
CI цього не бачив — видалений тест просто не запускається. Спіймав оглядач
ручним порівнянням із базою. Твердження «тести не видаляли» в тілі PR — це
обіцянка в прозі; тут воно стає перевіркою: набір тестових функцій зміненого
файлу звіряється з базою, і КОЖНЕ зникнення мусить бути назване в історії.

Що читається: `git diff --name-status <base> <head>`; для кожного зміненого,
перейменованого чи видаленого `app/src/test/java/**/*Test.kt` — множина імен
функцій під `@Test`, `@ParameterizedTest` і `@RepeatedTest` у base і в head.
Ім'я, яке було в base і зникло з head, — це видалення. Воно проходить лише
тоді, коли в тілі якогось коміту діапазону `base..head` є рядок
`test-removal: <ім'я>` (голе ім'я або крапковий суфікс, що ним закінчується).

Названі межі (кожна — відмова, а не зелений, у місці, де це можливо):

  * лише Kotlin-файли `app/src/test/java/**/*Test.kt`: тест, оголошений не так,
    не бачиться, і це межа знання, а не мовчазна згода;
  * git — джерело істини: незакомічені зміни робочого дерева невидимі (сторож
    задуманий на PR-диф; локально спершу комітьте);
  * `test-removal:` — слово автора, а не доказ, що покриття переїхало: рядок
    може бути неправдивим, тож око оглядача все одно читає диф; перейменування
    тесту мусить бути назване як видалення;
  * на push у main базового PR немає, тож джоба CI мовчить там свідомо; коди
    виходу 0 — видалень без заяви немає, 1 — є, 2 — перевірити не вдалося
    (немає git, немає бази, немає дерева).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

TEST_ROOT = "app/src/test/java/"
TEST_SUFFIX = "Test.kt"
ANNOTATION = re.compile(r"^\s*@(?:Test|ParameterizedTest|RepeatedTest)\b", re.MULTILINE)
FUNCTION = re.compile(r"\bfun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))")
DECLARATION = re.compile(r"test-removal:\s*(.+)$", re.IGNORECASE)
# The scan window is generous enough to cross `@Suppress`/`@Throws` lines between
# the annotation and `fun`, and still short of the next declaration.
SCAN_WINDOW = 400


def test_names(source: str) -> list[str]:
    """The names declared under a test annotation, in file order."""
    names: list[str] = []
    for match in ANNOTATION.finditer(source):
        found = FUNCTION.search(source, match.end(), match.end() + SCAN_WINDOW)
        if found is not None:
            name = (found.group(1) or found.group(2)).strip()
            if name:
                names.append(name)
    return names


def declared_removals(commit_bodies: str) -> list[str]:
    names: list[str] = []
    for line in commit_bodies.splitlines():
        match = DECLARATION.search(line)
        if match is not None:
            names.append(match.group(1).strip().strip("`"))
    return names


def covers(declared: str, name: str) -> bool:
    return declared == name or declared.endswith(f".{name}") or declared.split(".")[-1] == name


def git(repo: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *arguments],
        check=False,
        capture_output=True,
        text=True,
    )


def changed_files(repo: Path, base: str, head: str) -> list[tuple[str, str, str]]:
    """`(status, old_path, new_path)` for every path the range touches."""
    result = git(repo, "diff", "--name-status", "--find-renames", base, head)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git diff failed")
    changes: list[tuple[str, str, str]] = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 2:
            status, path = parts
            changes.append((status, path, path))
        elif len(parts) == 3:
            status, old, new = parts
            changes.append((status, old, new))
    return changes


def guard(repo: Path, base: str, head: str) -> int:
    for ref in (base, head):
        verified = git(repo, "rev-parse", "--verify", f"{ref}^{{commit}}")
        if verified.returncode != 0:
            print(
                f"test count guard: cannot read {ref!r} — {verified.stderr.strip()}",
                file=sys.stderr,
            )
            return 2
    try:
        changes = changed_files(repo, base, head)
    except RuntimeError as error:
        print(f"test count guard: {error}", file=sys.stderr)
        return 2

    bodies = git(repo, "log", "--format=%B", f"{base}..{head}")
    if bodies.returncode != 0:
        print(f"test count guard: cannot read the commit bodies — {bodies.stderr.strip()}", file=sys.stderr)
        return 2
    declared = declared_removals(bodies.stdout)

    undeclared: list[tuple[str, str]] = []
    touched = 0
    for status, old_path, new_path in changes:
        removed_path = old_path if status.startswith(("D", "R", "C")) else None
        changed = [path for path in (old_path, new_path) if path.startswith(TEST_ROOT) and path.endswith(TEST_SUFFIX)]
        if not changed:
            continue
        touched += 1
        before = test_names(git(repo, "show", f"{base}:{old_path}").stdout)
        after: list[str] = []
        if not status.startswith("D"):
            after = test_names(git(repo, "show", f"{head}:{new_path}").stdout)
        still_there = set(after)
        for name in before:
            if name in still_there:
                continue
            if any(covers(entry, name) for entry in declared):
                continue
            undeclared.append((removed_path or new_path, name))

    if undeclared:
        for path, name in undeclared:
            print(f"removed test: {path}: {name}")
        print(
            f'test count guard: {len(undeclared)} test(s) removed without a declared removal '
            f'(add a "test-removal: <name>" line to a commit body)',
            file=sys.stderr,
        )
        return 1
    print(f"test count guard: {touched} changed test file(s), no undeclared test removal.")
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--repo", default=".", help="repository root; defaults to the current directory")
    result.add_argument("--base", default="origin/main", help="base revision to compare against")
    result.add_argument("--head", default="HEAD", help="head revision; defaults to HEAD")
    return result


def main(argv: list[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    return guard(Path(arguments.repo), arguments.base, arguments.head)


if __name__ == "__main__":
    raise SystemExit(main())
