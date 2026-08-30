#!/usr/bin/env python3
"""Validate a bounded OpenCode diagnosis contract without executing a model."""
from __future__ import annotations

import dataclasses
from typing import Any


@dataclasses.dataclass(frozen=True)
class DiagnosisVerdict:
    status: str  # ready-for-agent | needs-triage
    reason: str


def validate_diagnosis(value: Any) -> DiagnosisVerdict:
    """Fail closed unless proof of a real red loop is present.

    This validator is deliberately model/provider agnostic: the workflow can
    submit an OpenCode response, but never grants the diagnosis process any
    ability to mutate a branch, issue or PR.
    """
    if not isinstance(value, dict):
        return DiagnosisVerdict("needs-triage", "malformed diagnosis")
    required = {"red_command", "red_exit_code", "reproduction", "hypotheses", "evidence", "regression_test", "cleanup_plan"}
    if set(value) != required:
        return DiagnosisVerdict("needs-triage", "unknown diagnosis field")
    command, reproduction, regression = value["red_command"], value["reproduction"], value["regression_test"]
    hypotheses, evidence, cleanup = value["hypotheses"], value["evidence"], value["cleanup_plan"]
    if not all(isinstance(item, str) and item.strip() for item in (command, reproduction, regression, cleanup)):
        return DiagnosisVerdict("needs-triage", "missing reproducible evidence")
    if value["red_exit_code"] == 0:
        return DiagnosisVerdict("needs-triage", "red command did not fail")
    if not isinstance(hypotheses, list) or not 3 <= len(hypotheses) <= 5 or any(not isinstance(item, str) or not item.strip() for item in hypotheses):
        return DiagnosisVerdict("needs-triage", "hypotheses are not ranked and falsifiable")
    if not isinstance(evidence, list) or not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
        return DiagnosisVerdict("needs-triage", "missing recorded evidence")
    return DiagnosisVerdict("ready-for-agent", "recorded red loop and regression contract")
