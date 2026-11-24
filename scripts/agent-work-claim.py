#!/usr/bin/env python3
"""Inspect or atomically reserve issue work across this repository's worktrees."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys


def read_claim(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"issue": path.stem, "status": "occupied-unreadable"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    status = commands.add_parser("status")
    status.add_argument("issue", type=int, nargs="?")
    acquire = commands.add_parser("acquire")
    acquire.add_argument("issue", type=int)
    acquire.add_argument("--owner", required=True)
    acquire.add_argument("--reservation", required=True)
    acquire.add_argument("--plan", required=True)
    args = parser.parse_args()
    if args.issue is not None and args.issue <= 0:
        parser.error("issue must be positive")

    common = Path(subprocess.check_output(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        text=True,
    ).strip())
    registry = common / "agent-work-claims"
    if args.command == "status":
        paths = ([registry / f"{args.issue}.json"] if args.issue is not None
                 else sorted(registry.glob("*.json")))
        print(json.dumps({"registry": str(registry), "claims": [
            read_claim(path) for path in paths if path.exists()
        ]}, ensure_ascii=False, indent=2))
        return 0

    registry.mkdir(parents=True, exist_ok=True)
    path = registry / f"{args.issue}.json"
    claim = {
        "issue": args.issue,
        "ownerThread": args.owner,
        "reservation": args.reservation,
        "status": "prepared-reserved",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "plan": args.plan,
    }
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print(json.dumps({"error": "already-reserved", "claim": read_claim(path)},
                         ensure_ascii=False), file=sys.stderr)
        return 2
    # A crash may leave an incomplete claim. It remains occupied until the
    # coordinator checks it; another agent must never silently steal it.
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(claim, output, ensure_ascii=False, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    print(json.dumps(claim, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
