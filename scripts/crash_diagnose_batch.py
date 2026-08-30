#!/usr/bin/env python3
"""Run the bounded #417 diagnosis subset and retain no model transcript."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Callable

from scripts.crash_diagnose import diagnose
from scripts.crash_tracer import SanitizationError, group_from_projection


def diagnose_batch(value: Any, run_one: Callable[[Any], tuple[dict[str, Any], Any, dict[str, Any] | None]] = diagnose) -> dict[str, list[dict[str, Any]]]:
    if not isinstance(value, dict) or set(value) != {"diagnose", "retained"}:
        raise SanitizationError("diagnosis queue has an unknown field")
    selected = value["diagnose"]
    if not isinstance(selected, list) or len(selected) > 3:
        raise SanitizationError("diagnosis queue exceeds the hard cap")
    result: list[dict[str, Any]] = []
    for item in selected:
        group = group_from_projection(item)
        projection, verdict, contract = run_one(item)
        # The worker must return the same marker; it cannot invent an Issue key.
        if projection.get("fingerprint") != group.fingerprint:
            raise SanitizationError("diagnosis result crossed a group boundary")
        entry: dict[str, Any] = {"fingerprint": group.fingerprint, "status": verdict.status, "reason": verdict.reason}
        if verdict.status == "ready-for-agent":
            if contract is None:
                raise SanitizationError("ready diagnosis omitted its verified contract")
            entry["contract"] = contract
        elif contract is not None:
            raise SanitizationError("unproven diagnosis cannot retain a contract")
        result.append(entry)
    return {"diagnoses": result}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Diagnose at most three sanitized crash groups")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = diagnose_batch(json.loads(args.input.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError, SanitizationError):
        result = {"diagnoses": []}
    args.output.write_text(json.dumps(result, sort_keys=True), encoding="utf-8")
    print(json.dumps({"diagnosed": len(result["diagnoses"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
