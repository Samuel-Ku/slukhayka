#!/usr/bin/env python3
"""Read-only Crashlytics v1alpha adapter that emits only sanitized groups.

The REST response stays in process: this module never logs, caches or writes
it.  The only serializable result is a `crash_tracer.SanitizedGroup` projection.
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:  # `python scripts/crash_collect.py` and `python -m unittest` both work.
    from .crash_tracer import SanitizationError, SanitizedGroup, normalize_group
except ImportError:  # pragma: no cover - direct script execution
    from crash_tracer import SanitizationError, SanitizedGroup, normalize_group


RESOURCE_PART = re.compile(r"^[A-Za-z0-9_.:-]+$")
APP_FRAME = re.compile(r"^com\.slukhayka\.audiobooks\.[A-Za-z0-9_$.]+$")
CONTEXT_KEYS = {"app_visibility", "playback_state", "playback_service", "audio_origin", "cast_active"}
EXIT_KEYS = {"exit_reason", "exit_status", "process_importance", "rss_kb", "pss_kb", "app_version_code", "android_api"}


class CollectError(ValueError):
    """The API response cannot safely cross the collect trust boundary."""


def _mapping(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CollectError(f"{name} is not an object")
    return value


def _positive_int(value: Any, name: str, maximum: int = 2**31 - 1) -> int:
    if isinstance(value, bool):
        raise CollectError(f"{name} is not an integer")
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise CollectError(f"{name} is not an integer") from error
    if result < 0 or result > maximum or str(result) != str(value):
        raise CollectError(f"{name} is outside the bounded range")
    return result


def _context(raw: Any, extra_keys: set[str] | None = None) -> dict[str, Any]:
    values = _mapping(raw, "custom keys")
    if set(values) != CONTEXT_KEYS | (extra_keys or set()):
        raise CollectError("custom keys are not the reporting allowlist")
    cast_active = values["cast_active"]
    if cast_active == "true":
        cast_active = True
    elif cast_active == "false":
        cast_active = False
    elif type(cast_active) is not bool:
        raise CollectError("cast_active is not bounded")
    return {
        "app_visibility": values["app_visibility"],
        "playback_state": values["playback_state"],
        "playback_service": values["playback_service"],
        "audio_origin": values["audio_origin"],
        "cast_active": cast_active,
    }


def _frames(raw: Any) -> list[str]:
    if not isinstance(raw, list) or len(raw) > 8:
        raise CollectError("frames have unknown shape")
    result: list[str] = []
    for frame in raw:
        item = _mapping(frame, "frame")
        if item.get("owner") != "DEVELOPER":
            continue
        symbol, filename, line = item.get("symbol"), item.get("file"), item.get("line")
        if not isinstance(symbol, str) or not APP_FRAME.fullmatch(symbol):
            raise CollectError("frame is not app-owned")
        if not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9_]+\.kt", filename):
            raise CollectError("frame filename is not bounded")
        result.append(f"{symbol}({filename}:{_positive_int(line, 'frame line')})")
    return result


def _exception(raw: Any) -> dict[str, Any]:
    values = _mapping(raw, "exception")
    exception_type = values.get("type")
    if not isinstance(exception_type, str):
        raise CollectError("exception type is absent")
    return {"type": exception_type, "frames": _frames(values.get("frames"))}


def _unexpected_exit(values: dict[str, Any]) -> dict[str, Any]:
    return {
        "reason": values["exit_reason"],
        "status": _positive_int(values["exit_status"], "exit status"),
        "importance": values["process_importance"],
        "rss_kb": _positive_int(values["rss_kb"], "rss"),
        "pss_kb": _positive_int(values["pss_kb"], "pss"),
        "android_api": _positive_int(values["android_api"], "android api", 99),
    }


def _is_new_or_regressed(issue: dict[str, Any]) -> bool:
    signals = issue.get("signals", [])
    if not isinstance(signals, list):
        raise CollectError("issue signals have unknown shape")
    try:
        names = {item["signal"] for item in signals if isinstance(item, dict)}
    except (KeyError, TypeError) as error:
        raise CollectError("issue signals have unknown shape") from error
    if len(names) != len(signals) or any(not isinstance(name, str) for name in names):
        raise CollectError("issue signals have unknown shape")
    return bool(names & {"SIGNAL_FRESH", "SIGNAL_REGRESSED"})


def event_to_group(event: Any) -> SanitizedGroup:
    """Project one Android FATAL/ANR event into the #412 synthetic contract.

    Unknown top-level fields are intentionally never copied.  A malformed
    required field fails closed, so no response reaches diagnosis/publishing.
    """
    source = _mapping(event, "event")
    if source.get("platform") != "ANDROID":
        raise CollectError("only Android events are supported")
    issue = _mapping(source.get("issue"), "issue")
    version = _mapping(source.get("version"), "version")
    exceptions = source.get("exceptions")
    if not isinstance(exceptions, list) or not exceptions:
        raise CollectError("event has no Android exception")
    event_type = {"FATAL": "fatal", "ANR": "anr"}.get(issue.get("errorType"))
    if event_type is not None:
        return dataclasses.replace(normalize_group({
            "event_type": event_type,
            "app_version": version.get("displayVersion"),
            "affected_install_count": 1,
            "event_count": 1,
            "exception": _exception(exceptions[-1]),
            "context": _context(source.get("customKeys")),
        }), is_new_or_regressed=_is_new_or_regressed(issue))
    exception = _exception(exceptions[-1])
    if issue.get("errorType") != "NON_FATAL" or exception["type"] != (
        "com.slukhayka.audiobooks.data.diagnostics.UnexpectedPlaybackExit"
    ):
        raise CollectError("event is not an approved diagnostic type")
    custom_keys = _mapping(source.get("customKeys"), "custom keys")
    return dataclasses.replace(normalize_group({
        "event_type": "unexpected_playback_exit",
        "app_version": version.get("displayVersion"),
        "affected_install_count": 1,
        "event_count": 1,
        "exit": _unexpected_exit(custom_keys),
        "context": _context(custom_keys, EXIT_KEYS),
    }), is_new_or_regressed=_is_new_or_regressed(issue))


class CrashlyticsV1AlphaReader:
    """Narrow HTTP GET adapter. It never exposes headers or response bytes."""
    def __init__(self, project: str, app: str, access_token: str) -> None:
        if not all(RESOURCE_PART.fullmatch(part or "") for part in (project, app)):
            raise CollectError("invalid Crashlytics resource identifier")
        if not access_token:
            raise CollectError("missing access token")
        self.parent = f"projects/{project}/apps/{app}"
        self.access_token = access_token

    def open_events(self) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode([("filter.issue.states", "OPEN"), ("pageSize", "100")])
        url = f"https://firebasecrashlytics.googleapis.com/v1alpha/{self.parent}/events?{query}"
        request = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.access_token}"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except (OSError, ValueError) as error:
            raise CollectError("Crashlytics read failed") from error
        values = _mapping(payload, "event list").get("events", [])
        if not isinstance(values, list) or any(not isinstance(value, dict) for value in values):
            raise CollectError("unknown event list schema")
        return values


def collect(reader: CrashlyticsV1AlphaReader) -> tuple[list[SanitizedGroup], int]:
    groups: dict[str, SanitizedGroup] = {}
    rejected = 0
    for event in reader.open_events():
        try:
            group = event_to_group(event)
        except (CollectError, SanitizationError):
            rejected += 1
            continue
        previous = groups.get(group.fingerprint)
        if previous is None:
            groups[group.fingerprint] = group
        else:
            groups[group.fingerprint] = SanitizedGroup(
                fingerprint=group.fingerprint, event_type=group.event_type, app_version=group.app_version,
                affected_install_count=previous.affected_install_count + 1,
                event_count=previous.event_count + 1, details=group.details, context=group.context,
                is_new_or_regressed=previous.is_new_or_regressed or group.is_new_or_regressed,
            )
    return list(groups.values()), rejected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read Crashlytics only into sanitized groups")
    parser.add_argument("--project", required=True)
    parser.add_argument("--app", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        groups, rejected = collect(CrashlyticsV1AlphaReader(args.project, args.app, os.environ.get("ACCESS_TOKEN", "")))
        args.output.write_text(json.dumps({"groups": [group.__dict__ for group in groups], "rejected": rejected}, sort_keys=True), encoding="utf-8")
        print(json.dumps({"groups": len(groups), "rejected": rejected}))
        return 0
    except (CollectError, OSError):
        print("needs-triage: Crashlytics collect boundary rejected the response", flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
