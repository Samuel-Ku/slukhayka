"""#416 guard before an isolated crash-fix agent may propose a branch/PR."""
from __future__ import annotations

import dataclasses
from typing import Any


@dataclasses.dataclass(frozen=True)
class ImplementationGate:
    allowed: bool
    branch: str | None
    reason: str


def gate(issue_number: int, diagnosis_status: str, rerun_exit_code: int | None, open_pr_exists: bool) -> ImplementationGate:
    if diagnosis_status != "ready-for-agent":
        return ImplementationGate(False, None, "diagnosis is not ready-for-agent")
    if rerun_exit_code is None or rerun_exit_code == 0:
        return ImplementationGate(False, None, "recorded red command no longer reproduces")
    if open_pr_exists:
        return ImplementationGate(False, None, "an implementation PR already exists")
    return ImplementationGate(True, f"codex/crash-{issue_number}", "minimal fix may be proposed")
