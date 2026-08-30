"""#416 guard before an isolated crash-fix agent may propose a branch/PR."""
from __future__ import annotations

import dataclasses
import json
from pathlib import Path
from typing import Any

from scripts.crash_diagnosis import validate_diagnosis, verify_diagnosis_red_loop
from scripts.crash_tracer import SanitizationError, group_from_projection


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


def gate_contract(issue_number: int, contract: Any, open_pr_exists: bool, run: Any) -> ImplementationGate:
    """Reproduce the approved red loop before exposing an editable checkout."""
    if type(issue_number) is not int or issue_number <= 0:
        return ImplementationGate(False, None, "invalid crash Issue number")
    if validate_diagnosis(contract).status != "ready-for-agent":
        return ImplementationGate(False, None, "malformed diagnosis contract")
    replay = verify_diagnosis_red_loop(contract, run=run)
    # `verify_diagnosis_red_loop` has already constrained the command and
    # independently observed a non-zero exit before this agent receives edit.
    return gate(issue_number, replay.status, 1 if replay.status == "ready-for-agent" else 0, open_pr_exists)


def select_contract(queue: Any, diagnoses: Any, issues: Any, issue_number: int) -> dict[str, Any] | None:
    """Join three bounded handoffs without letting the model choose an Issue."""
    if not isinstance(queue, dict) or set(queue) != {"diagnose", "retained"} or not isinstance(queue["diagnose"], list):
        raise SanitizationError("implementation queue is malformed")
    selected = [group_from_projection(item) for item in queue["diagnose"]]
    if len(selected) > 3:
        raise SanitizationError("implementation queue exceeds diagnosis cap")
    if not isinstance(diagnoses, dict) or set(diagnoses) != {"diagnoses"} or not isinstance(diagnoses["diagnoses"], list):
        raise SanitizationError("implementation diagnoses are malformed")
    if not isinstance(issues, dict) or set(issues) != {"issues"} or not isinstance(issues["issues"], list):
        raise SanitizationError("implementation Issue mapping is malformed")
    by_fingerprint = {item["fingerprint"]: item for item in diagnoses["diagnoses"] if isinstance(item, dict) and isinstance(item.get("fingerprint"), str)}
    mappings = {item["fingerprint"]: item for item in issues["issues"] if isinstance(item, dict) and isinstance(item.get("fingerprint"), str)}
    for group in selected:
        diagnosis, mapping = by_fingerprint.get(group.fingerprint), mappings.get(group.fingerprint)
        if not isinstance(diagnosis, dict) or not isinstance(mapping, dict):
            continue
        if mapping.get("issue_number") != issue_number or diagnosis.get("status") != "ready-for-agent":
            continue
        if set(diagnosis) != {"fingerprint", "status", "reason", "contract"} or validate_diagnosis(diagnosis["contract"]).status != "ready-for-agent":
            raise SanitizationError("ready diagnosis contract is malformed")
        if set(mapping) != {"fingerprint", "event_type", "issue_number", "action"}:
            raise SanitizationError("Issue mapping crossed a trust boundary")
        return {"issue_number": issue_number, "fingerprint": group.fingerprint, "contract": diagnosis["contract"]}
    return None


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Select one verified crash implementation contract")
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--diagnoses", type=Path, required=True)
    parser.add_argument("--issues", type=Path, required=True)
    parser.add_argument("--issue", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        plan = select_contract(
            json.loads(args.queue.read_text(encoding="utf-8")),
            json.loads(args.diagnoses.read_text(encoding="utf-8")),
            json.loads(args.issues.read_text(encoding="utf-8")),
            args.issue,
        )
    except (OSError, json.JSONDecodeError, SanitizationError):
        plan = None
    args.output.write_text(json.dumps(plan or {}, sort_keys=True), encoding="utf-8")
    print(json.dumps({"selected": bool(plan)}))
    return 0 if plan else 2


if __name__ == "__main__":
    raise SystemExit(main())
