#!/usr/bin/env python3
"""Publish every validated queue group without access to Crashlytics or models."""
from __future__ import annotations

import argparse
import dataclasses
import json
from pathlib import Path
from typing import Any

try:
    from .crash_tracer import (
        FakeIssuePublisher,
        GitHubCliIssuePublisher,
        IssuePublisher,
        PublishResult,
        SanitizationError,
        group_from_projection,
        publish_group,
    )
except ImportError:  # pragma: no cover - direct script execution
    from crash_tracer import (
        FakeIssuePublisher,
        GitHubCliIssuePublisher,
        IssuePublisher,
        PublishResult,
        SanitizationError,
        group_from_projection,
        publish_group,
    )


def publish_queue(value: Any, publisher: IssuePublisher) -> list[PublishResult]:
    """Create/update all groups; the top-three split never hides an issue."""
    if not isinstance(value, dict) or set(value) != {"diagnose", "retained"}:
        raise SanitizationError("queue output has an unknown field")
    if not all(isinstance(value[name], list) for name in ("diagnose", "retained")):
        raise SanitizationError("queue output groups are malformed")
    groups = [group_from_projection(item) for name in ("diagnose", "retained") for item in value[name]]
    if len({group.fingerprint for group in groups}) != len(groups):
        raise SanitizationError("queue has duplicate group fingerprints")
    return [publish_group(group, publisher) for group in groups]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Publish sanitized crash queue groups")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--output", type=Path, required=True, help="public fingerprint-to-Issue handoff")
    args = parser.parse_args(argv)
    try:
        queue = json.loads(args.input.read_text(encoding="utf-8"))
        projections = [item for name in ("diagnose", "retained") for item in queue.get(name, [])] if isinstance(queue, dict) else []
        results = publish_queue(queue, GitHubCliIssuePublisher(args.repo))
        groups = [group_from_projection(item) for item in projections]
        args.output.write_text(json.dumps({"issues": [
            {"fingerprint": group.fingerprint, "event_type": group.event_type,
             "issue_number": result.issue_number, "action": result.action}
            for group, result in zip(groups, results, strict=True)
        ]}, sort_keys=True), encoding="utf-8")
        print(json.dumps({"created": sum(result.action == "created" for result in results), "updated": sum(result.action == "updated" for result in results)}))
        return 0
    except (OSError, RuntimeError, json.JSONDecodeError, SanitizationError):
        print("needs-triage: crash publication rejected the queue")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
