#!/usr/bin/env python3
"""Read-only compact snapshot of open PRs and their local work reservations (#1228).

One command answers the questions an agent asks before starting work: which PRs
are open, are they drafts, what are the exact base/head SHAs, which checks are
pending or failed, which issues they close, who locally reserved that work and
what the last GitHub comment on those issues says. Full GitHub payloads are
written to a caller-chosen file, never to stdout, so the main output stays small.
Nothing here mutates git, the index, the working tree, reservations, issues or
PRs — it only reads.
"""

import argparse
from datetime import datetime, timezone
import json
import subprocess
import sys
from pathlib import Path

FAILURE_STATES = {"FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED",
                  "STARTUP_FAILURE", "ERROR"}
PENDING_STATES = {"PENDING", "QUEUED", "IN_PROGRESS", "EXPECTED", "WAITING", "REQUESTED"}
SKIPPED_STATES = {"SKIPPED", "NEUTRAL"}
# `gh pr list --limit`; a full page means the list may be truncated and says so.
PR_LIMIT = 1000
COMMENTS_PER_PAGE = 100


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


def repo_from_remote():
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
        if not isinstance(claim, dict):
            claim = {"issue": path.stem, "status": "occupied-unreadable"}
        issue = claim.get("issue", path.stem)
        try:
            claims[int(issue)] = claim
        except (TypeError, ValueError):
            continue
    return claims


def fetch_prs(repo, gh="gh", fixture=None):
    """Returns (pulls, fixture comments, truncated flag)."""
    if fixture:
        try:
            payload = json.loads(Path(fixture).read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise GithubUnavailable(f"cannot read fixture: {error}")
        if isinstance(payload, dict):
            pulls = payload.get("pulls", [])
            return (
                pulls,
                payload.get("comments", {}),
                payload.get("truncated", len(pulls) >= PR_LIMIT),
            )
        return payload, {}, len(payload) >= PR_LIMIT
    command = [
        gh, "pr", "list", "--repo", repo, "--state", "open", "--limit", str(PR_LIMIT),
        "--json",
        "number,title,isDraft,baseRefName,baseRefOid,headRefOid,url,statusCheckRollup,"
        "closingIssuesReferences,labels,reviewDecision",
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise GithubUnavailable(str(error))
    try:
        pulls = json.loads(completed.stdout or "[]")
    except ValueError as error:
        raise GithubUnavailable(f"gh returned invalid JSON: {error}")
    return pulls, {}, len(pulls) >= PR_LIMIT


def check_counts(rollup):
    """None means GitHub did not report checks — 'unknown', never 'all green'."""
    if not rollup:
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


def _comment_text(comment):
    """A null-filled object from `--jq .[-1]` means there is no comment."""
    if not isinstance(comment, dict):
        return "none"
    author = (comment.get("user") or {}).get("login")
    if not author:
        return "none"
    created = (comment.get("created_at") or "")[:10] or "unknown-date"
    return f"{author}@{created}"


def last_comment(repo, issue, gh="gh", fixture_comments=None):
    """The last GitHub comment on the issue, or an explicit unknown — never invented."""
    if fixture_comments is not None:
        return _comment_text(fixture_comments.get(str(issue)))
    command = [
        gh, "api", "--paginate",
        f"repos/{repo}/issues/{issue}/comments?per_page={COMMENTS_PER_PAGE}",
        "--jq", ".[-1] | {user: {login: .user.login}, created_at: .created_at}",
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return "unavailable"
    lines = [line for line in completed.stdout.splitlines() if line.strip()]
    # `--paginate` prints one JSON value per page; a trailing empty page yields
    # `null`, which is not a comment and must not hide a real one before it.
    parsed = []
    for line in lines:
        try:
            parsed.append(json.loads(line))
        except ValueError:
            return "unavailable"
    for comment in reversed(parsed):
        if comment is None:
            continue
        return _comment_text(comment)
    return "none"


def divergence(claims_for_issues, comment_texts):
    """Explicit record divergence between the local registry and GitHub (#1228 AC4).

    The two records can only prove presence, not identity (the registry keeps a
    thread id, GitHub keeps a login), so the marker is about which side knows the
    work: `local-only`, `comment-only`, `both`, `none`, or `unknown` when GitHub
    could not be asked about a comment.
    """
    if not comment_texts:
        return None
    if any(text == "unavailable" for text in comment_texts):
        return "unknown"
    has_comment = any(text not in ("none", "unavailable") for text in comment_texts)
    has_local = bool(claims_for_issues)
    if has_local and has_comment:
        return "both"
    if has_local:
        return "local-only"
    if has_comment:
        return "comment-only"
    return "none"


def compact_line(pull, claims, comment_texts=None):
    number = pull.get("number")
    ready = "draft" if pull.get("isDraft") else "ready"
    head = pull.get("headRefOid") or "unknown"
    base_name = pull.get("baseRefName") or "unknown"
    base_sha = pull.get("baseRefOid") or "unknown"
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
    line = (
        f"#{number} {ready} base={base_name}@{base_sha} head={head} "
        f"checks={checks} issues={issues_text} reservation={reservation}"
    )
    if comment_texts:
        comments = ",".join(
            f"#{issue}:{text}" for issue, text in zip(issues, comment_texts)
        )
        line += f" last-comment={comments}"
        marker = divergence(owners, comment_texts)
        if marker:
            line += f" divergence={marker}"
    flags = []
    if pull.get("reviewDecision") == "CHANGES_REQUESTED":
        flags.append("changes-requested")
    if flags:
        line += f" flags={','.join(flags)}"
    return line


def parse_expect_head(values, parser):
    expected = []
    for raw in values:
        if "=" not in raw:
            parser.error(f"--expect-head expects NUMBER=SHA, got {raw!r}")
        number_text, sha = raw.split("=", 1)
        try:
            number = int(number_text)
        except ValueError:
            parser.error(f"--expect-head expects a numeric PR number, got {number_text!r}")
        if number <= 0:
            parser.error(f"--expect-head expects a positive PR number, got {number}")
        sha = sha.strip()
        if len(sha) < 7 or any(char not in "0123456789abcdefABCDEF" for char in sha):
            parser.error(f"--expect-head expects a hexadecimal SHA, got {sha!r}")
        expected.append((number, sha))
    return expected


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
    expected_heads = parse_expect_head(args.expect_head, parser)

    try:
        repo = args.repo or repo_from_remote()
        pulls, fixture_comments, truncated = fetch_prs(repo, gh=args.gh, fixture=args.fixture)
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
    comments_read = {}

    def comment_for(issue):
        if args.no_comments:
            return None
        if issue not in comment_cache:
            text = last_comment(
                repo, issue, gh=args.gh,
                fixture_comments=fixture_comments if args.fixture else None,
            )
            comment_cache[issue] = text
            comments_read[str(issue)] = text
        return comment_cache[issue]

    captured_at = datetime.now(timezone.utc).isoformat()
    print(f"snapshot: {captured_at} repo={repo} prs={len(pulls)}")
    if truncated:
        print(f"truncated: gh returned at least {PR_LIMIT} open PRs — the list may be incomplete")
    for pull in sorted(pulls, key=lambda item: item.get("number", 0)):
        texts = [] if args.no_comments else [
            comment_for(issue) for issue in linked_issues(pull)
        ]
        print(compact_line(pull, claims, comment_texts=texts))

    if args.full_json:
        destination = Path(args.full_json)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            json.dumps({
                "capturedAt": captured_at,
                "repo": repo,
                "pullRequests": pulls,
                "comments": comments_read,
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    by_number = {pull.get("number"): pull for pull in pulls}
    drifted = False
    for number, sha in expected_heads:
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
