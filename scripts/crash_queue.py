"""#417 priority queue; collection and GitHub publication stay separate."""
from __future__ import annotations

import argparse
import dataclasses
import json
from pathlib import Path

try:
    from .crash_tracer import SanitizationError, SanitizedGroup, group_from_projection
except ImportError:  # pragma: no cover - direct script execution
    from crash_tracer import SanitizationError, SanitizedGroup, group_from_projection


@dataclasses.dataclass(frozen=True)
class CrashQueueItem:
    key: str
    event_type: str
    affected_installs: int
    is_new_or_regressed: bool = False
    is_background_playback: bool = False


def prioritize(items: list[CrashQueueItem], cap: int = 3) -> tuple[list[CrashQueueItem], list[CrashQueueItem]]:
    """Background playback first, then impact, then regression; tie by key."""
    ordered = sorted(
        items,
        key=lambda item: (
            0 if item.is_background_playback else 1,
            -item.affected_installs,
            0 if item.is_new_or_regressed else 1,
            item.key,
        ),
    )
    return ordered[:cap], ordered[cap:]


def select_groups(value: object, cap: int = 3) -> tuple[list[SanitizedGroup], list[SanitizedGroup]]:
    """Re-check the collector artifact, then retain every non-selected group."""
    if not isinstance(value, dict) or set(value) != {"groups", "rejected"}:
        raise SanitizationError("queue input has an unknown field")
    if type(value["rejected"]) is not int or value["rejected"] < 0:
        raise SanitizationError("queue rejection aggregate is malformed")
    raw_groups = value["groups"]
    if not isinstance(raw_groups, list):
        raise SanitizationError("queue groups are malformed")
    groups = [group_from_projection(group) for group in raw_groups]
    if len({group.fingerprint for group in groups}) != len(groups):
        raise SanitizationError("collector emitted duplicate group fingerprints")
    by_key = {group.fingerprint: group for group in groups}
    selected, retained = prioritize([
        CrashQueueItem(
            key=group.fingerprint,
            event_type=group.event_type,
            affected_installs=group.affected_install_count,
            is_background_playback=(
                group.event_type == "unexpected_playback_exit"
                and group.context["app_visibility"] == "background"
                and group.context["playback_state"] == "playing"
            ),
            is_new_or_regressed=group.is_new_or_regressed,
        ) for group in groups
    ], cap=cap)
    return [by_key[item.key] for item in selected], [by_key[item.key] for item in retained]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prioritize sanitized crash groups")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        selected, retained = select_groups(json.loads(args.input.read_text(encoding="utf-8")))
        args.output.write_text(json.dumps({
            "diagnose": [dataclasses.asdict(group) for group in selected],
            "retained": [dataclasses.asdict(group) for group in retained],
        }, sort_keys=True), encoding="utf-8")
        print(json.dumps({"diagnose": len(selected), "retained": len(retained)}))
        return 0
    except (OSError, json.JSONDecodeError, SanitizationError):
        print("needs-triage: crash queue rejected the collector artifact")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
