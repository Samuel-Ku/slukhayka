#!/usr/bin/env python3
"""Own the mutation trail: append run records and answer what the last run was.

#1221. `scripts/mutate.sh` does the work — apply one named mutation, run one test
class, roll the mutation back. This module owns the record it leaves behind, so
one file decides the schema that both writing and reading use.

A mutation run writes the same `app/build/test-results/testDebugUnitTest/*.xml`
that a real run writes, so an XML on disk cannot say where it came from. A record
can: it names the class, the task, HEAD, the patch, and the sha256 of the XML the
run produced. `status` re-hashes the XML on disk and compares.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path


SCHEMA = 1

# Every partition alias depends on AGP's single testDebugUnitTest task, so they
# all write one JUnit directory (app/build.gradle.kts registers them on it).
DEFAULT_RESULTS = "app/build/test-results/testDebugUnitTest"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def head_of(worktree: Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(worktree), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def read_records(log: Path) -> tuple[list[dict], int]:
    records: list[dict] = []
    malformed = 0
    if not log.is_file():
        return records, malformed
    for line in log.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            # A torn append (killed mid-write) must not hide the records around
            # it, but it must be visible: the count goes into every answer.
            malformed += 1
    return records, malformed


def build_record(values: dict[str, str], files: list[dict]) -> dict:
    def text(key: str):
        value = values.get(key, "")
        return value if value != "" else None

    def integer(key: str):
        value = values.get(key, "")
        return int(value) if value.strip() else None

    return {
        "schema": SCHEMA,
        "kind": "mutation",
        "runId": values["runId"],
        "mutation": values["mutation"],
        "class": values["class"],
        "task": values["task"],
        "partition": values["partition"],
        "patch": values["patch"],
        "patchSha256": text("patchSha256"),
        "files": files,
        "worktree": values["worktree"],
        "branch": text("branch"),
        "head": text("head"),
        "startedAt": values["startedAt"],
        "finishedAt": text("finishedAt"),
        "durationSeconds": integer("durationSeconds"),
        "exitCode": integer("exitCode"),
        "outcome": values["outcome"],
        "detail": text("detail"),
        "tests": integer("tests"),
        "failures": integer("failures"),
        "errors": integer("errors"),
        "artifact": text("artifact"),
        # artifactFresh says whether the XML on disk came from this run. When it
        # did not, artifactSha256 stays null: status reads a recorded hash as
        # proof of "this file is that run's output", so a stale hash would lie.
        "artifactFresh": values.get("artifactFresh") == "yes",
        "artifactSha256": text("artifactSha256"),
        "restored": values.get("restored") == "yes",
    }


def read_files(path: Path) -> list[dict]:
    files = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        while len(fields) < 3:
            fields.append("")
        relative, before, after = fields[:3]
        files.append(
            {
                "path": relative,
                "pristineSha256": before or None,
                "rolledBackSha256": after or None,
                "restored": before == after,
            }
        )
    return files


def record(args: argparse.Namespace) -> int:
    values: dict[str, str] = {}
    for argument in args.values:
        key, _, value = argument.partition("=")
        values[key] = value
    try:
        payload = build_record(values, read_files(args.files))
    except (KeyError, ValueError) as error:
        print(f"mutation trace error: unusable argument {error}", file=sys.stderr)
        return 2
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


def verdict_for(matching: list[dict], artifact: Path) -> tuple[str, str, dict]:
    digest = sha256_of(artifact)
    twins = [
        record for record in matching if record.get("artifactSha256") == digest
    ]
    if twins:
        twin = twins[-1]
        return (
            "mutational",
            "the XML on disk is byte-identical to the artifact that the mutation run "
            f"{twin.get('runId')} wrote ({twin.get('outcome')} at "
            f"{twin.get('finishedAt') or twin.get('startedAt')})",
            # The newest run for the class is usually the one that wrote the XML;
            # when it is not (a later run left no artifact), the id names the run
            # that did, and the log holds its full record.
            {"artifactSha256": digest, "artifactRunId": twin.get("runId")},
        )
    return (
        "genuine",
        "the XML on disk differs from every recorded mutation artifact for this "
        "class: it was written by a run this tool did not record",
        {"artifactSha256": digest},
    )


def status(args: argparse.Namespace) -> int:
    records, malformed = read_records(args.log)
    matching = [
        record for record in records if not args.test_class or record.get("class") == args.test_class
    ]
    newest = matching[-1] if matching else None

    result: dict = {
        "queryClass": args.test_class,
        "class": args.test_class or (newest or {}).get("class"),
        "log": str(args.log),
        "records": len(records),
        "malformedLines": malformed,
        "newestRun": newest,
    }

    if newest is not None:
        run_worktree = Path(newest.get("worktree") or args.root)
        result["runWorktree"] = str(run_worktree)
        result["runHead"] = newest.get("head")
        result["worktreeHead"] = head_of(run_worktree)
        if newest.get("head") and result["worktreeHead"]:
            result["headMatches"] = result["worktreeHead"] == newest["head"]
        if run_worktree != args.root and run_worktree.is_dir():
            result["note"] = (
                f"the record comes from {run_worktree}; its artifact was checked there"
            )

    # The XML path is deterministic for a class whether or not a record exists,
    # so "is there anything on disk at all?" is answered before verdicts are.
    # A record can name a worktree that has since moved (`git worktree move`):
    # then the same path under this root is where the XML would be, and the
    # bytes — not the directory — are what the verdict rests on.
    artifact = None
    if args.test_class or newest is not None:
        relative = (newest or {}).get("artifact") or (
            f"{args.results}/TEST-{args.test_class or newest.get('class', '')}.xml"
        )
        recorded_worktree = (
            Path(newest["worktree"]) if newest and newest.get("worktree") else None
        )
        candidates = []
        if recorded_worktree is not None:
            candidates.append(recorded_worktree / relative)
        candidates.append(args.root / relative)
        artifact = next(
            (candidate for candidate in candidates if candidate.is_file()),
            candidates[0],
        )
        result["artifact"] = str(artifact)
        if recorded_worktree is not None and not recorded_worktree.is_dir():
            result["note"] = f"the recorded worktree {recorded_worktree} is gone" + (
                f"; the XML was read from {artifact}"
                if artifact != recorded_worktree / relative
                else ""
            )

    if artifact is not None and not artifact.is_file():
        verdict = "no-artifact"
        basis = (
            f"no XML for {result['class']} is on disk"
            + (
                ""
                if newest is None
                else ", so the newest recorded mutation run of it left nothing to confuse"
            )
        )
    elif not args.log.is_file():
        verdict = "unknown"
        basis = (
            f"no {args.log.name} in this worktree, so nothing on disk can be traced "
            "to a recorded mutation run"
        )
    elif newest is None:
        verdict = "genuine"
        basis = (
            f"no mutation run of {args.test_class} is recorded in this worktree"
            if args.test_class
            else "the log holds no mutation run at all"
        )
    else:
        verdict, basis, extra = verdict_for(matching, artifact)
        result.update(extra)

    result["verdict"] = verdict
    result["basis"] = basis

    if verdict == "mutational":
        summary = (
            f"mutational: the newest test XML for {result['class']} on disk is the "
            "mutation run's own artifact — a failure there is the mutation, not a "
            "run of HEAD"
        )
    elif verdict == "genuine":
        summary = (
            f"genuine: the newest test XML for {result['class']} on disk is not a "
            "recorded mutation artifact — read it as a real run"
        )
    elif verdict == "no-artifact":
        summary = f"no-artifact: no test XML for {result['class']} is on disk"
    else:
        summary = f"unknown: {basis}"
    if result.get("headMatches") is False:
        summary += (
            f" (note: HEAD is now {result['worktreeHead'][:8]} in "
            f"{result['runWorktree']}, not the recorded run's "
            f"{str(newest.get('head'))[:8]} — that artifact says nothing about the "
            "current commit)"
        )
    result["summary"] = summary

    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = result.add_subparsers(dest="command", required=True)

    record_parser = subparsers.add_parser("record", help="append one run record")
    record_parser.add_argument("--log", type=Path, required=True)
    record_parser.add_argument("--files", type=Path, required=True)
    record_parser.add_argument("values", nargs="*", metavar="key=value")
    record_parser.set_defaults(handler=record)

    status_parser = subparsers.add_parser("status", help="answer what the last run was")
    status_parser.add_argument("--root", type=Path, default=Path.cwd())
    status_parser.add_argument("--log", type=Path, required=True)
    status_parser.add_argument("--results", default=DEFAULT_RESULTS)
    status_parser.add_argument("--class", dest="test_class", default="")
    status_parser.set_defaults(handler=status)
    return result


def main() -> int:
    args = parser().parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
