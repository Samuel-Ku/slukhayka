#!/usr/bin/env python3
"""Fail-closed normalizer and idempotent publisher for crash-diagnostic groups.

The collection adapter calls this only with a synthetic, already isolated
payload.  This module deliberately has no Crashlytics client and never writes
input payloads or logs them.  Its output is the small public issue projection.
"""

from __future__ import annotations

import dataclasses
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Protocol


class SanitizationError(ValueError):
    """The upstream shape is unknown or can carry non-public information."""


CONTEXT_VALUES = {
    "app_visibility": {"unknown", "foreground", "background"},
    "playback_state": {"idle", "buffering", "playing", "paused"},
    "playback_service": {"stopped", "started"},
    "audio_origin": {"none", "local", "remote"},
    "cast_active": {True, False},
}
EVENT_FIELDS = {
    "fatal": {"event_type", "app_version", "affected_install_count", "event_count", "exception", "context"},
    "anr": {"event_type", "app_version", "affected_install_count", "event_count", "exception", "context"},
    "unexpected_playback_exit": {"event_type", "app_version", "affected_install_count", "event_count", "exit", "context"},
}
FRAME = re.compile(r"^com\.slukhayka\.audiobooks\.[A-Za-z0-9_$.]+\([A-Za-z0-9_]+\.kt:[0-9]+\)$")
EXCEPTION_TYPE = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$")
VERSION = re.compile(r"^[0-9]+(?:\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?$")
EXIT_REASONS = {"SIGNALED", "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "DEPENDENCY_DIED"}
IMPORTANCE = {"FOREGROUND", "FOREGROUND_SERVICE", "VISIBLE", "PERCEPTIBLE", "SERVICE", "CACHED", "GONE", "UNKNOWN"}


@dataclasses.dataclass(frozen=True)
class SanitizedGroup:
    fingerprint: str
    event_type: str
    app_version: str
    affected_install_count: int
    event_count: int
    details: dict[str, Any]
    context: dict[str, Any]
    is_new_or_regressed: bool = False

    @property
    def marker(self) -> str:
        return f"<!-- crash-group:{self.fingerprint} -->"

    @property
    def issue_body(self) -> str:
        return self.render_issue_body()

    def render_issue_body(self, diagnosis_status: str = "needs-triage") -> str:
        lines = [
            self.marker,
            "## Безпечний діагностичний знімок",
            "",
            f"- Тип: `{self.event_type}`",
            f"- Версія застосунку: `{self.app_version}`",
            f"- Affected installs: `{self.affected_install_count}`",
            f"- Подій: `{self.event_count}`",
        ]
        if self.event_type in {"fatal", "anr"}:
            lines += ["- Виняток: `" + self.details["type"] + "`"]
            frames = self.details["frames"]
            if frames:
                lines += ["", "### App stack excerpt", "```", *frames, "```"]
        else:
            lines += [
                f"- Причина: `{self.details['reason']}`",
                f"- Status: `{self.details['status']}`",
                f"- Importance: `{self.details['importance']}`",
                f"- RSS/PSS KiB: `{self.details['rss_kb']}/{self.details['pss_kb']}`",
                f"- Android API: `{self.details['android_api']}`",
            ]
        status = (
            "Доведений red-loop передано окремому агенту для мінімального виправлення."
            if diagnosis_status == "ready-for-agent"
            else "Потрібен triage: група очищена, але причина ще не доведена."
        )
        lines += ["", "## Статус діагностики", status]
        return "\n".join(lines)


@dataclasses.dataclass
class PublishedIssue:
    number: int
    title: str
    body: str
    labels: list[str]


class IssuePublisher(Protocol):
    def find_by_marker(self, marker: str) -> PublishedIssue | None: ...
    def create(self, title: str, body: str, labels: list[str]) -> PublishedIssue: ...
    def update(self, number: int, body: str, labels: list[str]) -> PublishedIssue: ...


@dataclasses.dataclass(frozen=True)
class PublishResult:
    action: str
    issue_number: int


class FakeIssuePublisher:
    """Deterministic fake used by golden synthetic tests; it stores no input."""
    def __init__(self) -> None:
        self.issues: list[PublishedIssue] = []

    def find_by_marker(self, marker: str) -> PublishedIssue | None:
        return next((issue for issue in self.issues if marker in issue.body), None)

    def create(self, title: str, body: str, labels: list[str]) -> PublishedIssue:
        issue = PublishedIssue(len(self.issues) + 1, title, body, labels)
        self.issues.append(issue)
        return issue

    def update(self, number: int, body: str, labels: list[str]) -> PublishedIssue:
        issue = self.issues[number - 1]
        issue.body, issue.labels = body, labels
        return issue


class GitHubCliIssuePublisher:
    """Narrow GitHub write adapter; receives only [SanitizedGroup.issue_body]."""
    def __init__(self, repo: str) -> None:
        self.repo = repo

    def _run(self, *args: str) -> str:
        result = subprocess.run(["gh", *args], check=False, capture_output=True, text=True)
        if result.returncode:
            # Never include tool output: it can contain third-party issue text.
            raise RuntimeError("GitHub Issue publisher failed")
        return result.stdout

    def find_by_marker(self, marker: str) -> PublishedIssue | None:
        output = self._run("issue", "list", "--repo", self.repo, "--state", "all", "--search", marker, "--json", "number,title,body,labels", "--limit", "10")
        matches = json.loads(output)
        for issue in matches:
            if marker in issue.get("body", ""):
                return PublishedIssue(issue["number"], issue["title"], issue["body"], [label["name"] for label in issue["labels"]])
        return None

    def create(self, title: str, body: str, labels: list[str]) -> PublishedIssue:
        output = self._run("issue", "create", "--repo", self.repo, "--title", title, "--body", body, "--label", ",".join(labels))
        number = int(output.rstrip("/\n").rsplit("/", 1)[-1])
        return PublishedIssue(number, title, body, labels)

    def update(self, number: int, body: str, labels: list[str]) -> PublishedIssue:
        self._run("issue", "edit", str(number), "--repo", self.repo, "--body", body, "--add-label", ",".join(labels))
        return PublishedIssue(number, "", body, labels)

def _exact_mapping(value: Any, expected: set[str], name: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise SanitizationError(f"{name} has an unknown or missing field")
    return value


def _integer(value: Any, name: str, maximum: int = 2**31 - 1) -> int:
    if type(value) is not int or value < 0 or value > maximum:
        raise SanitizationError(f"{name} is not a bounded integer")
    return value


def _context(value: Any) -> dict[str, Any]:
    context = _exact_mapping(value, set(CONTEXT_VALUES), "context")
    for key, allowed in CONTEXT_VALUES.items():
        if context[key] not in allowed:
            raise SanitizationError(f"context {key} is not an approved enum")
    return context


def normalize_group(payload: Any) -> SanitizedGroup:
    if not isinstance(payload, dict):
        raise SanitizationError("payload is not an object")
    event_type = payload.get("event_type")
    if event_type not in EVENT_FIELDS or set(payload) != EVENT_FIELDS[event_type]:
        raise SanitizationError("payload has an unknown event type or field")
    version = payload["app_version"]
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise SanitizationError("app_version is not bounded")
    shared = {
        "event_type": event_type,
        "app_version": version,
        "affected_install_count": _integer(payload["affected_install_count"], "affected_install_count"),
        "event_count": _integer(payload["event_count"], "event_count"),
        "context": _context(payload["context"]),
    }
    if event_type in {"fatal", "anr"}:
        exception = _exact_mapping(payload["exception"], {"type", "frames"}, "exception")
        if not isinstance(exception["type"], str) or not EXCEPTION_TYPE.fullmatch(exception["type"]):
            raise SanitizationError("exception type is not allowlisted")
        frames = exception["frames"]
        if not isinstance(frames, list) or len(frames) > 8 or any(not isinstance(frame, str) or not FRAME.fullmatch(frame) for frame in frames):
            raise SanitizationError("frame is not an app-owned frame")
        details: dict[str, Any] = {"type": exception["type"], "frames": frames}
    else:
        exit_info = _exact_mapping(payload["exit"], {"reason", "status", "importance", "rss_kb", "pss_kb", "android_api"}, "exit")
        if exit_info["reason"] not in EXIT_REASONS or exit_info["importance"] not in IMPORTANCE:
            raise SanitizationError("exit enum is not allowlisted")
        details = {
            "reason": exit_info["reason"], "status": _integer(exit_info["status"], "status"),
            "importance": exit_info["importance"], "rss_kb": _integer(exit_info["rss_kb"], "rss_kb"),
            "pss_kb": _integer(exit_info["pss_kb"], "pss_kb"), "android_api": _integer(exit_info["android_api"], "android_api", 99),
        }
    # Aggregates and app versions change when the same group repeats.  They
    # must update the existing Issue rather than producing a new marker.
    fingerprint_source = {"event_type": event_type, "details": details}
    fingerprint = hashlib.sha256(json.dumps(fingerprint_source, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:20]
    return SanitizedGroup(
        fingerprint, event_type, version, shared["affected_install_count"],
        shared["event_count"], details, shared["context"]
    )


def group_from_projection(value: Any) -> SanitizedGroup:
    """Re-check a sanitized collect artifact before another trust zone uses it."""
    fields = _exact_mapping(
        value,
        {"fingerprint", "event_type", "app_version", "affected_install_count", "event_count", "details", "context", "is_new_or_regressed"},
        "sanitized group",
    )
    event_type = fields["event_type"]
    if event_type not in EVENT_FIELDS:
        raise SanitizationError("sanitized group has an unknown event type")
    payload: dict[str, Any] = {
        "event_type": event_type,
        "app_version": fields["app_version"],
        "affected_install_count": fields["affected_install_count"],
        "event_count": fields["event_count"],
        "context": fields["context"],
    }
    payload["exception" if event_type in {"fatal", "anr"} else "exit"] = fields["details"]
    group = normalize_group(payload)
    if fields["fingerprint"] != group.fingerprint:
        raise SanitizationError("sanitized group fingerprint does not match its contents")
    if type(fields["is_new_or_regressed"]) is not bool:
        raise SanitizationError("sanitized group has an unbounded priority flag")
    return dataclasses.replace(group, is_new_or_regressed=fields["is_new_or_regressed"])


def publish_group(group: SanitizedGroup, publisher: IssuePublisher, diagnosis_status: str = "needs-triage") -> PublishResult:
    if diagnosis_status not in {"needs-triage", "ready-for-agent"}:
        raise SanitizationError("unknown diagnosis status")
    existing = publisher.find_by_marker(group.marker)
    labels = [diagnosis_status]
    if existing is None:
        created = publisher.create(f"[Crash] {group.event_type}: {group.fingerprint}", group.render_issue_body(diagnosis_status), labels)
        return PublishResult("created", created.number)
    publisher.update(existing.number, group.render_issue_body(diagnosis_status), labels)
    return PublishResult("updated", existing.number)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail-closed crash-group tracer")
    parser.add_argument("--input", type=Path, required=True, help="ephemeral synthetic event JSON")
    parser.add_argument("--repo", help="owner/repo; required with --publish")
    parser.add_argument("--publish", action="store_true", help="publish only the sanitized Issue projection")
    args = parser.parse_args(argv)
    try:
        payload = json.loads(args.input.read_text(encoding="utf-8"))
        group = normalize_group(payload)
        if args.publish:
            if not args.repo:
                raise SanitizationError("--repo is required with --publish")
            result = publish_group(group, GitHubCliIssuePublisher(args.repo))
            print(json.dumps({"status": result.action, "issue": result.issue_number, "fingerprint": group.fingerprint}))
        else:
            print(json.dumps({"status": "sanitized", "fingerprint": group.fingerprint, "event_type": group.event_type}))
        return 0
    except (OSError, json.JSONDecodeError, SanitizationError, RuntimeError):
        # Inputs and upstream tool responses are deliberately not echoed.
        print("crash tracer rejected the group", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
