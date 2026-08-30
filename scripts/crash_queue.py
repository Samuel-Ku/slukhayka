"""Pure #417 priority queue; callers keep collection and publication separate."""
from __future__ import annotations

import dataclasses


@dataclasses.dataclass(frozen=True)
class CrashQueueItem:
    key: str
    event_type: str
    affected_installs: int
    is_new_or_regressed: bool = False


def prioritize(items: list[CrashQueueItem], cap: int = 3) -> tuple[list[CrashQueueItem], list[CrashQueueItem]]:
    """Background playback first, then impact, then regression; tie by key."""
    ordered = sorted(
        items,
        key=lambda item: (
            0 if item.event_type == "unexpected_playback_exit" else 1,
            -item.affected_installs,
            0 if item.is_new_or_regressed else 1,
            item.key,
        ),
    )
    return ordered[:cap], ordered[cap:]
