#!/usr/bin/env python3
"""Validate a bounded OpenCode diagnosis contract and verify its red loop."""
from __future__ import annotations

import argparse
import dataclasses
import json
import shlex
import subprocess
from pathlib import Path
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
    if type(value["red_exit_code"]) is not int or value["red_exit_code"] == 0:
        return DiagnosisVerdict("needs-triage", "red command did not fail")
    if not isinstance(hypotheses, list) or not 3 <= len(hypotheses) <= 5 or any(not isinstance(item, str) or not item.strip() for item in hypotheses):
        return DiagnosisVerdict("needs-triage", "hypotheses are not ranked and falsifiable")
    if not isinstance(evidence, list) or not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
        return DiagnosisVerdict("needs-triage", "missing recorded evidence")
    return DiagnosisVerdict("ready-for-agent", "recorded red loop and regression contract")


def _safe_red_command(command: str) -> list[str] | None:
    """Only allow deterministic test commands in the read-only diagnosis job."""
    try:
        args = shlex.split(command)
    except ValueError:
        return None
    if not args:
        return None
    gradle_test = args[0] == "./gradlew" and ":app:test" in command and "--tests" in args
    python_test = args[:3] == ["python3", "-m", "unittest"]
    return args if gradle_test or python_test else None


def verify_diagnosis_red_loop(
    value: Any,
    run: Any = subprocess.run,
) -> DiagnosisVerdict:
    """Re-run the recorded test without a shell before permitting an agent.

    The model's reported `red_exit_code` is evidence to inspect, not proof. A
    contract becomes ready only when this isolated job observes the failure
    itself and the named regression test is part of that command.
    """
    verdict = validate_diagnosis(value)
    if verdict.status != "ready-for-agent":
        return verdict
    assert isinstance(value, dict)  # narrowed by validate_diagnosis
    command, regression = value["red_command"], value["regression_test"]
    args = _safe_red_command(command)
    if args is None:
        return DiagnosisVerdict("needs-triage", "red command is outside the test allowlist")
    if regression not in command:
        return DiagnosisVerdict("needs-triage", "recorded command does not run the regression test")
    try:
        result = run(args, shell=False, check=False, capture_output=True, text=True, timeout=20 * 60)
    except (OSError, subprocess.TimeoutExpired):
        return DiagnosisVerdict("needs-triage", "recorded red command could not be verified")
    if result.returncode == 0:
        return DiagnosisVerdict("needs-triage", "recorded red command no longer fails")
    return DiagnosisVerdict("ready-for-agent", "recorded red loop was independently reproduced")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify a crash diagnosis red loop")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        verdict = verify_diagnosis_red_loop(json.loads(args.input.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError):
        verdict = DiagnosisVerdict("needs-triage", "malformed diagnosis input")
    args.output.write_text(json.dumps(dataclasses.asdict(verdict), sort_keys=True), encoding="utf-8")
    print(json.dumps({"status": verdict.status, "reason": verdict.reason}))
    return 0 if verdict.status == "ready-for-agent" else 2


if __name__ == "__main__":
    raise SystemExit(main())
