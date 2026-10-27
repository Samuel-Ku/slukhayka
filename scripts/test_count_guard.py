#!/usr/bin/env python3
"""Змінений Kotlin-тест не може тихо втратити тести (#1493).

Чому це сторож, а не ритуал огляду. У сесії #1486 скриптовий едит тестового
файлу обірвав його на першій закривальній дужці: дванадцять чинних тестів
адаптера зникли разом із фікстурами, файл компілювався, клас лишався зеленим, і
CI цього не бачив — видалений тест просто не запускається. Спіймав оглядач
ручним порівнянням із базою. Твердження «тести не видаляли» в тілі PR — це
обіцянка в прозі; тут воно стає перевіркою: набір тестових функцій зміненого
файлу звіряється з базою, і КОЖНЕ зникнення мусить бути назване в історії.

Що читається. `git merge-base <base> <head>` — це і є та база, з якою гілку
порівнюють: `git diff` від верхівки base-гілки показав би тести, додані в main
після відгалуження, «видаленими» (і дав би червоне на PR, що взагалі не чіпав
тестів). Далі `git diff --name-status -z --find-renames <merge-base> <head>`:
`-z` тому, що інакше git цитує non-ASCII шляхи, і файл `…/ЛокальTest.kt` мовчки
випав би з перевірки. Для кожного зміненого, перейменованого чи видаленого
`app/src/test/java/**/*Test.kt` — множини імен функцій під `@Test`,
`@ParameterizedTest` і `@RepeatedTest` у merge-base і в head. Ім'я, яке було в
базі й зникло з head, — видалення. Воно проходить лише тоді, коли в тілі якогось
коміту діапазону `merge-base..head` є рядок `test-removal: <ім'я>` (голе ім'я або
крапковий суфікс, що ним закінчується).

Названі межі (кожна — відмова, а не зелений, у місці, де це можливо):

  * лише Kotlin-файли `app/src/test/java/**/*Test.kt`: тест, оголошений не так,
    не бачиться, і це межа знання, а не мовчазна згода;
  * git — джерело істини: незакомічені зміни робочого дерева невидимі (сторож
    задуманий на PR-диф; локально спершу комітьте);
  * будь-який нечитний об'єкт — база, файл у базі чи head, тіло коміту — це
    НЕВІДОМІСТЬ (код 2), а не «тестів не було»: fail-open тут означав би зелене
    свідоцтво про втрату, яку не прочитали (review #1495, знахідка 1);
  * `test-removal:` — слово автора, а не доказ, що покриття переїхало: рядок
    може бути неправдивим, тож око оглядача все одно читає диф; перейменування
    тесту мусить бути назване як видалення;
  * на push у main базового PR немає, тож джоба CI мовчить там свідомо; коди
    виходу 0 — видалень без заяви немає, 1 — є, 2 — перевірити не вдалося
    (немає git, немає бази/merge-base, нечитний об'єкт).
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


class Unknown(Exception):
    """The guard could not read the facts; a green answer here would be a lie."""


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
    try:
        return subprocess.run(
            ["git", "-C", str(repo), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise Unknown(f"cannot run git: {error}") from error


def revision(repo: Path, ref: str) -> str:
    result = git(repo, "rev-parse", "--verify", f"{ref}^{{commit}}")
    if result.returncode != 0:
        raise Unknown(f"cannot read {ref!r} — {result.stderr.strip()}")
    return result.stdout.strip()


def merge_base(repo: Path, base: str, head: str) -> str:
    result = git(repo, "merge-base", base, head)
    if result.returncode != 0 or not result.stdout.strip():
        raise Unknown(f"cannot find merge-base of {base!r} and {head!r} — {result.stderr.strip()}")
    return result.stdout.strip()


def show(repo: Path, revision_id: str, path: str) -> str:
    """The file's content at a revision; an unreadable object is an unknown."""
    result = git(repo, "show", f"{revision_id}:{path}")
    if result.returncode != 0:
        raise Unknown(
            f"cannot read {path!r} at {revision_id[:12]} — {result.stderr.strip()}"
        )
    return result.stdout


def changed_files(repo: Path, base: str, head: str) -> list[tuple[str, str, str]]:
    """`(status, old_path, new_path)` for every path the range touches."""
    result = git(repo, "diff", "--name-status", "-z", "--find-renames", base, head)
    if result.returncode != 0:
        raise Unknown(result.stderr.strip() or "git diff failed")
    # With -z the output is NUL-separated: `M\0path\0`, `R100\0old\0new\0`. A
    # quoted non-ASCII path would silently miss TEST_ROOT — the reason for -z.
    fields = result.stdout.split("\0")
    changes: list[tuple[str, str, str]] = []
    index = 0
    while index + 1 < len(fields):
        status = fields[index]
        if not status:
            index += 1
            continue
        if status[0] in "RC":
            if index + 2 >= len(fields):
                break
            old, new = fields[index + 1], fields[index + 2]
            changes.append((status, old, new))
            index += 3
        else:
            changes.append((status, fields[index + 1], fields[index + 1]))
            index += 2
    return changes


def guard(repo: Path, base: str, head: str) -> int:
    try:
        revision(repo, base)
        revision(repo, head)
        root = merge_base(repo, base, head)
        bodies = git(repo, "log", "--format=%B", f"{root}..{head}")
        if bodies.returncode != 0:
            raise Unknown(f"cannot read the commit bodies — {bodies.stderr.strip()}")
        declared = declared_removals(bodies.stdout)
        changes = changed_files(repo, root, head)
        touched = 0
        undeclared: list[tuple[str, str]] = []
        for status, old_path, new_path in changes:
            candidates = [path for path in (old_path, new_path) if path.startswith(TEST_ROOT) and path.endswith(TEST_SUFFIX)]
            if not candidates:
                continue
            touched += 1
            # An added file has no previous side; treating a missing base blob
            # as "no tests" is exactly the fail-open this guard refuses — but a
            # genuinely added path is not a read failure.
            before = [] if status.startswith("A") else test_names(show(repo, root, old_path))
            after: list[str] = []
            if not status.startswith("D"):
                # An added file has nothing before it; a rename/modify must be
                # readable on both sides, which show() enforces.
                after = test_names(show(repo, head, new_path)) if not status.startswith("A") else []
            still_there = set(after)
            for name in before:
                if name in still_there:
                    continue
                if any(covers(entry, name) for entry in declared):
                    continue
                undeclared.append((old_path, name))
    except Unknown as error:
        print(f"test count guard: {error}", file=sys.stderr)
        return 2

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
