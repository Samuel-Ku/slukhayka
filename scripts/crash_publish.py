#!/usr/bin/env python3
"""Publish every validated queue group without access to Crashlytics or models."""
from __future__ import annotations

import argparse
import dataclasses
import json
from pathlib import Path
from typing import Any

from scripts.crash_diagnosis import validate_diagnosis

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


def _diagnosis_statuses(value: Any, selected_fingerprints: set[str]) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict) or set(value) != {"diagnoses"} or not isinstance(value["diagnoses"], list):
        raise SanitizationError("diagnosis artifact has an unknown field")
    statuses: dict[str, str] = {}
    for entry in value["diagnoses"]:
        if not isinstance(entry, dict) or not {"fingerprint", "status", "reason"} <= set(entry):
            raise SanitizationError("diagnosis entry is malformed")
        allowed = {"fingerprint", "status", "reason"}
        if entry["status"] == "ready-for-agent":
            allowed.add("contract")
            if "contract" not in entry or validate_diagnosis(entry["contract"]).status != "ready-for-agent":
                raise SanitizationError("ready diagnosis lacks a valid contract")
        if set(entry) != allowed or entry["status"] not in {"needs-triage", "ready-for-agent"}:
            raise SanitizationError("diagnosis entry has an unknown field")
        if not isinstance(entry["fingerprint"], str) or entry["fingerprint"] not in selected_fingerprints or entry["fingerprint"] in statuses:
            raise SanitizationError("diagnosis entry crossed a queue boundary")
        statuses[entry["fingerprint"]] = entry["status"]
    if set(statuses) != selected_fingerprints:
        raise SanitizationError("diagnosis artifact omitted a selected group")
    return statuses


def publish_queue(value: Any, publisher: IssuePublisher, diagnoses: Any = None) -> list[PublishResult]:
    """Create/update all groups; the top-three split never hides an issue."""
    if not isinstance(value, dict) or set(value) != {"diagnose", "retained"}:
        raise SanitizationError("queue output has an unknown field")
    if not all(isinstance(value[name], list) for name in ("diagnose", "retained")):
        raise SanitizationError("queue output groups are malformed")
    groups = [group_from_projection(item) for name in ("diagnose", "retained") for item in value[name]]
    if len({group.fingerprint for group in groups}) != len(groups):
        raise SanitizationError("queue has duplicate group fingerprints")
    statuses = _diagnosis_statuses(diagnoses, {group.fingerprint for group in groups[:len(value["diagnose"])]})
    return [publish_group(group, publisher, statuses.get(group.fingerprint, "needs-triage")) for group in groups]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Publish sanitized crash queue groups")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--output", type=Path, required=True, help="public fingerprint-to-Issue handoff")
    parser.add_argument("--diagnoses", type=Path, help="bounded diagnosis statuses for selected groups")
    args = parser.parse_args(argv)
    try:
        queue = json.loads(args.input.read_text(encoding="utf-8"))
        diagnoses = json.loads(args.diagnoses.read_text(encoding="utf-8")) if args.diagnoses else None
        projections = [item for name in ("diagnose", "retained") for item in queue.get(name, [])] if isinstance(queue, dict) else []
        results = publish_queue(queue, GitHubCliIssuePublisher(args.repo), diagnoses)
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
