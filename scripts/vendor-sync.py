#!/usr/bin/env python3
"""Keep vendored upstream files pinned and fresh.

A vendored file (a BotGuard asset copied from NewPipe, say) is not a
dependency: no package manager can bump it. Each one is described by
vendor/<name>.lock, and this script compares that pin against upstream and
rewrites the file and lock when upstream moved.

    python3 scripts/vendor-sync.py            # update files in place
    python3 scripts/vendor-sync.py --check    # exit 1 on drift, write nothing

With no vendor/*.lock present the script is a clean no-op, so the scheduled
workflow exists before the first component is ported.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

LOCK_DIR = Path("vendor")
RAW = "https://raw.githubusercontent.com"
API = "https://api.github.com"


def _token() -> str | None:
    return os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or None


def _get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "slukhayka-vendor-sync"})
    token = _token()
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def latest_commit(repo: str, path: str, ref: str) -> str:
    url = (
        f"{API}/repos/{repo}/commits"
        f"?path={urllib.parse.quote(path)}&sha={urllib.parse.quote(ref)}&per_page=1"
    )
    payload = json.loads(_get(url).decode("utf-8"))
    if not payload:
        raise SystemExit(f"vendor-sync: no commits for {repo}:{path}@{ref}")
    return payload[0]["sha"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="never write; exit 1 on drift")
    args = parser.parse_args()

    locks = sorted(LOCK_DIR.glob("*.lock"))
    if not locks:
        print("vendor-sync: no vendored components pinned")
        return 0

    drifted = 0
    for lock_path in locks:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        repo, path, ref = lock["repo"], lock["path"], lock.get("ref", "main")
        commit = latest_commit(repo, path, ref)
        if commit == lock.get("commit"):
            print(f"vendor-sync: {lock_path.name} up to date at {commit[:7]}")
            continue
        body = _get(f"{RAW}/{repo}/{commit}/{urllib.parse.quote(path)}")
        digest = hashlib.sha256(body).hexdigest()
        if digest == lock.get("sha256"):
            print(f"vendor-sync: {lock_path.name} content unchanged at {commit[:7]}")
            continue
        drifted += 1
        print(
            f"vendor-sync: {lock_path.name} drifted "
            f"{str(lock.get('commit', '?'))[:7]} -> {commit[:7]}"
        )
        if args.check:
            continue
        dest = Path(lock["dest"])
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(body)
        lock["commit"] = commit
        lock["sha256"] = digest
        lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")

    if args.check and drifted:
        print(f"vendor-sync: {drifted} vendored component(s) drifted", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
