#!/usr/bin/env python3
"""Run one sanitized crash diagnosis through the read-only OpenCode boundary."""
from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
from pathlib import Path
from typing import Any

from scripts.crash_diagnosis import DiagnosisVerdict, diagnosis_prompt, parse_model_diagnosis, verify_diagnosis_red_loop
from scripts.crash_tracer import SanitizationError, group_from_projection


def diagnose(
    group: Any,
    run: Any = subprocess.run,
) -> tuple[dict[str, Any], DiagnosisVerdict]:
    """Invoke OpenCode without a shell, then independently replay its red loop."""
    try:
        projection = dataclasses.asdict(group_from_projection(group))
        prompt = diagnosis_prompt(projection)
    except (SanitizationError, TypeError, ValueError):
        return {}, DiagnosisVerdict("needs-triage", "invalid sanitized group")
    try:
        response = run(
            [
                "opencode", "run", "--model", "crash-diagnosis-proxy/glm-5.3-flash",
                "--format", "default", prompt,
            ],
            shell=False,
            check=False,
            capture_output=True,
            text=True,
            timeout=20 * 60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return projection, DiagnosisVerdict("needs-triage", "OpenCode diagnosis could not run")
    if response.returncode != 0:
        return projection, DiagnosisVerdict("needs-triage", "OpenCode diagnosis failed")
    try:
        diagnosis = parse_model_diagnosis(response.stdout)
    except ValueError:
        return projection, DiagnosisVerdict("needs-triage", "OpenCode returned malformed diagnosis")
    return projection, verify_diagnosis_red_loop(diagnosis)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run one read-only OpenCode crash diagnosis")
    parser.add_argument("--group", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        group = json.loads(args.group.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        group = None
    projection, verdict = diagnose(group)
    # This artifact is deliberately bounded: no model transcript or raw group
    # input survives the diagnosis process.
    result = {
        "fingerprint": projection.get("fingerprint"),
        "status": verdict.status,
        "reason": verdict.reason,
    }
    args.output.write_text(json.dumps(result, sort_keys=True), encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
