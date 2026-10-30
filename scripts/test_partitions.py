#!/usr/bin/env python3
"""Classify JVM tests and select the narrowest safe verification scope.

`select` answers with the JVM partitions a diff touches and the non-JVM checks
it needs — `web/**` maps to the web check, not to the full Android matrix.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


TEST_ROOT = Path("app/src/test/java")
MAIN_ROOT = Path("app/src/main/java")
PARTITIONS = ("pure-jvm", "room-robolectric", "compose-roborazzi")
ROBOLECTRIC_MARKERS = (
    "RobolectricTestRunner",
    "AndroidJUnit4",
)
ROOM_NATIVE_MARKERS = (
    "androidx.room",
    "androidx.sqlite",
    "Room.inMemoryDatabaseBuilder",
    "FrameworkSQLiteOpenHelperFactory",
    "MigrationTestHelper",
    "AppDatabase.",
)
SHARED_PREFIXES = (
    ".github/",
    "app/schemas/",
    "buildSrc/",
    "gradle/",
    "scripts/",
)
SHARED_FILES = {
    "app/build.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts",
}
# A diff path that ships documentation and nothing a test can run (#1493).
# Without this, a docs-only commit fell through to "unmapped changed path" and
# dragged the whole six-partition matrix into a lane for no possible witness.
DOCUMENTATION_PREFIX = "docs/"
DOCUMENTATION_SUFFIXES = (".md", ".mdx")
WEB_CHECK_PREFIX = "web/"
WEB_CHECK = "web"
# The steps the web check runs, READ FROM THE SCRIPT THAT RUNS THEM (#1276).
#
# They used to be a literal here with a note that `scripts/test-changed.sh`
# "executes exactly these" — a record declaring a fact about another file that
# nothing checked. Adding or dropping a step in the wrapper would have left this
# tuple naming a check that no longer exists, and `select`'s `reason` would have
# costed it wrong. The executor is the source, so the executor is read: the
# command the wrapper runs for the web check — `(cd "$repo_root/web" && …) ||
# exit`. A tree whose wrapper has no such command is a refusal (2), not a guess —
# `web_check_steps` is called only when a web path is actually selected, so the
# copied trees the fixtures build never need it.
#
# What is NOT verified here: the wrapper's own note says its steps mirror the
# `web` job in `.github/workflows/ci.yml`. That mirror is a claim about a second
# executor, and nothing in this repository compares the two; the steps below are
# the wrapper's, and only the wrapper's.
#
# A named limit of the derivation: swapping the read back for a literal that
# happens to name the same steps changes no behaviour today, so nothing reddens.
# What does redden is the read BREAKING — a regex that never matches turns the
# selector into a refusal (2), and `test_test_partitions` fails on it. And what
# is read is the command's BODY, not the arm's own label: renaming `web)` to
# `web-check)` leaves this selector green while the wrapper itself would refuse
# the unknown check — `test_test_wrappers.py` catches that one, this does not.
WEB_CHECK_SCRIPT = Path("scripts/test-changed.sh")
WEB_CHECK_ARM = re.compile(
    r'\(cd\s+"\$repo_root/web"\s*&&\s*(?P<steps>.+?)\)\s*\|\|\s*exit', re.DOTALL
)


def web_check_steps(root: Path | None = None) -> tuple[str, ...]:
    """The web check's steps, taken from the wrapper that executes them."""
    script = (root or Path(__file__).resolve().parents[1]) / WEB_CHECK_SCRIPT
    try:
        text = script.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError(f"cannot read the web check's executor: {error}")
    match = WEB_CHECK_ARM.search(text)
    if match is None:
        raise ValueError(
            f"{WEB_CHECK_SCRIPT} no longer runs the web check in a shape this "
            "selector can read — the check's cost would be a guess"
        )
    return tuple(step.strip() for step in match.group("steps").split("&&"))


def web_check_description(root: Path | None = None) -> str:
    return f"{WEB_CHECK} check ({', '.join(web_check_steps(root))})"


@dataclass(frozen=True)
class TestClass:
    fqcn: str
    package: str
    partition: str
    room_native: bool
    sdk: int | None
    path: Path


CLASS_DECLARATION = re.compile(
    r"^(?:(?:internal|private|public|protected|abstract|open|sealed|data|enum|annotation|value)\s+)*"
    r"(?:class|object)\s+(\w+)",
    re.MULTILINE,
)


def declared_test_classes(text: str) -> list[str]:
    """Every top-level *Test class declared in one test file, in order.

    The partitioner runs CI, so it must see ALL classes the file declares:
    a second class co-located with the stem class is still a test that has to
    run. Helper classes (fakes, abstract bases) do not end with «Test» and are
    intentionally not test targets.
    """
    names = [
        match.group(1)
        for match in CLASS_DECLARATION.finditer(text)
        if match.group(1).endswith("Test")
    ]
    # Stable de-duplication: an object/class pair must not produce two entries.
    return list(dict.fromkeys(names))


def discover(root: Path) -> list[TestClass]:
    source_root = root / TEST_ROOT
    if not source_root.is_dir():
        raise ValueError(f"test source root is missing: {source_root}")

    result: list[TestClass] = []
    for path in sorted(source_root.rglob("*Test.kt")):
        text = path.read_text(encoding="utf-8")
        package_match = re.search(r"^package\s+([\w.]+)\s*$", text, re.MULTILINE)
        if package_match is None:
            raise ValueError(f"test file has no package declaration: {path.relative_to(root)}")
        package = package_match.group(1)
        relative = path.relative_to(source_root).as_posix()
        compose_match = (
            "/ui/snapshots/" in f"/{relative}"
            or "Roborazzi" in text
            or "captureRobo" in text
        )
        robolectric_match = any(marker in text for marker in ROBOLECTRIC_MARKERS)
        native_room_match = any(marker in text for marker in ROOM_NATIVE_MARKERS)
        matches = {
            "compose-roborazzi": compose_match,
            # Compose snapshots also use Robolectric. That is expected; a
            # snapshot that additionally opens native Room is the unsafe
            # overlap that must be rejected.
            "room-robolectric": robolectric_match
            and (not compose_match or native_room_match),
            "pure-jvm": not compose_match and not robolectric_match,
        }
        matched_partitions = [name for name, matched in matches.items() if matched]
        if len(matched_partitions) != 1:
            relative_path = path.relative_to(root)
            raise ValueError(
                f"test must match exactly one partition: {relative_path} "
                f"matched {matched_partitions or 'none'}"
            )
        partition = matched_partitions[0]
        room_native = partition == "room-robolectric" and native_room_match
        sdk_match = re.search(r"sdk\s*=\s*\[\s*(\d+)", text)
        sdk = int(sdk_match.group(1)) if sdk_match else None
        if room_native and sdk not in (None, 35, 36):
            raise ValueError(
                f"native Room test uses an unsupported SDK cohort: "
                f"{path.relative_to(root)} sdk={sdk}"
            )
        # A *Test.kt file may declare more than one test class (e.g.
        # CanonicalStateFacadesTest.kt declares GuardTest and BehaviorTest).
        # Naming only the file stem would silently drop every other class from
        # the partition list — and from CI, which runs exactly this list.
        class_names = declared_test_classes(text) or [path.stem]
        for class_name in class_names:
            result.append(
                TestClass(
                    f"{package}.{class_name}", package, partition, room_native, sdk, path
                )
            )
    return result


def validate(test_classes: list[TestClass]) -> dict[str, int]:
    seen: set[str] = set()
    for test_class in test_classes:
        if test_class.partition not in PARTITIONS:
            raise ValueError(f"unknown partition for {test_class.fqcn}: {test_class.partition}")
        if test_class.fqcn in seen:
            raise ValueError(f"duplicate test class: {test_class.fqcn}")
        seen.add(test_class.fqcn)

    counts = {partition: 0 for partition in PARTITIONS}
    for test_class in test_classes:
        counts[test_class.partition] += 1
    return {
        "compose-roborazzi": counts["compose-roborazzi"],
        "pure-jvm": counts["pure-jvm"],
        "room-native": sum(test_class.room_native for test_class in test_classes),
        "room-robolectric": counts["room-robolectric"],
        "total": len(test_classes),
    }


def is_documentation_only(path: str) -> bool:
    return path.startswith(DOCUMENTATION_PREFIX) or path.endswith(DOCUMENTATION_SUFFIXES)


def is_shared_or_unknown(path: str) -> bool:
    return path in SHARED_FILES or path.startswith(SHARED_PREFIXES)


def production_package(path: str) -> str | None:
    prefix = f"{MAIN_ROOT.as_posix()}/"
    if not path.startswith(prefix) or not path.endswith(".kt"):
        return None
    relative = path[len(prefix) :]
    parts = Path(relative).parts[:-1]
    return ".".join(parts) if parts else None


def changed_test_fqcns(test_classes: list[TestClass], path: str) -> list[str] | None:
    """All classes declared in the changed *Test.kt file, or None if unmapped."""
    prefix = f"{TEST_ROOT.as_posix()}/"
    if not path.startswith(prefix) or not path.endswith("Test.kt"):
        return None
    relative_suffix = path[len(prefix) :]
    return sorted(
        test_class.fqcn
        for test_class in test_classes
        if test_class.path.as_posix().endswith(f"/{relative_suffix}")
    )


def execution_partition(test_class: TestClass) -> str:
    if test_class.partition == "room-robolectric" and test_class.room_native:
        return f"room-native-sdk{test_class.sdk}" if test_class.sdk else "room-native-default"
    if test_class.partition == "room-robolectric":
        return "room-robolectric-only"
    return test_class.partition


def contains_tests(test_classes: list[TestClass], package: str) -> bool:
    return any(
        test_class.package == package or test_class.package.startswith(f"{package}.")
        for test_class in test_classes
    )


def ancestor_packages(package: str) -> list[str]:
    """The changed package and every package above it, deepest first."""
    result: list[str] = []
    candidate = package
    while candidate:
        result.append(candidate)
        candidate = candidate.rpartition(".")[0]
    return result


def select(test_classes: list[TestClass], changed_files: list[str]) -> dict[str, object]:
    if not changed_files:
        return {"fullSuite": True, "partitions": {}, "reason": "no changed files"}
    if any(is_shared_or_unknown(path) for path in changed_files):
        return {"fullSuite": True, "partitions": {}, "reason": "shared infrastructure changed"}
    if all(is_documentation_only(path) for path in changed_files):
        return {"fullSuite": False, "partitions": {}, "reason": "documentation only"}

    selected: dict[str, set[str]] = {}
    checks: set[str] = set()
    by_fqcn = {test_class.fqcn: test_class for test_class in test_classes}
    for path in changed_files:
        exact_fqcns = changed_test_fqcns(test_classes, path)
        if exact_fqcns is not None:
            if not exact_fqcns:
                return {"fullSuite": True, "partitions": {}, "reason": "unknown changed test"}
            for fqcn in exact_fqcns:
                test_class = by_fqcn[fqcn]
                selected.setdefault(execution_partition(test_class), set()).add(test_class.fqcn)
            continue

        if path.startswith(WEB_CHECK_PREFIX):
            # A web-only diff used to drag the whole Android matrix in through
            # the unknown-path fallback, which is the one answer a lane cannot
            # afford when nothing below web/ needs the JDK. The web tree has
            # its own check in CI; name that check instead.
            checks.add(WEB_CHECK)
            continue

        # In a mixed diff the documentation paths claim nothing; the code paths
        # still decide the scope (#1493). A docs-only diff was answered above.
        if is_documentation_only(path):
            continue

        package = production_package(path)
        if package is None:
            # A Roborazzi golden is the product of exactly one partition — the
            # one whose tests render it. Without this case the path counts as
            # unknown and every golden change drags the whole matrix in
            # locally, which is the one thing a lane cannot afford here.
            if path.startswith("app/src/test/snapshots/"):
                rendered_by = [
                    test_class.fqcn
                    for test_class in test_classes
                    if execution_partition(test_class) == "compose-roborazzi"
                ]
                if rendered_by:
                    selected.setdefault("compose-roborazzi", set()).update(rendered_by)
                    continue
            return {"fullSuite": True, "partitions": {}, "reason": "unmapped changed path"}

        # A change in package P can break a test declared in P or in any
        # package below it, and also one declared in a package above it: a
        # test in `ui` names classes from `ui.components` without importing
        # across the module. The walk used to stop at the deepest ancestor
        # with tests anywhere below it, so `ui/components/PlayerPane.kt`
        # reached its own 4 tests and never `ui`'s 100 — `WidePlayerPaneTest`
        # among them — and the lane had to name the missing classes by hand.
        # Every package-ancestor now contributes: the deepest one with tests
        # keeps its whole subtree, and each package above it adds the tests
        # declared directly in it. Only the direct tests count above that
        # subtree — taking the whole subtree of every ancestor would select
        # the entire suite.
        ancestors = ancestor_packages(package)
        nearest = next(
            (ancestor for ancestor in ancestors if contains_tests(test_classes, ancestor)),
            None,
        )
        if nearest is None:
            return {"fullSuite": True, "partitions": {}, "reason": "no tests for changed module"}
        matches = [
            test_class
            for test_class in test_classes
            if test_class.package == nearest or test_class.package.startswith(f"{nearest}.")
        ]
        for ancestor in ancestors[ancestors.index(nearest) + 1 :]:
            matches.extend(
                test_class for test_class in test_classes if test_class.package == ancestor
            )
        for test_class in matches:
            selected.setdefault(execution_partition(test_class), set()).add(test_class.fqcn)

    if checks and not selected:
        reason = f"changed paths map to the {web_check_description()}"
    elif checks:
        reason = f"matched changed logical modules and the {web_check_description()}"
    else:
        reason = "matched changed logical modules"
    result: dict[str, object] = {
        "fullSuite": False,
        "partitions": {
            partition: sorted(selected[partition])
            for partition in (
                "pure-jvm",
                "room-native-sdk35",
                "room-native-sdk36",
                "room-native-default",
                "room-robolectric-only",
                "compose-roborazzi",
            )
            if partition in selected
        },
        "reason": reason,
    }
    if checks:
        result["checks"] = sorted(checks)
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--root", type=Path, default=Path.cwd())
    subparsers = result.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    list_parser = subparsers.add_parser("list")
    list_parser.add_argument("--partition", choices=PARTITIONS, required=True)
    list_parser.add_argument(
        "--cohort",
        choices=("all", "native-sdk35", "native-sdk36", "native-default", "non-native"),
        default="all",
    )
    select_parser = subparsers.add_parser("select")
    select_parser.add_argument("--changed-file", action="append", default=[])
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        test_classes = discover(args.root.resolve())
        validate(test_classes)
        if args.command == "validate":
            print(json.dumps(validate(test_classes), sort_keys=True))
        elif args.command == "list":
            for test_class in test_classes:
                cohort_match = {
                    "all": True,
                    "native-sdk35": test_class.room_native and test_class.sdk == 35,
                    "native-sdk36": test_class.room_native and test_class.sdk == 36,
                    "native-default": test_class.room_native and test_class.sdk is None,
                    "non-native": not test_class.room_native,
                }[args.cohort]
                if test_class.partition == args.partition and cohort_match:
                    print(test_class.fqcn)
        else:
            print(json.dumps(select(test_classes, args.changed_file), sort_keys=True))
    except ValueError as error:
        print(f"test partition error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
