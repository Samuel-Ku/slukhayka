#!/usr/bin/env python3
"""Read-only compact snapshot of open PRs and their local work reservations (#1228).

One command answers the questions an agent asks before starting work: which PRs
are open, are they drafts, what are their exact base/head SHAs, which checks are
pending or failed, which issues they close, and who locally reserved that work.
Full GitHub payloads are written to a caller-chosen file, never to stdout, so the
main output stays small. Nothing here mutates git, the index, the working tree,
reservations, issues or PRs — it only reads.
"""

import argparse
from datetime import datetime, timezone
import json
import subprocess
import sys
from pathlib import Path

FAILURE_STATES = {"FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE"}
PENDING_STATES = {"PENDING", "QUEUED", "IN_PROGRESS", "EXPECTED", "WAITING", "REQUESTED"}
SKIPPED_STATES = {"SKIPPED", "NEUTRAL"}


class GithubUnavailable(Exception):
    """GitHub could not be read at all — never report that as an empty state."""


def git_common_dir():
    try:
        return Path(subprocess.check_output(
            ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
            text=True,
        ).strip())
    except (OSError, subprocess.CalledProcessError):
        return None


def repo_from_remote(gh="gh"):
    try:
        url = subprocess.check_output(
            ["git", "remote", "get-url", "origin"], text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise GithubUnavailable(f"cannot read git remote: {error}")
    if url.startswith("git@github.com:"):
        slug = url[len("git@github.com:"):]
    elif "github.com/" in url:
        slug = url.split("github.com/", 1)[1]
    else:
        raise GithubUnavailable(f"origin is not a GitHub remote: {url}")
    slug = slug.removesuffix(".git").strip("/")
    if slug.count("/") != 1:
        raise GithubUnavailable(f"cannot derive owner/name from {url}")
    return slug


def load_claims(registry):
    claims = {}
    if registry is None or not registry.is_dir():
        return claims
    for path in sorted(registry.glob("*.json")):
        try:
            claim = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            claim = {"issue": path.stem, "status": "occupied-unreadable"}
        issue = claim.get("issue", path.stem)
        try:
            claims[int(issue)] = claim
        except (TypeError, ValueError):
            continue
    return claims


def fetch_prs(repo, gh="gh", fixture=None):
    """Returns (pulls, fixture comments). Fixtures may be a bare list (no comments)."""
    if fixture:
        try:
            payload = json.loads(Path(fixture).read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise GithubUnavailable(f"cannot read fixture: {error}")
        if isinstance(payload, dict):
            return payload.get("pulls", []), payload.get("comments", {})
        return payload, {}
    command = [
        gh, "pr", "list", "--repo", repo, "--state", "open", "--limit", "1000",
        "--json",
        "number,title,isDraft,baseRefName,headRefOid,url,statusCheckRollup,"
        "closingIssuesReferences,labels,reviewDecision",
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise GithubUnavailable(str(error))
    try:
        return json.loads(completed.stdout or "[]"), {}
    except ValueError as error:
        raise GithubUnavailable(f"gh returned invalid JSON: {error}")


def last_comment(repo, issue, gh="gh", fixture_comments=None):
    """The last GitHub comment on the issue, or an explicit unknown — never invented."""
    if fixture_comments is not None:
        comment = fixture_comments.get(str(issue))
        if not comment:
            return "none"
        author = (comment.get("user") or {}).get("login", "unknown")
        created = (comment.get("created_at") or "")[:10] or "unknown-date"
        return f"{author}@{created}"
    command = [
        gh, "api", f"repos/{repo}/issues/{issue}/comments?per_page=100",
        "--jq", ".[-1] | {user: {login: .user.login}, created_at: .created_at}",
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=True)
        comment = json.loads(completed.stdout or "null")
    except (OSError, subprocess.CalledProcessError, ValueError):
        return "unavailable"
    if not comment:
        return "none"
    author = (comment.get("user") or {}).get("login", "unknown")
    created = (comment.get("created_at") or "")[:10] or "unknown-date"
    return f"{author}@{created}"


def check_counts(rollup):
    """None means GitHub did not report checks — 'unknown', never 'all green'."""
    if rollup is None:
        return None
    counts = {"success": 0, "failure": 0, "pending": 0, "skipped": 0}
    for check in rollup:
        state = (check.get("conclusion") or check.get("state") or "").upper()
        if state == "SUCCESS":
            counts["success"] += 1
        elif state in FAILURE_STATES:
            counts["failure"] += 1
        elif state in SKIPPED_STATES:
            counts["skipped"] += 1
        elif state in PENDING_STATES or not state:
            counts["pending"] += 1
        else:
            counts["pending"] += 1
    return counts


def linked_issues(pull):
    numbers = []
    for reference in pull.get("closingIssuesReferences") or []:
        number = reference.get("number")
        if isinstance(number, int) and number not in numbers:
            numbers.append(number)
    return numbers


def compact_line(pull, claims, last_comment_text=None):
    number = pull.get("number")
    ready = "draft" if pull.get("isDraft") else "ready"
    head = (pull.get("headRefOid") or "")[:7] or "unknown"
    base = pull.get("baseRefName") or "unknown"
    counts = check_counts(pull.get("statusCheckRollup"))
    if counts is None:
        checks = "unknown"
    else:
        checks = (
            f"{counts['success']}ok/{counts['failure']}fail/"
            f"{counts['pending']}pend"
        )
        if counts["skipped"]:
            checks += f"/{counts['skipped']}skip"
    issues = linked_issues(pull)
    issues_text = ",".join(f"#{issue}" for issue in issues) if issues else "none"
    owners = [claims[issue] for issue in issues if issue in claims]
    if owners:
        reservation = ";".join(
            f"{claim.get('ownerThread', 'unknown')}:{claim.get('status', 'unknown')}"
            for claim in owners
        )
    else:
        # Absence of a LOCAL record is not proof the issue is free (#1228).
        reservation = "absent"
    if last_comment_text is not None:
        issues_text += f" last-comment={last_comment_text}"
    flags = []
    if pull.get("reviewDecision") == "CHANGES_REQUESTED":
        flags.append("changes-requested")
    return (
        f"#{number} {ready} base={base} head={head} checks={checks} "
        f"issues={issues_text} reservation={reservation}"
        + (f" flags={','.join(flags)}" if flags else "")
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", help="owner/name; derived from the git remote by default")
    parser.add_argument("--gh", default="gh", help="gh executable (tests inject a fake)")
    parser.add_argument("--fixture", help="read PR payloads from this JSON file instead of GitHub")
    parser.add_argument("--claims-dir", help="reservation registry directory")
    parser.add_argument("--full-json", help="write the complete GitHub payload to this file")
    parser.add_argument(
        "--no-comments", action="store_true",
        help="skip the last GitHub comment lookup for linked issues",
    )
    parser.add_argument(
        "--expect-head", action="append", default=[], metavar="NUMBER=SHA",
        help="fail when a PR's head no longer matches the expected SHA (repeatable)",
    )
    args = parser.parse_args(argv)

    try:
        repo = args.repo or repo_from_remote(args.gh)
        pulls, fixture_comments = fetch_prs(repo, gh=args.gh, fixture=args.fixture)
    except GithubUnavailable as error:
        print(json.dumps({"error": "github-unavailable", "detail": str(error)},
                         ensure_ascii=False), file=sys.stderr)
        return 3

    registry = Path(args.claims_dir) if args.claims_dir else None
    if registry is None:
        common = git_common_dir()
        registry = (common / "agent-work-claims") if common else None
    claims = load_claims(registry)

    comment_cache = {}

    def comment_for(issue):
        if args.no_comments:
            return None
        if issue not in comment_cache:
            comment_cache[issue] = last_comment(
                repo, issue, gh=args.gh,
                fixture_comments=fixture_comments if args.fixture else None,
            )
        return comment_cache[issue]

    captured_at = datetime.now(timezone.utc).isoformat()
    print(f"snapshot: {captured_at} repo={repo} prs={len(pulls)}")
    for pull in sorted(pulls, key=lambda item: item.get("number", 0)):
        texts = [comment_for(issue) for issue in linked_issues(pull)]
        last = texts[-1] if texts else None
        print(compact_line(pull, claims, last_comment_text=last))

    if args.full_json:
        destination = Path(args.full_json)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            json.dumps({"capturedAt": captured_at, "repo": repo, "pullRequests": pulls},
                       ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    by_number = {pull.get("number"): pull for pull in pulls}
    drifted = False
    for expected in args.expect_head:
        if "=" not in expected:
            parser.error(f"--expect-head expects NUMBER=SHA, got {expected!r}")
        number_text, sha = expected.split("=", 1)
        number = int(number_text)
        actual = (by_number.get(number) or {}).get("headRefOid")
        if actual is None:
            print(f"head-drift: #{number} is not open any more (expected {sha[:7]})")
            drifted = True
        elif not actual.startswith(sha):
            print(f"head-drift: #{number} expected={sha[:7]} actual={actual[:7]}")
            drifted = True
    return 4 if drifted else 0


if __name__ == "__main__":
    sys.exit(main())
