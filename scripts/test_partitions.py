#!/usr/bin/env python3
"""Classify JVM tests and select the narrowest safe verification scope."""

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


@dataclass(frozen=True)
class TestClass:
    fqcn: str
    package: str
    partition: str
    room_native: bool
    sdk: int | None
    path: Path


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
        fqcn = f"{package}.{path.stem}"
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
        result.append(TestClass(fqcn, package, partition, room_native, sdk, path))
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


def is_shared_or_unknown(path: str) -> bool:
    return path in SHARED_FILES or path.startswith(SHARED_PREFIXES)


def production_package(path: str) -> str | None:
    prefix = f"{MAIN_ROOT.as_posix()}/"
    if not path.startswith(prefix) or not path.endswith(".kt"):
        return None
    relative = path[len(prefix) :]
    parts = Path(relative).parts[:-1]
    return ".".join(parts) if parts else None


def changed_test_fqcn(path: str) -> str | None:
    prefix = f"{TEST_ROOT.as_posix()}/"
    if not path.startswith(prefix) or not path.endswith("Test.kt"):
        return None
    relative = Path(path[len(prefix) :])
    return ".".join((*relative.parts[:-1], relative.stem))


def execution_partition(test_class: TestClass) -> str:
    if test_class.partition == "room-robolectric" and test_class.room_native:
        return f"room-native-sdk{test_class.sdk}" if test_class.sdk else "room-native-default"
    if test_class.partition == "room-robolectric":
        return "room-robolectric-only"
    return test_class.partition


def select(test_classes: list[TestClass], changed_files: list[str]) -> dict[str, object]:
    if not changed_files:
        return {"fullSuite": True, "partitions": {}, "reason": "no changed files"}
    if any(is_shared_or_unknown(path) for path in changed_files):
        return {"fullSuite": True, "partitions": {}, "reason": "shared infrastructure changed"}

    selected: dict[str, set[str]] = {}
    by_fqcn = {test_class.fqcn: test_class for test_class in test_classes}
    for path in changed_files:
        exact_fqcn = changed_test_fqcn(path)
        if exact_fqcn is not None:
            test_class = by_fqcn.get(exact_fqcn)
            if test_class is None:
                return {"fullSuite": True, "partitions": {}, "reason": "unknown changed test"}
            selected.setdefault(execution_partition(test_class), set()).add(test_class.fqcn)
            continue

        package = production_package(path)
        if package is None:
            return {"fullSuite": True, "partitions": {}, "reason": "unmapped changed path"}

        matches: list[TestClass] = []
        candidate = package
        while candidate:
            matches = [
                test_class
                for test_class in test_classes
                if test_class.package == candidate or test_class.package.startswith(f"{candidate}.")
            ]
            if matches:
                break
            candidate = candidate.rpartition(".")[0]
        if not matches:
            return {"fullSuite": True, "partitions": {}, "reason": "no tests for changed module"}
        for test_class in matches:
            selected.setdefault(execution_partition(test_class), set()).add(test_class.fqcn)

    return {
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
        "reason": "matched changed logical modules",
    }


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
