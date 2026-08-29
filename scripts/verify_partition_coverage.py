#!/usr/bin/env python3
"""Require a deliberate coverage contribution from every public JVM partition."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


EXPECTED_METHODS = {"pureJvm", "roomRobolectric", "composeRoborazzi"}


def verify(report: Path) -> None:
    root = ET.parse(report).getroot()
    probe = next(
        (
            item
            for item in root.findall(".//class")
            if item.attrib.get("name", "").endswith("/PartitionCoverageProbe")
        ),
        None,
    )
    if probe is None:
        raise ValueError("PartitionCoverageProbe is absent from the merged Kover report")

    covered: set[str] = set()
    for method in probe.findall("method"):
        counter = method.find("counter[@type='INSTRUCTION']")
        if counter is not None and int(counter.attrib["covered"]) > 0:
            covered.add(method.attrib["name"])
    missing = EXPECTED_METHODS - covered
    if missing:
        raise ValueError(f"partition coverage contributions are missing: {sorted(missing)}")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_partition_coverage.py <kover-report.xml>", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"partition coverage error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
