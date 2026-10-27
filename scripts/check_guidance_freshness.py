#!/usr/bin/env python3
"""Is the agent guidance in THIS copy the same as on `origin/main`?

Why this exists. AGENTS.md and the docs it points to are read from whichever
copy an agent works in. In a long-lived lane, or in the repository root left on
someone else's branch, that copy drifts: a session read a root AGENTS.md that
still said `GLOSSARY.md` and `CODING_STANDARDS.md`, while `origin/main` already
had `CONTEXT.md`, `docs/agents/local-environment.md` and the merge gate. The
session then merged a PR with `gh pr merge`, bypassing `scripts/merge-pr.sh`,
because its always-loaded guidance never mentioned it.

That failure is not fixed by writing the same warning in prose: the copy is
what the agent reads. This command makes the drift visible in one line, before
the guidance is trusted:

    scripts/check_guidance_freshness.py            # 0 = fresh, 1 = drift
    scripts/check_guidance_freshness.py --no-fetch # offline lane, may be stale
    git show origin/main:AGENTS.md                  # the version that matters

When the base is a remote-tracking ref (`origin/main`), the command fetches it
first: comparing against a stale `origin/main` is exactly the false PASS this
tool exists to prevent. An unreadable remote is an unknown state (exit 2), not
freshness; `--no-fetch` is the explicit offline opt-out, and then the answer is
only as fresh as the local ref.

It only READS. It never edits or restores guidance: a lane may legitimately
change AGENTS.md in its own PR, and that is drift against `origin/main` until
the PR merges — the command says so without pretending it is wrong.

Covered files: AGENTS.md, CONTRIBUTING.md, docs/agents/**, docs/CODEMAPS/**.

Exit codes: 0 — the copy matches the base; 1 — drift named file by file;
2 — cannot verify (not a git repo, missing base ref, git failure).
Env: GUIDANCE_GIT — path to git (default `git`; tests use it).
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

GUIDANCE_PATTERNS = ("AGENTS.md", "CONTRIBUTING.md", "docs/agents", "docs/CODEMAPS")


class CannotVerify(Exception):
    """The comparison could not be made; never reported as "fresh"."""


class Git:
    def __init__(self, binary: str):
        self.binary = binary

    def __call__(self, *arguments: str, cwd: Path, check: bool = True) -> subprocess.CompletedProcess:
        try:
            completed = subprocess.run(
                [self.binary, *arguments],
                cwd=str(cwd),
                capture_output=True,
                text=True,
                check=False,
            )
        except OSError as error:
            raise CannotVerify(f"не вдалося запустити {self.binary}: {error}") from error
        if check and completed.returncode != 0:
            detail = (completed.stderr or completed.stdout).strip().splitlines()
            raise CannotVerify(
                f"git {' '.join(arguments)}: {detail[-1] if detail else completed.returncode}"
            )
        return completed


def repository_root(git: Git, where: Path) -> Path:
    completed = git("rev-parse", "--show-toplevel", cwd=where, check=False)
    if completed.returncode != 0:
        raise CannotVerify(f"{where} не є git-репозиторієм")
    found = Path(completed.stdout.strip()).resolve()
    # A git hook exports GIT_DIR/GIT_WORK_TREE into everything it runs; an
    # inherited GIT_DIR makes `git -C <foreign temp dir>` answer about the
    # hook's repository. Refuse that: a root outside the requested copy is not
    # this copy's guidance, and silently comparing it would be a wrong PASS.
    try:
        inside = where == found or where.is_relative_to(found)
    except ValueError:
        inside = False
    if not inside:
        raise CannotVerify(
            f"git повернув корінь {found}, що не містить {where} — "
            "успадкований GIT_DIR/GIT_WORK_TREE?"
        )
    return found


def base_is_readable(git: Git, base: str, root: Path) -> None:
    completed = git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}", cwd=root, check=False)
    if completed.returncode != 0:
        raise CannotVerify(
            f"база {base!r} не читається — зроби `git fetch origin main` "
            "(або назви іншу базу через --base)"
        )


def base_files(git: Git, base: str, root: Path) -> dict[str, bytes]:
    listing = git("ls-tree", "-r", "--name-only", base, "--", *GUIDANCE_PATTERNS, cwd=root).stdout
    files: dict[str, bytes] = {}
    for path in sorted(line for line in listing.splitlines() if line):
        completed = subprocess.run(
            [git.binary, "show", f"{base}:{path}"],
            cwd=str(root), capture_output=True, check=False,
        )
        if completed.returncode != 0:
            raise CannotVerify(f"не вдалося прочитати {base}:{path}")
        files[path] = completed.stdout
    return files


def local_files(git: Git, root: Path) -> dict[str, bytes]:
    tracked = git("ls-files", "--", *GUIDANCE_PATTERNS, cwd=root).stdout.splitlines()
    untracked = git(
        "ls-files", "--others", "--exclude-standard", "--", *GUIDANCE_PATTERNS,
        cwd=root,
    ).stdout.splitlines()
    files: dict[str, bytes] = {}
    for path in sorted(set(tracked) | set(untracked)):
        candidate = root / path
        if candidate.is_file():
            files[path] = candidate.read_bytes()
    return files


def compare(git: Git, root: Path, base: str) -> list[str]:
    base_is_readable(git, base, root)
    expected = base_files(git, base, root)
    actual = local_files(git, root)
    drift: list[str] = []
    for path in sorted(set(expected) | set(actual)):
        if path not in actual:
            drift.append(f"missing in this copy: {path}")
        elif path not in expected:
            drift.append(f"only in this copy:    {path}")
        elif actual[path] != expected[path]:
            drift.append(f"differs:              {path}")
    return drift


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Порівнює guidance-файли робочої копії з базою (типово origin/main).",
        epilog="Коди виходу: 0 — збігається; 1 — є розходження; 2 — перевірити не вдалося.",
    )
    parser.add_argument("--base", default="origin/main", help="git-ревізія для порівняння")
    parser.add_argument("--root", default=".", help="тека робочої копії (типово поточна)")
    parser.add_argument("--quiet", action="store_true", help="друкувати лише підсумок")
    parser.add_argument(
        "--no-fetch",
        dest="fetch",
        action="store_false",
        help="не робити git fetch (офлайн); відповідь буде настільки свіжою, наскільки свіжий локальний ref",
    )
    arguments = parser.parse_args(argv[1:])

    git = Git(os.environ.get("GUIDANCE_GIT", "git"))
    try:
        root = repository_root(git, Path(arguments.root).resolve())
        if arguments.fetch and arguments.base.startswith("origin/"):
            branch = arguments.base.split("/", 1)[1]
            fetched = git("fetch", "--quiet", "origin", branch, cwd=root, check=False)
            if fetched.returncode != 0:
                detail = (fetched.stderr or fetched.stdout).strip().splitlines()
                raise CannotVerify(
                    "git fetch origin %s: %s — потрібна мережа або --no-fetch "
                    "(тоді відповідь лише настільки свіжа, наскільки локальний ref)"
                    % (branch, detail[-1] if detail else fetched.returncode)
                )
        drift = compare(git, root, arguments.base)
    except CannotVerify as error:
        print(f"FAIL  перевірити свіжість guidance не вдалося: {error}", file=sys.stderr)
        return 2

    if not drift:
        print(f"PASS  guidance збігається з {arguments.base} ({root})")
        return 0
    print(
        f"DRIFT  guidance у {root} відрізняється від {arguments.base}. "
        "Ця копія не є джерелом правди — читай базу: git show "
        f"{arguments.base}:AGENTS.md"
    )
    if not arguments.quiet:
        for line in drift:
            print(f"  {line}")
    print(f"  файлів із розходженням: {len(drift)}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
