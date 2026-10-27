#!/usr/bin/env python3
"""Mechanical merge gate: a FRESH `review:pass` verdict on the current HEAD.

Why this exists. `scripts/merge-pr.sh` is the real gate, but it runs only when a
human or agent deliberately calls it. PR #1490 was merged with a plain
`gh pr merge` and no review verdict at all, because branch protection required
just one check and nothing made the verdict part of the green board. This
script is that missing CI check: once the `Review gate` job is required in
branch protection, a PR cannot look mergeable without a verdict, whatever tool
someone reaches for.

What it checks, in this order:

1. the PR is open and not a draft;
2. the verdict label (default `review:pass`) is present on the PR;
3. the LATEST `labeled` event for that label is not older than the PR head
   commit, and not older than the last `head_ref_force_pushed` event — a verdict
   given before the last push (or a force-push back to an older commit) does
   not cover what would be merged.

Freshness against a re-run of CI is deliberately left to `scripts/merge-pr.sh`:
it counts every check-run after the verdict, and that is the final gate before
the merge. This check only publishes the verdict as a status so a plain
`gh pr merge` cannot present a green board without one; it does not pretend to
replace the final gate's re-run proof.

Exit codes: 0 — gate passes; 1 — gate refuses; 2 — cannot verify (arguments,
`gh`, network, unreadable timeline). A refusal is a verdict; an unknown state is
never reported as one.

Env: REVIEW_GATE_GH — path to `gh` (default `gh`; tests point it at a fake).
     REVIEW_GATE_LABEL — verdict label name (default `review:pass`).
     REVIEW_GATE_REPO — `owner/name` (default: GITHUB_REPOSITORY, then
     `gh repo view`).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone

DEFAULT_LABEL = "review:pass"


class CannotVerify(Exception):
    """The gate could not read the facts it needs — never a pass, never a refusal."""


def _run_gh(gh: str, *arguments: str) -> str:
    try:
        completed = subprocess.run(
            [gh, *arguments],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise CannotVerify(f"не вдалося запустити {gh}: {error}") from error
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip().splitlines()
        reason = detail[-1] if detail else f"exit {completed.returncode}"
        raise CannotVerify(f"gh {' '.join(arguments)} → {reason}")
    return completed.stdout


def _iso(raw: str, what: str) -> datetime:
    text = raw.strip()
    if not text:
        raise CannotVerify(f"порожній час: {what}")
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise CannotVerify(f"не розібрати час {what}: {text!r}") from error
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _repo_name(gh: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    from_ci = os.environ.get("GITHUB_REPOSITORY")
    if from_ci:
        return from_ci
    raw = _run_gh(gh, "repo", "view", "--json", "nameWithOwner")
    try:
        return json.loads(raw)["nameWithOwner"]
    except (ValueError, KeyError, TypeError) as error:
        raise CannotVerify("не вдалося визначити owner/name репозиторію") from error


def _pr_state(gh: str, pr: str, repo: str) -> dict:
    raw = _run_gh(
        gh, "pr", "view", pr, "--repo", repo,
        "--json", "state,isDraft,headRefOid,headRefName,labels",
    )
    try:
        parsed = json.loads(raw)
    except ValueError as error:
        raise CannotVerify(f"gh pr view {pr}: відповідь не JSON") from error
    if not isinstance(parsed, dict):
        raise CannotVerify(f"gh pr view {pr}: неочікувана відповідь")
    return parsed


def _head_committed_at(gh: str, repo: str, sha: str) -> datetime:
    raw = _run_gh(gh, "api", f"repos/{repo}/commits/{sha}", "--jq", ".commit.committer.date")
    return _iso(raw, f"head {sha[:12]}")


def _tl_events(gh: str, repo: str, pr: str) -> list[dict]:
    # `--slurp` wraps the pages into one array of arrays; flatten it because a
    # long PR timeline is paginated and bare `--paginate` would return several
    # concatenated JSON arrays that `json.loads` cannot read.
    raw = _run_gh(gh, "api", f"repos/{repo}/issues/{pr}/timeline", "--paginate", "--slurp")
    try:
        pages = json.loads(raw)
    except ValueError as error:
        raise CannotVerify("стрічка подій PR: відповідь не JSON") from error
    if not isinstance(pages, list):
        raise CannotVerify("стрічка подій PR: неочікувана відповідь")
    events = [
        event
        for page in pages
        for event in (page if isinstance(page, list) else [page])
    ]
    if not isinstance(events, list):
        raise CannotVerify("стрічка подій PR: неочікувана відповідь")
    return [event for event in events if isinstance(event, dict)]


def _timeline_moments(
    gh: str, repo: str, pr: str, label: str
) -> tuple[datetime, datetime | None]:
    """Latest label event and latest force-push event, in one timeline pass."""
    verdicts: list[datetime] = []
    rewrites: list[datetime] = []
    for event in _tl_events(gh, repo, pr):
        kind = event.get("event")
        # A real PR timeline carries events that are not ours and have no
        # `created_at` at all (`committed` has only author/committer dates).
        # Parse the timestamp ONLY for the two events this check reads; doing
        # it for every event made a healthy PR fail with "empty time" — the
        # class of bug that a fixture without such events cannot see.
        if kind == "labeled":
            event_label = event.get("label")
            name = event_label.get("name") if isinstance(event_label, dict) else None
            if name == label:
                verdicts.append(_iso(str(event.get("created_at", "")), f"подія {label}"))
        elif kind == "head_ref_force_pushed":
            rewrites.append(_iso(str(event.get("created_at", "")), "подія force-push"))
    if not verdicts:
        raise LookupError(label)
    return max(verdicts), (max(rewrites) if rewrites else None)


def verdict(gh: str, pr: str, label: str, repo: str) -> str:
    state = _pr_state(gh, pr, repo)
    if state.get("state") != "OPEN":
        return "FAIL  PR не відкритий — вердикт не застосовується"
    if state.get("isDraft"):
        return "FAIL  PR — чернетка; огляд і вердикт питаються лише для готового PR"
    head_sha = state.get("headRefOid") or ""
    if not head_sha:
        raise CannotVerify("gh pr view не назвав head SHA")
    labels = state.get("labels") or []
    if not any(isinstance(item, dict) and item.get("name") == label for item in labels):
        return (
            f"FAIL  на PR немає мітки '{label}' — незалежний огляд не дав PASS "
            "(мітку ставить оглядач, не автор PR)"
        )
    try:
        label_at, force_push_at = _timeline_moments(gh, repo, pr, label)
    except LookupError:
        raise CannotVerify(f"мітка '{label}' є в PR, але події labeled у стрічці немає")
    head_at = _head_committed_at(gh, repo, head_sha)
    if label_at < head_at:
        return (
            f"FAIL  вердикт '{label}' старіший за HEAD ({label_at.isoformat()} < "
            f"{head_at.isoformat()}) — після останнього коміту огляду не було"
        )
    if force_push_at is not None and label_at < force_push_at:
        return (
            f"FAIL  після вердикту '{label}' ({label_at.isoformat()}) гілку "
            f"перезаписали ({force_push_at.isoformat()}) — огляд був для іншої "
            "ревізії"
        )
    return (
        f"PASS  свіжий вердикт '{label}': {label_at.isoformat()} ≥ HEAD "
        f"{head_sha[:12]} ({head_at.isoformat()})"
    )


def main(argv: list[str]) -> int:
    arguments = list(argv[1:])
    if not arguments or arguments[0] in ("-h", "--help"):
        print(
            "usage: scripts/review_gate.py <номер-PR>\n\n"
            "Механічний мердж-гейт: свіжий review:pass на поточному HEAD.\n"
            "Коди виходу: 0 — PASS; 1 — відмова; 2 — перевірити не вдалося.",
            file=sys.stderr if not arguments else sys.stdout,
        )
        return 0 if arguments else 2
    if len(arguments) != 1:
        print("FAIL  зайвий аргумент: review_gate.py приймає лише номер PR", file=sys.stderr)
        return 2
    pr = arguments[0]
    if not pr.isdigit():
        print(f"FAIL  номер PR мусить бути числом, отримано {pr!r}", file=sys.stderr)
        return 2
    gh = os.environ.get("REVIEW_GATE_GH", "gh")
    label = os.environ.get("REVIEW_GATE_LABEL", DEFAULT_LABEL)
    repo = os.environ.get("REVIEW_GATE_REPO") or None
    try:
        repo = _repo_name(gh, repo)
        message = verdict(gh, pr, label, repo)
    except CannotVerify as error:
        print(f"FAIL  перевірити вердикт не вдалося: {error}", file=sys.stderr)
        return 2
    print(message)
    return 0 if message.startswith("PASS") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
