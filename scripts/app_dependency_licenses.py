#!/usr/bin/env python3
"""Read the licenses of the SHIPPED :app classpath from POM metadata.

#1355: the previous shell script looked only inside
`caches/modules-2/files-2.1`, where Gradle 9 keeps the resolved jars/aars and
almost no POMs. Every artifact then fell into "no POM", and the report printed
`GPL-family: none` about a graph that contains NewPipeExtractor under
GPL-3.0-or-later. An empty answer must not look like a verdict: an artifact
whose license could not be read is UNKNOWN, and `none` is printed only when
every coordinate was actually checked and none of them is copyleft.

Where the licenses live: the POM published by the repository that supplies the
artifact. The three repositories the app graph comes from are Google Maven,
Maven Central and JitPack. Gradle module metadata (`.module`) was measured for
this graph and carries no license field — the POM is the metadata that has it.
A license declared by a parent POM is accepted as inherited, because Maven's
model inherits `<licenses>` exactly like the rest of the project description.

Network is optional: `--offline` reads only cached POMs, and every coordinate
it cannot resolve is still named UNKNOWN rather than counted as zero.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import os
import re
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


COORD = re.compile(
    r"[|+\\\- ]*([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):"
    r"([A-Za-z0-9_.\-]+)(?: -> ([A-Za-z0-9_.\-]+))?"
)
COPYLEFT = re.compile(r"\b(A?GPL|LGPL|GPL)\b|General Public License|Affero", re.I)
MAX_PARENT_DEPTH = 5
DEFAULT_TIMEOUT = 20.0
DEFAULT_JOBS = min(8, os.cpu_count() or 1)


@dataclass(frozen=True)
class Repository:
    """One Maven repository: a label for the report and its base URL."""

    name: str
    base_url: str


REPOSITORIES = (
    Repository("Google Maven", "https://dl.google.com/dl/android/maven2"),
    Repository("Maven Central", "https://repo1.maven.org/maven2"),
    Repository("JitPack", "https://jitpack.io"),
)


@dataclass(frozen=True)
class Coordinate:
    group: str
    artifact: str
    version: str

    @property
    def text(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"

    def pom_url(self, repository: Repository) -> str:
        group_path = "/".join(
            urllib.parse.quote(part, safe="") for part in self.group.split(".")
        )
        artifact = urllib.parse.quote(self.artifact, safe="")
        version = urllib.parse.quote(self.version, safe="")
        return (
            f"{repository.base_url}/{group_path}/{artifact}/{version}/"
            f"{artifact}-{version}.pom"
        )


@dataclass(frozen=True)
class Fetch:
    """One HTTP answer: a status (None when the request never reached) and text."""

    status: int | None
    text: str = ""
    error: str = ""


@dataclass(frozen=True)
class Lookup:
    """What was established for one coordinate, and why when nothing was."""

    coordinate: Coordinate
    licenses: tuple[str, ...] = ()
    source: str = ""
    reason: str = ""

    @property
    def unknown(self) -> bool:
        return not self.licenses


@dataclass(frozen=True)
class Report:
    tree_name: str
    lookups: tuple[Lookup, ...]

    @property
    def unknown(self) -> tuple[Lookup, ...]:
        return tuple(lookup for lookup in self.lookups if lookup.unknown)

    @property
    def known(self) -> tuple[Lookup, ...]:
        return tuple(lookup for lookup in self.lookups if not lookup.unknown)


def http_fetch(url: str, timeout: float = DEFAULT_TIMEOUT) -> Fetch:
    """One GET. A 404 is an answer; a missing network is a named failure."""
    request = urllib.request.Request(
        url, headers={"User-Agent": "slukhayka-license-inventory/1"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return Fetch(response.status, response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        return Fetch(error.code, "")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return Fetch(None, "", str(error))


def resolved_coordinates(tree_text: str) -> tuple[Coordinate, ...]:
    """The resolved graph, exactly as the `:app:dependencies` tree spells it."""
    resolved: set[Coordinate] = set()
    for line in tree_text.splitlines():
        if "---" not in line and "+" not in line:
            continue
        match = COORD.search(line)
        if match is None:
            continue
        version = match.group(4) or match.group(3)
        resolved.add(Coordinate(match.group(1), match.group(2), version))
    return tuple(sorted(resolved, key=lambda coordinate: coordinate.text))


def repositories_for(group: str) -> tuple[Repository, ...]:
    """Try the repository that most likely publishes this group first.

    The order is only a preference: every coordinate is answered by the first
    repository that supplies a POM with a license, and a 404 from one repository
    never stops the next one.
    """
    by_name = {repository.name: repository for repository in REPOSITORIES}
    if group.startswith(("androidx.", "com.android.", "com.google.android.")):
        order = ("Google Maven", "Maven Central", "JitPack")
    elif group.startswith("com.github."):
        order = ("JitPack", "Maven Central", "Google Maven")
    else:
        order = ("Maven Central", "Google Maven", "JitPack")
    return tuple(by_name[name] for name in order)


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child(element: ET.Element, name: str) -> ET.Element | None:
    for candidate in element:
        if _local_name(candidate.tag) == name:
            return candidate
    return None


def _text(element: ET.Element | None) -> str:
    if element is None or element.text is None:
        return ""
    return " ".join(element.text.split())


def parse_pom(text: str) -> tuple[tuple[str, ...], Coordinate | None] | None:
    """Licenses and parent declared by one POM; None means "not a POM"."""
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return None

    names: list[str] = []
    licenses = _child(root, "licenses")
    if licenses is not None:
        for entry in licenses:
            if _local_name(entry.tag) != "license":
                continue
            name = _text(_child(entry, "name")) or _text(_child(entry, "url"))
            if name:
                names.append(name)

    parent_coordinate = None
    parent = _child(root, "parent")
    if parent is not None:
        group = _text(_child(parent, "groupId"))
        artifact = _text(_child(parent, "artifactId"))
        version = _text(_child(parent, "version"))
        if group and artifact and version:
            parent_coordinate = Coordinate(group, artifact, version)

    return tuple(dict.fromkeys(names)), parent_coordinate


def cached_poms(cache: Path, coordinate: Coordinate) -> tuple[Path, ...]:
    base = cache / coordinate.group / coordinate.artifact / coordinate.version
    if not base.is_dir():
        return ()
    exact = sorted(base.rglob(f"{coordinate.artifact}-{coordinate.version}.pom"))
    return tuple(exact) if exact else tuple(sorted(base.rglob("*.pom")))


class Resolver:
    """Resolve one coordinate at a time, remembering answers across threads."""

    def __init__(
        self,
        cache: Path,
        fetch=http_fetch,
        *,
        offline: bool = False,
    ) -> None:
        self.cache = cache
        self.fetch = fetch
        self.offline = offline
        self._memo: dict[Coordinate, Lookup] = {}
        self._lock = threading.Lock()

    def resolve(
        self,
        coordinate: Coordinate,
        seen: frozenset[Coordinate] = frozenset(),
        depth: int = 0,
    ) -> Lookup:
        with self._lock:
            remembered = self._memo.get(coordinate)
        if remembered is not None:
            return remembered
        result = self._resolve(coordinate, seen, depth)
        with self._lock:
            self._memo.setdefault(coordinate, result)
        return result

    def _resolve(
        self,
        coordinate: Coordinate,
        seen: frozenset[Coordinate],
        depth: int,
    ) -> Lookup:
        if depth > MAX_PARENT_DEPTH:
            return Lookup(
                coordinate,
                reason=f"parent chain is deeper than {MAX_PARENT_DEPTH}",
            )

        notes: list[str] = []
        for path in cached_poms(self.cache, coordinate):
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError as error:
                notes.append(f"cached POM unreadable: {error}")
                continue
            parsed = parse_pom(text)
            if parsed is None:
                notes.append("cached POM is not XML")
                continue
            names, parent = parsed
            if names:
                return Lookup(coordinate, names, "cache")
            if parent is not None:
                if parent in seen:
                    notes.append(f"parent cycle at {parent.text}")
                    continue
                inherited = self.resolve(parent, seen | {coordinate}, depth + 1)
                if inherited.licenses:
                    return Lookup(coordinate, inherited.licenses, inherited.source)
                notes.append(f"parent {parent.text}: {inherited.reason}")
            else:
                notes.append("cached POM has no <licenses>")

        if self.offline:
            reason = "; ".join(notes) or "offline: no cached POM"
            return Lookup(coordinate, reason=reason)

        for repository in repositories_for(coordinate.group):
            url = coordinate.pom_url(repository)
            answer = self.fetch(url)
            if answer.status == 404:
                notes.append(f"{repository.name}: POM not found")
                continue
            if answer.status is None:
                notes.append(f"{repository.name}: {answer.error or 'network error'}")
                continue
            if answer.status != 200:
                notes.append(f"{repository.name}: HTTP {answer.status}")
                continue
            parsed = parse_pom(answer.text)
            if parsed is None:
                notes.append(f"{repository.name}: POM is not XML")
                continue
            names, parent = parsed
            if names:
                return Lookup(coordinate, names, repository.name)
            if parent is not None:
                if parent in seen:
                    notes.append(f"{repository.name}: parent cycle at {parent.text}")
                    continue
                inherited = self.resolve(parent, seen | {coordinate}, depth + 1)
                if inherited.licenses:
                    return Lookup(coordinate, inherited.licenses, inherited.source)
                notes.append(
                    f"{repository.name}: no <licenses> in POM or parent "
                    f"{parent.text} ({inherited.reason})"
                )
            else:
                notes.append(f"{repository.name}: POM has no <licenses>")

        return Lookup(coordinate, reason="; ".join(notes))


def inventory(
    tree_text: str,
    cache: Path,
    fetch=http_fetch,
    *,
    offline: bool = False,
    jobs: int = DEFAULT_JOBS,
    tree_name: str = "app-dependencies.txt",
) -> Report:
    resolver = Resolver(cache, fetch, offline=offline)
    coordinates = resolved_coordinates(tree_text)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, jobs)) as pool:
        lookups = tuple(pool.map(resolver.resolve, coordinates))
    return Report(tree_name, lookups)


def render(report: Report) -> str:
    total = len(report.lookups)
    unknown = report.unknown
    known = report.known

    by_license: dict[str, set[str]] = defaultdict(set)
    copyleft: dict[str, set[str]] = defaultdict(set)
    for lookup in known:
        for name in lookup.licenses:
            by_license[name].add(lookup.coordinate.text)
            if COPYLEFT.search(name):
                copyleft[lookup.coordinate.text].add(name)

    lines = [
        f"розвʼязано артефактів у :app:{report.tree_name}: {total}",
        f"з них із відомою ліцензією: {len(known)}",
        f"ліцензію не визначено: {len(unknown)}",
    ]
    sources = Counter(lookup.source for lookup in known)
    if sources:
        rendered = ", ".join(
            f"{name} {count}" for name, count in sorted(sources.items())
        )
        lines.append(f"POM-джерела: {rendered}")
    lines.append("")

    for name in sorted(by_license, key=lambda key: (-len(by_license[key]), key)):
        flag = "  ← GPL-family" if COPYLEFT.search(name) else ""
        lines.append(f"{name}  ({len(by_license[name])}){flag}")

    lines.append("\n--- GPL-family in the SHIPPED graph ---")
    for coordinate in sorted(copyleft):
        names = ", ".join(sorted(copyleft[coordinate]))
        lines.append(f"  {coordinate}  {names}")
    if total == 0:
        lines.append("  UNKNOWN — граф порожній; це не «none»")
    elif unknown:
        lines.append(
            f"  UNKNOWN — ліцензію не визначено для {len(unknown)} з {total} "
            "артефактів; це не «none»"
        )
    elif not copyleft:
        lines.append("  none")

    if unknown:
        lines.append("\n--- ліцензія не визначена ---")
        for lookup in unknown:
            lines.append(f"  {lookup.coordinate.text}  — {lookup.reason}")

    return "\n".join(lines)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    result.add_argument(
        "--tree",
        type=Path,
        default=Path("/tmp/app-dependencies.txt"),
        help="output of `./gradlew :app:dependencies`",
    )
    result.add_argument(
        "--cache",
        type=Path,
        default=Path(
            os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
        )
        / "caches/modules-2/files-2.1",
        help="Gradle module cache to read first",
    )
    result.add_argument(
        "--offline",
        action="store_true",
        help="read cached POMs only; unresolved coordinates stay UNKNOWN",
    )
    result.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help="HTTP timeout in seconds per repository",
    )
    result.add_argument(
        "--jobs",
        type=int,
        default=DEFAULT_JOBS,
        help="parallel repository lookups",
    )
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        tree_text = args.tree.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"app-dependency-licenses: cannot read {args.tree}: {error}", file=sys.stderr)
        return 2

    report = inventory(
        tree_text,
        args.cache,
        lambda url: http_fetch(url, args.timeout),
        offline=args.offline,
        jobs=args.jobs,
        tree_name=args.tree.name,
    )
    print(render(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
