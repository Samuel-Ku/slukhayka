#!/usr/bin/env python3
"""Validate a bounded OpenCode diagnosis contract and verify its red loop."""
from __future__ import annotations

import argparse
import dataclasses
import json
import re
import shlex
import subprocess
from pathlib import Path
from typing import Any

from scripts.crash_tracer import SanitizationError, group_from_projection


@dataclasses.dataclass(frozen=True)
class DiagnosisVerdict:
    status: str  # ready-for-agent | needs-triage
    reason: str


_MAX_TEXT = 800
_FORBIDDEN_OUTPUT = re.compile(
    r"(?:https?://|\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b|"
    r"\b(?:AIza|gh[ps]_|github_pat_|ya29\.)[A-Za-z0-9._-]+)",
    re.IGNORECASE,
)


def _is_bounded_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip()) and len(value) <= _MAX_TEXT and not _FORBIDDEN_OUTPUT.search(value)


def diagnosis_prompt(group: Any) -> str:
    """Build the only model prompt from a revalidated sanitized projection."""
    try:
        safe_group = dataclasses.asdict(group_from_projection(group))
    except (SanitizationError, TypeError, ValueError):
        raise ValueError("group is not an allowlisted sanitized projection") from None
    return """You are a read-only crash diagnosis agent. Diagnose only this sanitized JSON group:\n\n""" + json.dumps(
        safe_group, sort_keys=True
    ) + """\n\nReturn exactly one JSON object, with no Markdown or prose, using these exact keys:
red_command, red_exit_code, reproduction, hypotheses, evidence, regression_test, cleanup_plan.
You must propose 3–5 ranked, falsifiable hypotheses. `red_command` must be a
safe deterministic test command (./gradlew :app:test... --tests <name> or
python3 -m unittest ...), `red_exit_code` must be the non-zero code you
actually observed, and `regression_test` must name that failing test. Do not
include URLs, user data, credentials, raw crash data, or logs. You may inspect
the public checkout but must not edit files, run commands, use the network, or
change any GitHub state."""


def parse_model_diagnosis(output: str) -> Any:
    """Accept one JSON object only; prose around a model answer fails closed."""
    if not isinstance(output, str) or len(output) > 20_000:
        raise ValueError("malformed model output")
    try:
        value = json.loads(output)
    except json.JSONDecodeError as error:
        raise ValueError("model output must be exactly one JSON object") from error
    if not isinstance(value, dict):
        raise ValueError("model output must be an object")
    return value


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
    if not all(_is_bounded_text(item) for item in (command, reproduction, regression, cleanup)):
        return DiagnosisVerdict("needs-triage", "missing reproducible evidence")
    if type(value["red_exit_code"]) is not int or value["red_exit_code"] == 0:
        return DiagnosisVerdict("needs-triage", "red command did not fail")
    if not isinstance(hypotheses, list) or not 3 <= len(hypotheses) <= 5 or any(not _is_bounded_text(item) for item in hypotheses):
        return DiagnosisVerdict("needs-triage", "hypotheses are not ranked and falsifiable")
    if not isinstance(evidence, list) or not evidence or any(not _is_bounded_text(item) for item in evidence):
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
        verdict = verify_diagnosis_red_loop(parse_model_diagnosis(args.input.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        verdict = DiagnosisVerdict("needs-triage", "malformed diagnosis input")
    args.output.write_text(json.dumps(dataclasses.asdict(verdict), sort_keys=True), encoding="utf-8")
    print(json.dumps({"status": verdict.status, "reason": verdict.reason}))
    return 0 if verdict.status == "ready-for-agent" else 2


if __name__ == "__main__":
    raise SystemExit(main())
