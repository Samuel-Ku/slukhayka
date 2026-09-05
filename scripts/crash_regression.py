"""#418 regression reopen policy over bounded version metadata only."""
from __future__ import annotations

import dataclasses


def version_key(version: str) -> tuple[int, ...] | None:
    try:
        core = version.split("-", 1)[0]
        parts = tuple(int(item) for item in core.split("."))
        return parts if 2 <= len(parts) <= 4 and all(item >= 0 for item in parts) else None
    except ValueError:
        return None


@dataclasses.dataclass(frozen=True)
class RegressionDecision:
    reopen: bool
    label: str | None
    reason: str


def decide(observed_version: str, fixed_version: str | None, fix_was_merged: bool) -> RegressionDecision:
    observed, fixed = version_key(observed_version), version_key(fixed_version or "")
    if not fix_was_merged or observed is None or fixed is None:
        return RegressionDecision(False, None, "no verified merged fix to compare")
    if observed > fixed:
        return RegressionDecision(True, "needs-triage", "group returned after the merged fix")
    return RegressionDecision(False, None, "same or older version only updates aggregates")
