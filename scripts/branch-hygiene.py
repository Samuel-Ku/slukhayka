#!/usr/bin/env python3
"""Порядок у гілках: зібрати факти, прибрати поглинуте main, скласти нічний звіт.

Команди, які викликає нічний воркфлоу в CI і таймер на цій машині:

    collect        — факти про віддалені гілки та їхні PR (JSON, нічого не змінює)
    collect-local  — те саме про локальні гілки й worktree
    cleanup        — рішення про віддалені гілки; без --apply це сухий прогін
    local          — локальні гілки та worktree; без --apply це сухий прогін
    report         — markdown-звіт, з наративом LLM, якщо є токен (--upsert оновлює issue)

Правила, пороги й захищені назви — docs/runbooks/branch-hygiene.md.
Скрипт ніколи не видаляє гілку, поки не доведено, що її вміст уже в main:
предок main, злитий у main PR із тим самим tip (і merge-комітом у графі main),
або нуль унікальних патчів. Видалення пінить доведений sha через
`--force-with-lease`, тож гілка, що рушила після збору фактів, не зникає.

Коди виходу (як у сусідніх механізмів):
    0 — усе, що доведено, прибрано (можливо, прибирати було нічого);
    1 — частину гілок прибрати не вдалося (робота не зроблена повністю);
    2 — перевірити не вдалося: немає даних про PR під час `--apply`, немає
        `origin/main`, gh і GitHub API недоступні.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_MAIN = "main"
DEFAULT_STALE_DAYS = 14

# Гілки, які не чіпаємо ніколи: основна лінія, правова основа ліцензії та
# страхові копії (їхня єдина мета — пережити те, що вже в main).
PROTECTED_EXACT = ("main", "master", "HEAD")
PROTECTED_PREFIXES = ("legal/", "backup/")
RELEASE_PREFIX = "release/"

ISSUE_TITLE = "Branch hygiene — nightly report"
ISSUE_LABEL = "branch-hygiene"
ISSUE_MARKER = "<!-- branch-hygiene-report -->"

DEFAULT_LLM_URL = "https://opencode.ai/zen/go/v1"
DEFAULT_LLM_MODEL = "deepseek-v4.1-flash"
DEFAULT_LLM_SESSION = "branch-hygiene-github-actions"
LLM_MAX_TOKENS = 1200

RULE_TEXT = {
    "ancestor": "tip is an ancestor of main",
    "pr-merged-sha": "merged PR with the same tip, merge commit in the main graph",
    "patch-absorbed": "zero unique patches against main",
}

LLM_SYSTEM_PROMPT = (
    "You are the branch-hygiene warden for the «Слухайка» repository. You are given facts "
    "about remote branches and their PRs. Write the \"What is worth doing\" section in "
    "English: 3-8 bullets, each one \"branch or PR → action and reason\". Use ONLY the "
    "given facts, invent nothing. Do not suggest deleting protected branches, release "
    "branches without a v* tag, or branches with an open PR. Tone — calm and dry, no "
    "marketing and no bureaucratese (docs/voice.md). No preamble and no heading: bullets "
    "right away."
)


# --------------------------------------------------------------------------- #
# Дрібні помічники
# --------------------------------------------------------------------------- #


def run(cmd, *, check=True, input_text=None, cwd=None):
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, input=input_text, check=False, cwd=cwd
        )
    except (FileNotFoundError, NotADirectoryError) as exc:
        # Каталогу немає (prunable worktree) — це відмова, а не падіння.
        if check:
            raise RuntimeError(f"{' '.join(cmd)} → {exc}") from exc
        return subprocess.CompletedProcess(cmd, 127, "", str(exc))
    if check and result.returncode != 0:
        raise RuntimeError(
            f"{' '.join(cmd)} → код {result.returncode}: {result.stderr.strip()}"
        )
    return result


def git(*args, check=True, cwd=None):
    return run(["git", *args], check=check, cwd=cwd)


def last_line(result):
    """Останній рядок виводу — те, що варто показати в звіті про невдачу."""
    output = (result.stderr or result.stdout).strip()
    return output.splitlines()[-1] if output else "невідома помилка"


def parse_iso(value):
    if not value:
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        stamp = datetime.fromisoformat(text)
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp


def age_days(stamp, now):
    if stamp is None:
        return None
    return max(0, int((now - stamp).total_seconds() // 86400))


def dump_json(path, payload):
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    if path == "-":
        print(text)
        return
    Path(path).write_text(text + "\n", encoding="utf-8")
    print(f"JSON: {path}", file=sys.stderr)


# --------------------------------------------------------------------------- #
# collect
# --------------------------------------------------------------------------- #


def repo_slug():
    """`owner/repo` з origin — без мережі, без gh і без keyring."""
    url = git("remote", "get-url", "origin", check=False).stdout.strip()
    for pattern in (
        r"git@[^:]+:(.+?)(?:\.git)?$",
        r"ssh://[^/]+/(.+?)(?:\.git)?$",
        r"https?://[^/]+/(.+?)(?:\.git)?$",
    ):
        match = re.match(pattern, url)
        if match:
            return match.group(1)
    return None


def prs_from_api(slug, token=None, pages=10, timeout=30):
    """PR із GitHub API: працює без gh, без токена й без keyring.

    Репозиторій публічний, тож анонімного ліміту (60 запитів на годину) досить
    на кілька сторінок. Токен лише піднімає ліміт, якщо він узагалі є.
    """
    if not slug:
        return None
    token = token or os.environ.get("BRANCH_HYGIENE_GITHUB_TOKEN") or os.environ.get("GITHUB_TOKEN")
    items = []
    for page in range(1, pages + 1):
        url = (
            f"https://api.github.com/repos/{slug}/pulls"
            f"?state=all&sort=updated&direction=desc&per_page=100&page={page}"
        )
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "slukhayka-branch-hygiene",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                page_items = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, OSError, ValueError) as exc:
            print(f"GitHub API недоступний: {exc}", file=sys.stderr)
            return None
        items.extend(page_items)
        if len(page_items) < 100:
            break
    return [mapped for mapped in (map_api_pr(item) for item in items) if mapped]


def map_api_pr(item):
    """Один PR із REST API у ту саму форму, що дає `gh pr list`."""
    head = item.get("head") or {}
    head_repo = head.get("repo") or {}
    if not head_repo:
        # PR із видаленого форка не можна приписати цьому репозиторію.
        return None
    state = "OPEN" if item.get("state") == "open" else "CLOSED"
    if item.get("merged_at"):
        state = "MERGED"
    return {
        "number": item.get("number"),
        "headRefName": head.get("ref") or "",
        "baseRefName": (item.get("base") or {}).get("ref") or "",
        "state": state,
        "isDraft": bool(item.get("draft")),
        "title": item.get("title") or "",
        "updatedAt": item.get("updated_at"),
        "mergedAt": item.get("merged_at"),
        "headRefOid": head.get("sha"),
        "mergeCommit": {"oid": item.get("merge_commit_sha")},
        "url": item.get("html_url"),
        "headRepositoryOwner": {"login": (head_repo.get("owner") or {}).get("login")},
    }


def fetch_prs(use_gh=True, pr_facts_path=None, slug=None, api_token=None):
    """PR усіх станів і джерело даних. `(None, None)` — «даних немає», не помилка."""
    if pr_facts_path:
        return json.loads(Path(pr_facts_path).read_text(encoding="utf-8")), "facts"
    if use_gh:
        result = run(
            [
                "gh", "pr", "list", "--state", "all", "--limit", "1000",
                "--json",
                "number,headRefName,baseRefName,state,isDraft,title,updatedAt,"
                "mergedAt,headRefOid,mergeCommit,url,headRepositoryOwner",
            ],
            check=False,
        )
        if result.returncode == 0:
            try:
                return json.loads(result.stdout), "gh"
            except ValueError:
                pass
        else:
            print(f"gh pr list недоступний: {last_line(result)}", file=sys.stderr)
    prs = prs_from_api(slug, api_token)
    if prs is None:
        return None, None
    return prs, "api"


def normalize_prs(raw, owner):
    prs = []
    for item in raw or []:
        head_owner = (item.get("headRepositoryOwner") or {}).get("login")
        # PR з форка може мати таку саму назву гілки, як локальна: не плутаємо.
        if owner and head_owner != owner:
            continue
        prs.append(
            {
                "number": item.get("number"),
                "headRefName": item.get("headRefName") or "",
                "baseRefName": item.get("baseRefName") or "",
                "state": item.get("state"),
                "isDraft": bool(item.get("isDraft")),
                "title": item.get("title") or "",
                "updatedAt": item.get("updatedAt"),
                "mergedAt": item.get("mergedAt"),
                "headRefOid": item.get("headRefOid"),
                "mergeCommit": item.get("mergeCommit"),
                "url": item.get("url"),
            }
        )
    return prs


def merge_commit_in_main(pr, main_sha):
    """Чи справді merge-коміт цього PR у графі main.

    Після force-push у `main` (у цьому репозиторії вже був —
    `docs/history-cleanup-force-push.md`) PR лишається MERGED із тим самим
    `headRefOid`, але його вмісту в `main` немає. Без цієї звірки видалення
    гілки знесло б єдиний ref на таку роботу.
    """
    merge = pr.get("mergeCommit")
    oid = merge.get("oid") if isinstance(merge, dict) else merge
    if not oid:
        return False
    return git("merge-base", "--is-ancestor", oid, main_sha, check=False).returncode == 0


def branch_facts(name, sha, main_sha, prs_by_branch, main=DEFAULT_MAIN):
    """Усе, що потрібне рішенню про одну гілку — спільне для origin і локальних."""
    tip = git("log", "-1", "--format=%cI\t%an\t%s", sha, check=False).stdout.rstrip("\n").split("\t")
    tip_date = parse_iso(tip[0]) if tip else None
    ancestor = git("merge-base", "--is-ancestor", sha, main_sha, check=False).returncode == 0
    unique_patches = None
    if not ancestor:
        cherry = git("cherry", main_sha, sha, check=False)
        if cherry.returncode == 0:
            unique_patches = sum(1 for row in cherry.stdout.splitlines() if row.startswith("+"))
    tags = git("tag", "--merged", sha, "--list", "v*", check=False).stdout.split()
    prs = prs_by_branch.get(name, [])
    for pr in prs:
        if pr.get("state") == "MERGED":
            pr["inMainGraph"] = merge_commit_in_main(pr, main_sha)
    return {
        "name": name,
        "sha": sha,
        "ancestor": ancestor,
        "unique_patches": unique_patches,
        "tagged_release": tags[0] if tags else None,
        "tip_date": tip_date.isoformat() if tip_date else None,
        "tip_author": tip[1] if len(tip) > 1 else "",
        "tip_subject": tip[2] if len(tip) > 2 else "",
        "prs": prs,
    }


def collection_context(main, use_gh, pr_facts_path, slug, now=None):
    """Спільний початок обох зборів: свіжість main, PR і розкладка за гілками."""
    has_main = run(
        ["git", "rev-parse", "--verify", "--quiet", f"refs/remotes/origin/{main}"],
        check=False,
    )
    if has_main.returncode != 0:
        raise SystemExit(
            f"Немає refs/remotes/origin/{main} — спершу зроби git fetch origin"
        )
    now = now or datetime.now(timezone.utc)
    main_sha = git("rev-parse", f"refs/remotes/origin/{main}").stdout.strip()
    slug = slug or repo_slug()
    raw_prs, pr_source = fetch_prs(
        use_gh=use_gh, pr_facts_path=pr_facts_path, slug=slug
    )
    owner = slug.split("/")[0] if slug else None
    prs_by_branch = {}
    for pr in normalize_prs(raw_prs, owner):
        prs_by_branch.setdefault(pr["headRefName"], []).append(pr)
    return now, main_sha, raw_prs, pr_source, prs_by_branch


def collect_facts(main=DEFAULT_MAIN, use_gh=True, pr_facts_path=None, now=None, slug=None):
    now, main_sha, raw_prs, pr_source, prs_by_branch = collection_context(
        main, use_gh, pr_facts_path, slug, now
    )

    refs = git(
        "for-each-ref",
        "refs/remotes/origin",
        "--format=%(refname:short)\t%(objectname)\t%(symref)",
    ).stdout.splitlines()

    branches = []
    for line in refs:
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        short, sha, symref = parts[0], parts[1], parts[2]
        if symref or short in (f"origin/{main}", "origin"):
            continue
        name = short[len("origin/"):] if short.startswith("origin/") else short
        branches.append(branch_facts(name, sha, main_sha, prs_by_branch, main))

    branches.sort(key=lambda b: b["name"])
    return {
        "collected_at": now.isoformat(),
        "main": main,
        "main_sha": main_sha,
        "pr_source": pr_source,
        "pr_data": raw_prs is not None,
        "branches": branches,
    }


# --------------------------------------------------------------------------- #
# Рішення
# --------------------------------------------------------------------------- #


def is_protected(name, protect=()):
    if name in PROTECTED_EXACT or name.startswith(PROTECTED_PREFIXES):
        return True
    return any(fnmatch.fnmatch(name, pattern) for pattern in protect)


def decide(branch, *, protect=(), main=DEFAULT_MAIN, now=None):
    """Що робити з гілкою. Єдине місце, де народжується право на видалення."""
    now = now or datetime.now(timezone.utc)
    name = branch["name"]
    prs = branch.get("prs") or []
    tip_date = parse_iso(branch.get("tip_date"))
    base = {
        "name": name,
        "sha": branch["sha"],
        "tip_date": branch.get("tip_date"),
        "tip_author": branch.get("tip_author", ""),
        "tip_subject": branch.get("tip_subject", ""),
        "age_days": age_days(tip_date, now),
        "open_prs": [p for p in prs if p["state"] == "OPEN"],
        "merged_prs": [p for p in prs if p["state"] == "MERGED"],
        "merged_into_main": [
            p for p in prs
            if p["state"] == "MERGED" and p.get("baseRefName", main) == main
        ],
        "closed_prs": [p for p in prs if p["state"] == "CLOSED"],
        "prs": prs,
        "protected": is_protected(name, protect),
        "release": name.startswith(RELEASE_PREFIX),
    }

    def verdict(action, rule, reason):
        base.update(action=action, rule=rule, reason=reason)
        return base

    def numbers_of(items):
        return ", ".join(f"#{p['number']}" for p in items)

    if is_protected(name, protect):
        return verdict("keep", None, "protected branch name")

    absorbed = None
    if branch["ancestor"]:
        absorbed = "ancestor"
    elif any(
        p.get("headRefOid") == branch["sha"] and p.get("inMainGraph")
        for p in base["merged_into_main"]
    ):
        # Злитий у main PR із тим самим tip: гілка не рухалася після злиття,
        # отже не несе нічого, чого немає в main (працює й для squash/rebase).
        # PR, злитий у релізну лінію, доказом не є: у main його може не бути;
        # і merge-коміт мусить бути в графі main — інакше після force-push
        # `main` PR лишається MERGED, а вмісту в main немає.
        absorbed = "pr-merged-sha"
    elif branch.get("unique_patches") == 0:
        absorbed = "patch-absorbed"

    if base["open_prs"]:
        return verdict("keep", None, f"open PR {numbers_of(base['open_prs'])}")

    if name.startswith(RELEASE_PREFIX):
        # Релізну лінію видаляємо лише з тегом v* — жодна непроанонсована не зникає.
        if absorbed is None:
            return verdict("keep", None, "release branch with unique commits")
        if not branch.get("tagged_release"):
            return verdict("keep", None, "release branch without a v* tag")
        return verdict(
            "delete", absorbed,
            f"absorbed into main ({RULE_TEXT[absorbed]}), tag {branch['tagged_release']}",
        )

    if absorbed is None:
        other_lines = [p for p in base["merged_prs"] if p not in base["merged_into_main"]]
        if base["merged_into_main"]:
            return verdict(
                "keep", None,
                f"PR {numbers_of(base['merged_into_main'])} merged into main, but the branch kept moving",
            )
        if other_lines:
            lines = ", ".join(
                f"#{p['number']} → {p.get('baseRefName') or '?'}" for p in other_lines
            )
            return verdict(
                "keep", None,
                f"PR merged outside main ({lines}), those changes are not in main",
            )
        if base["closed_prs"]:
            return verdict("keep", None, "PR closed without merging")
        return verdict("keep", None, "has unique commits, no PR")

    return verdict("delete", absorbed, f"absorbed into main ({RULE_TEXT[absorbed]})")


def decide_all(facts, *, protect=(), now=None):
    main = facts.get("main", DEFAULT_MAIN)
    return [
        decide(branch, protect=protect, main=main, now=now)
        for branch in facts["branches"]
    ]


# --------------------------------------------------------------------------- #
# cleanup
# --------------------------------------------------------------------------- #


def delete_branch(name, sha):
    """Видалити рівно ту ревізію, яку доведено поглинутою.

    `--force-with-lease` пінить ref на доведений sha: якщо гілка рушила після
    збору фактів, видалення відмовляє, а не зносить нову роботу.

    Спершу пробуємо `origin` (у CI це HTTPS із токеном, і він працює). Якщо не
    вдався — та сама дія по HTTPS із токеном `gh`: SSH цієї машини зламаний
    (власник `/etc/ssh/ssh_config.d/20-omarchy-keepalive.conf`), і без цього
    документована ручна команда не могла б видалити нічого.
    """
    lease = f"--force-with-lease=refs/heads/{name}:{sha}"
    result = git("push", lease, "origin", "--delete", name, check=False)
    if result.returncode == 0:
        return True, ""
    slug = repo_slug()
    if slug:
        fallback = run(
            [
                "git", "-c", "credential.helper=!gh auth git-credential",
                "push", lease, f"https://github.com/{slug}.git", "--delete", name,
            ],
            check=False,
        )
        if fallback.returncode == 0:
            return True, ""
        result = fallback
    return False, last_line(result)


def render_cleanup_summary(decisions, applied, dry_run, refused=None):
    deleted = [d for d in decisions if d["action"] == "delete"]
    kept = [d for d in decisions if d["action"] == "keep"]
    lines = ["## Порядок у гілках — чистка", ""]
    if refused:
        # Відмова — це не «видалено 0 із N»: жодного рішення не виконували.
        lines += [f"**Нічого не видалено.** {refused}", ""]
        lines.append(f"Поглинутих гілок, які лишилися: {len(deleted)}.")
        lines += ["", f"Лишено: {len(kept)}.", ""]
        return "\n".join(lines) + "\n"
    if dry_run:
        lines.append(f"Сухий прогін. Поглинутих гілок: {len(deleted)}.")
    else:
        ok = [a for a in applied if a["ok"]]
        bad = [a for a in applied if not a["ok"]]
        lines.append(f"Видалено: {len(ok)} з {len(deleted)}.")
        for item in bad:
            lines.append(f"- `{item['name']}` — не вдалося видалити: {item['error']}")
    lines.append("")
    if deleted:
        verb = "буде видалено" if dry_run else "видалено"
        for item in deleted:
            lines.append(f"- `{item['name']}` — {verb}: {item['reason']}")
    else:
        lines.append("Поглинутих гілок немає.")
    lines += ["", f"Лишено: {len(kept)}.", ""]
    reasons = {}
    for item in kept:
        reasons[item["reason"]] = reasons.get(item["reason"], 0) + 1
    for reason, count in sorted(reasons.items(), key=lambda pair: -pair[1]):
        lines.append(f"- {reason}: {count}")
    return "\n".join(lines) + "\n"


def cmd_cleanup(args):
    if getattr(args, "fetch", False):
        fetch_origin()
    facts = load_facts(args, collect_facts)
    decisions = decide_all(facts, protect=args.protect)
    to_delete = [d for d in decisions if d["action"] == "delete"]

    applied = []
    refused = None
    if args.apply and not facts.get("pr_data"):
        # Без даних про PR зникає захист «відкритий PR не чіпаємо», а воркфлоу
        # обіцяє його як «ніколи». Тому краще не видалити нічого й сказати це
        # вголос, ніж видалити наосліп.
        refused = (
            "No PR data (neither gh nor the GitHub API) — the cleanup deletes nothing, "
            "because without it a branch with an open PR is indistinguishable from an abandoned one."
        )
        print(refused, file=sys.stderr)
    elif args.apply:
        for item in to_delete:
            ok, error = delete_branch(item["name"], item["sha"])
            applied.append({"name": item["name"], "ok": ok, "error": error})
            state = "видалено" if ok else f"НЕ видалено ({error})"
            print(f"{state}: {item['name']}", file=sys.stderr)

    summary = render_cleanup_summary(decisions, applied, dry_run=not args.apply, refused=refused)
    print(summary)
    if args.json:
        dump_json(
            args.json,
            {
                "dry_run": not args.apply,
                "refused": refused,
                "decisions": decisions,
                "applied": applied,
            },
        )
    if refused:
        return 2
    failures = [item for item in applied if not item["ok"]]
    if failures:
        print(f"Не вдалося видалити: {len(failures)}", file=sys.stderr)
        return 1
    return 0


# --------------------------------------------------------------------------- #
# report
# --------------------------------------------------------------------------- #


def branch_label(item):
    return f"`{item['name']}`"


def age_text(days):
    if days is None:
        return "unknown"
    return f"{days} day" if days == 1 else f"{days} days"


def deterministic_actions(decisions, stale_days):
    lines = []
    for item in decisions:
        if item["action"] != "keep" or item["protected"]:
            continue
        stale = item["age_days"] is not None and item["age_days"] >= stale_days
        if item["release"]:
            # Релізну лінію тримає тег v*, а не PR: радити їй PR безглуздо.
            if stale and not item["open_prs"]:
                lines.append(
                    f"- {branch_label(item)} — a release line without a v* tag, so the cleanup does not touch it. "
                    "Either a tag or a deliberate closing."
                )
            continue
        if not item["prs"] and stale:
            lines.append(
                f"- {branch_label(item)} — no PR, last commit {age_text(item['age_days'])} ago. "
                "Open a PR or delete the branch."
            )
        elif item["merged_prs"] and not item["open_prs"]:
            lines.append(
                f"- {branch_label(item)} — {item['reason']}. "
                "Check whether work was lost."
            )
    stale_drafts = [d for d in decisions if d.get("stale_draft")]
    for item in stale_drafts:
        numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
        lines.append(
            f"- PR {numbers} ({item['name']}) — draft with no movement for {age_text(item.get('pr_stale_days'))}. "
            "Update or close."
        )
    stale_open = [d for d in decisions if d.get("stale_open") and not d.get("stale_draft")]
    for item in stale_open:
        numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
        lines.append(
            f"- PR {numbers} ({item['name']}) — no movement for {age_text(item.get('pr_stale_days'))}. "
            "Push it through or close."
        )
    return lines


def with_pr_freshness(decisions, stale_days, now):
    """Дописує до рішень свіжість PR — потрібна лише звіту."""
    for item in decisions:
        opened = item["open_prs"]
        if opened:
            stamps = [parse_iso(p.get("updatedAt")) for p in opened]
            stamps = [s for s in stamps if s is not None]
            # Рух — це остання активність: гілка з одним старим і одним свіжим PR
            # не застійна.
            item["pr_stale_days"] = age_days(max(stamps), now) if stamps else None
        else:
            item["pr_stale_days"] = None
        stale_pr = item["pr_stale_days"] is not None and item["pr_stale_days"] >= stale_days
        item["stale_draft"] = bool(stale_pr and opened and all(p["isDraft"] for p in opened))
        item["stale_open"] = bool(stale_pr and opened and any(not p["isDraft"] for p in opened))
    return decisions


def render_report(facts, decisions, deleted_payload, *, stale_days, narrative, now,
                  run_url=None, narrative_note="the LLM narrative was not used"):
    branches = decisions
    lines = [
        ISSUE_MARKER,
        "# " + ISSUE_TITLE,
        "",
        f"Updated: {now.strftime('%Y-%m-%d %H:%M')} UTC · branches on origin: {len(branches)} "
        f"· of them with a PR: {sum(1 for b in branches if b['prs'])}",
        "",
    ]
    if not facts.get("pr_data"):
        lines += ["> No PR data (gh unavailable) — the match against PRs is incomplete.", ""]

    dry_run = (deleted_payload or {}).get("dry_run")
    applied = (deleted_payload or {}).get("applied") or []
    refused = (deleted_payload or {}).get("refused")
    deletions = [d for d in branches if d["action"] == "delete"]
    lines.append("## What was done tonight")
    lines.append("")
    if deleted_payload is None:
        lines.append(
            "The cleanup did not run in this pass, or it failed — see the run log."
        )
    elif refused:
        lines.append(f"Cleanup was postponed: {refused}")
    elif dry_run:
        if deletions:
            lines.append("Dry run. Would delete:")
            lines += [f"- {branch_label(d)} — {d['reason']}" for d in deletions]
        else:
            lines.append("Dry run: no absorbed branches.")
    else:
        ok = [a for a in applied if a["ok"]]
        ok_names = {a["name"] for a in ok}
        if ok:
            lines += [
                f"- {branch_label(d)} — deleted ({d['reason']})"
                for d in deletions
                if d["name"] in ok_names
            ]
        else:
            lines.append("No absorbed branches — nothing to delete.")
        for item in applied:
            if not item["ok"]:
                lines.append(f"- {branch_label(item)} — could not be deleted: {item['error']}")
    lines.append("")

    no_pr = [b for b in branches if not b["prs"]]
    lines += ["## Branches without a PR", ""]
    if no_pr:
        lines += ["| branch | age | last commit |", "|---|---|---|"]
        for item in sorted(no_pr, key=lambda b: -(b["age_days"] or 0))[:20]:
            lines.append(
                f"| `{item['name']}` | {age_text(item['age_days'])} | {item['tip_subject'][:70]} |"
            )
        if len(no_pr) > 20:
            lines.append(f"\n…and {len(no_pr) - 20} more.")
    else:
        lines.append("None.")
    lines.append("")

    leftover = [
        b for b in branches
        if b["merged_prs"] and b["action"] == "keep" and not b["open_prs"]
    ]
    lines += ["## Merged PRs whose branches remain", ""]
    if leftover:
        for item in leftover:
            numbers = ", ".join(
                f"#{p['number']} → {p.get('baseRefName') or '?'}" for p in item["merged_prs"]
            )
            lines.append(f"- {branch_label(item)} — PR {numbers}: {item['reason']}.")
    else:
        lines.append("None.")
    lines.append("")

    lines += [f"## Drafts with no movement (≥ {stale_days} days)", ""]
    stale_drafts = [b for b in branches if b.get("stale_draft")]
    if stale_drafts:
        for item in stale_drafts:
            numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
            titles = " / ".join(p["title"][:60] for p in item["open_prs"])
            lines.append(
                f"- {numbers} {branch_label(item)} — {age_text(item.get('pr_stale_days'))}: {titles}"
            )
    else:
        lines.append("None.")
    lines.append("")

    lines += [f"## Open PRs with no movement (≥ {stale_days} days)", ""]
    stale_open = [b for b in branches if b.get("stale_open")]
    if stale_open:
        for item in stale_open:
            numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
            titles = " / ".join(p["title"][:60] for p in item["open_prs"])
            lines.append(
                f"- {numbers} {branch_label(item)} — {age_text(item.get('pr_stale_days'))}: {titles}"
            )
    else:
        lines.append("None.")
    lines.append("")

    lines += ["## What is worth doing", ""]
    if narrative:
        lines += [narrative.strip(), ""]
    else:
        fallback = deterministic_actions(branches, stale_days)
        if fallback:
            lines += fallback
        else:
            lines.append("Everything is within the rules: nothing is stuck.")
        lines.append("")
        lines.append(f"_({narrative_note} — this is a deterministic list)_")
    lines += [
        "",
        "---",
        "Rules and thresholds: `docs/runbooks/branch-hygiene.md`. "
        "Only branches whose content is already proven to be in `main` are deleted.",
    ]
    if run_url:
        lines.append(f"Run: {run_url}")
    return "\n".join(lines) + "\n"


def llm_narrative(payload, token, url, model, session, timeout):
    request_body = json.dumps(
        {
            "model": model,
            "messages": [
                {"role": "system", "content": LLM_SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            "max_tokens": LLM_MAX_TOKENS,
            "temperature": 0.2,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        url.rstrip("/") + "/chat/completions",
        data=request_body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "x-opencode-session": session,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
    except (urllib.error.URLError, OSError, KeyError, ValueError, IndexError) as exc:
        print(f"LLM недоступний: {exc}", file=sys.stderr)
        return None
    return content.strip() if isinstance(content, str) and content.strip() else None


def find_issue():
    result = run(
        ["gh", "issue", "list", "--state", "open", "--limit", "50",
         "--search", f"in:title {ISSUE_TITLE}", "--json", "number,title,body"],
        check=False,
    )
    if result.returncode != 0:
        return None
    try:
        issues = json.loads(result.stdout)
    except ValueError:
        return None
    for issue in issues:
        if issue.get("title") == ISSUE_TITLE or ISSUE_MARKER in (issue.get("body") or ""):
            return issue["number"]
    return None


def ensure_label():
    run(
        ["gh", "label", "create", ISSUE_LABEL, "--force", "--color", "5319e7",
         "--description", "Nightly report on branch hygiene"],
        check=False,
    )


def upsert_issue(body):
    ensure_label()
    number = find_issue()
    if number:
        result = run(["gh", "issue", "edit", str(number), "--body-file", "-"],
                     input_text=body, check=False)
        if result.returncode != 0:
            raise SystemExit(f"gh issue edit #{number} → {result.stderr.strip()}")
        print(f"Оновлено issue #{number}", file=sys.stderr)
        return number
    result = run(
        ["gh", "issue", "create", "--title", ISSUE_TITLE, "--body-file", "-",
         "--label", ISSUE_LABEL],
        input_text=body, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"gh issue create → {result.stderr.strip()}")
    match = re.search(r"(\d+)\s*$", result.stdout.strip())
    number = int(match.group(1)) if match else None
    print(f"Створено issue {result.stdout.strip()}", file=sys.stderr)
    return number


def cmd_report(args):
    facts = load_facts(args, collect_facts)
    now = datetime.now(timezone.utc)
    decisions = with_pr_freshness(
        decide_all(facts, protect=args.protect, now=now), args.stale_days, now
    )

    deleted_payload = None
    if args.deleted:
        deleted_payload = json.loads(Path(args.deleted).read_text(encoding="utf-8"))

    narrative = None
    narrative_note = "the LLM narrative was not used"
    token = (
        args.llm_token
        or os.environ.get("BRANCH_HYGIENE_LLM_TOKEN")
        or os.environ.get("OCR_LLM_TOKEN")
    )
    if args.no_llm:
        narrative_note = "the LLM narrative is disabled"
    elif not token:
        narrative_note = "no LLM token"
        print("LLM-токена немає — звіт буде детермінований", file=sys.stderr)
    else:
        payload = {
            "branches": [
                {
                    "name": d["name"],
                    "age_days": d["age_days"],
                    "decision": d["action"],
                    "reason": d["reason"],
                    "stale_pr_days": d["pr_stale_days"],
                    "draft": bool(d["open_prs"])
                    and all(p["isDraft"] for p in d["open_prs"]),
                    "last_commit": d["tip_subject"][:120],
                    "prs": [
                        {
                            "number": p["number"],
                            "state": p["state"],
                            "draft": p["isDraft"],
                            "title": p["title"][:120],
                        }
                        for p in d["prs"]
                    ],
                }
                for d in decisions
            ],
            "deleted_tonight": [d["name"] for d in decisions if d["action"] == "delete"],
        }
        narrative = llm_narrative(
            payload, token, args.llm_url, args.llm_model,
            args.llm_session, args.llm_timeout,
        )
        if narrative is None:
            narrative_note = "the LLM did not answer"

    body = render_report(
        facts, decisions, deleted_payload,
        stale_days=args.stale_days, narrative=narrative,
        now=now, run_url=args.run_url, narrative_note=narrative_note,
    )
    if args.upsert:
        upsert_issue(body)
    # Тіло друкуємо завжди: у CI воно стає summary прогону навіть тоді, коли
    # issue вже оновлено.
    print(body)
    return 0


# --------------------------------------------------------------------------- #
# Локальне дерево
# --------------------------------------------------------------------------- #

FRESH_HOURS = 6
LOCAL_REPORT_NAME = "branch-hygiene-local.md"


def git_common_dir():
    return Path(git("rev-parse", "--path-format=absolute", "--git-common-dir").stdout.strip())


def claim_records(common_dir):
    """Реєстр резервувань у спільному Git-каталозі (той самий, що читає координатор)."""
    records = []
    for path in sorted((common_dir / "agent-work-claims").glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        record.setdefault("issue", path.stem)
        records.append(record)
    return records


def claimed_by(records, name, worktree_path=None):
    """Які резервування тримають цю гілку чи worktree.

    Резервування не має строку завершення, тож зайвий пропуск дешевший за
    зачеплену чужу роботу: номер тікета шукаємо ще й у назві гілки.
    """
    found = []
    for record in records:
        issue = str(record.get("issue") or "")
        if record.get("branch") == name:
            found.append(issue or "?")
            continue
        if worktree_path and str(record.get("worktree") or "") == str(worktree_path):
            found.append(issue or "?")
            continue
        haystack = name
        if worktree_path:
            # Смуги цього репозиторію звуться `w<номер тікета>`, і в назві гілки
            # номера може не бути взагалі (`feat/branch-hygiene`).
            haystack = f"{name}\n{worktree_path}"
        if issue and re.search(r"(?:^|[^0-9])0*%s(?![0-9])" % re.escape(issue), haystack):
            found.append(issue)
    return sorted(set(found))


def file_mtime(path):
    try:
        return datetime.fromtimestamp(Path(path).stat().st_mtime, tz=timezone.utc)
    except OSError:
        return None


def collect_worktrees(common_dir, root, now=None):
    """worktree зі станом: бруд, свіжість, головне/поточне дерево, межі репо."""
    now = now or datetime.now(timezone.utc)
    entries = []
    current = {}

    def flush():
        if current.get("path"):
            entries.append(dict(current))
        current.clear()

    for line in git("worktree", "list", "--porcelain").stdout.splitlines():
        if not line.strip():
            flush()
            continue
        key, _, value = line.partition(" ")
        if key == "worktree":
            current["path"] = value
        elif key == "branch":
            current["branch"] = (
                value[len("refs/heads/"):] if value.startswith("refs/heads/") else value
            )
        elif key == "detached":
            current["detached"] = True
        elif key == "locked":
            current["locked"] = True
        elif key == "prunable":
            current["prunable"] = True
    flush()

    main_path = Path(entries[0]["path"]) if entries else root
    worktrees = []
    for index, entry in enumerate(entries):
        path = Path(entry["path"])
        gitdir = Path(
            git("rev-parse", "--absolute-git-dir", cwd=path, check=False).stdout.strip() or path
        )
        branch = entry.get("branch")
        stamps = [file_mtime(gitdir / name) for name in ("index", "HEAD")]
        if branch:
            stamps.append(file_mtime(common_dir / "logs" / "refs" / "heads" / branch))
        stamps = [stamp for stamp in stamps if stamp]
        touched = max(stamps) if stamps else None
        if path.is_dir():
            status = run(["git", "status", "--porcelain"], check=False, cwd=path)
            rows = [row for row in status.stdout.splitlines() if row.strip()]
            dirty = len([row for row in rows if not row.startswith("!!")])
            kept_ignored = run(
                ["git", "status", "--porcelain", "--ignored=matching"],
                check=False, cwd=path,
            )
            ignored = [
                row[3:].strip() for row in kept_ignored.stdout.splitlines()
                if row.startswith("!!")
            ]
        else:
            # Каталогу немає — це prunable-запис, а не робоче дерево.
            dirty = 0
            ignored = []
        blocking = [item for item in ignored if blocks_removal(path, item)]
        worktrees.append(
            {
                "path": str(path),
                "branch": branch,
                "detached": bool(entry.get("detached")),
                "locked": bool(entry.get("locked")),
                "prunable": bool(entry.get("prunable")),
                "main": index == 0,
                "current": _within(path, root) and _within(root, path),
                "external": not _within(path, main_path),
                "dirty": dirty,
                "ignored": ignored,
                "ignored_blocking": blocking,
                "touched_at": touched.isoformat() if touched else None,
                "fresh_hours": (
                    round((now - touched).total_seconds() / 3600, 1) if touched else None
                ),
            }
        )
    return worktrees


# Відходи збірки й кеші інструментів: їхня втрата нічого не коштує. Усе інше
# ігнороване — чужий стан (конфіг, нотатки, журнал мутацій), і `worktree remove`
# без `--force` зносить його разом із деревом, бо `git status --porcelain` його
# не бачить.
DISPOSABLE_IGNORED = (
    ".gradle/", ".kotlin/", "build/", "node_modules/", "__pycache__/",
    ".mutations/", ".cache-tmp/", ".pytest_cache/", ".ruff_cache/",
)


def blocks_removal(path, ignored):
    """Чи блокує цей ігнорований шлях видалення worktree."""
    name = ignored.rstrip("/")
    if ignored.endswith("/") or (path / name).is_dir():
        if ignored in DISPOSABLE_IGNORED or name + "/" in DISPOSABLE_IGNORED:
            return False
        if any(part + "/" in DISPOSABLE_IGNORED for part in Path(name).parts):
            return False
        target = path / name
        try:
            # Порожня тека не тримає нічого — втрачати нічого.
            return any(target.iterdir())
        except OSError:
            return True
    if name.endswith(".pyc") or name.endswith(".class"):
        return False
    return True


def _within(path, parent):
    try:
        return path.resolve().is_relative_to(parent.resolve())
    except OSError:
        return False


def collect_local_facts(main=DEFAULT_MAIN, use_gh=True, pr_facts_path=None, now=None, slug=None):
    now, main_sha, raw_prs, pr_source, prs_by_branch = collection_context(
        main, use_gh, pr_facts_path, slug, now
    )

    common_dir = git_common_dir()
    root = Path(git("rev-parse", "--show-toplevel").stdout.strip())
    worktrees = collect_worktrees(common_dir, root, now)
    worktree_by_branch = {
        item["branch"]: item for item in worktrees if item.get("branch") and not item["detached"]
    }

    branches = []
    for line in git("for-each-ref", "refs/heads", "--format=%(refname:short)\t%(objectname)").stdout.splitlines():
        name, _, sha = line.partition("\t")
        if not name or not sha:
            continue
        facts = branch_facts(name, sha, main_sha, prs_by_branch, main)
        reflog = file_mtime(common_dir / "logs" / "refs" / "heads" / name)
        facts["reflog_at"] = reflog.isoformat() if reflog else None
        facts["worktree"] = worktree_by_branch.get(name)
        branches.append(facts)

    branches.sort(key=lambda b: b["name"])
    return {
        "collected_at": now.isoformat(),
        "main": main,
        "main_sha": main_sha,
        "pr_source": pr_source,
        "pr_data": raw_prs is not None,
        "pr_count": len(raw_prs or []),
        "common_dir": str(common_dir),
        "root": str(root),
        "claims": claim_records(common_dir),
        "worktrees": worktrees,
        "branches": branches,
    }


def decide_local(branch, *, protect=(), main=DEFAULT_MAIN, claims=(), fresh_hours=FRESH_HOURS,
                 now=None):
    """Локальне рішення: доказ той самий, але додаються локальні запобіжники.

    Право на видалення дає лише доведене поглинання (те саме `decide`);
    резервування, свіжість, бруд, головне й поточне дерево та межі репозиторію
    його забирають.
    """
    now = now or datetime.now(timezone.utc)
    base = decide(branch, protect=protect, main=main, now=now)
    worktree = branch.get("worktree")
    if worktree and worktree.get("prunable"):
        # Каталог уже зник, лишився адмінзапис: він не тримає гілку, а `prune`
        # прибере його після прогону.
        worktree = None
    base["worktree"] = worktree
    base["proof"] = base["rule"]
    base["claimed_by"] = claimed_by(claims, branch["name"], (worktree or {}).get("path"))

    touched = (worktree or {}).get("touched_at") or branch.get("reflog_at")
    stamp = parse_iso(touched)
    base["touched_at"] = touched
    base["fresh_hours"] = None if stamp is None else round((now - stamp).total_seconds() / 3600, 1)

    if base["action"] == "keep":
        return base

    def keep(reason):
        base["action"] = "keep"
        base["reason"] = reason
        return base

    if base["claimed_by"]:
        return keep("резервування #%s — там чужа робота" % ", #".join(base["claimed_by"]))
    if base["fresh_hours"] is not None and base["fresh_hours"] < fresh_hours:
        return keep(
            "торкалися %.1f год тому (< %d год) — наступної ночі"
            % (base["fresh_hours"], fresh_hours)
        )
    if worktree:
        if worktree["main"]:
            return keep("головне дерево репозиторію")
        if worktree["current"]:
            return keep("поточне дерево — з нього й біжить сторож")
        if worktree["locked"]:
            return keep("worktree залочено")
        if worktree["external"]:
            return keep("worktree поза репозиторієм — прибирається руками")
        if worktree["dirty"]:
            return keep("worktree брудний (%d змін) — спершу розібрати їх" % worktree["dirty"])
        if worktree.get("ignored_blocking"):
            return keep(
                "worktree має ігнорований вміст (%s) — git знесе його разом із деревом"
                % ", ".join(worktree["ignored_blocking"][:3])
            )
    return base


def fetch_origin(slug=None):
    """Свіжий origin по HTTPS: публічний репозиторій не потребує SSH і токена."""
    slug = slug or repo_slug()
    url = f"https://github.com/{slug}.git" if slug else "origin"
    result = git(
        "fetch", "--prune", "--force", url, "refs/heads/*:refs/remotes/origin/*",
        check=False,
    )
    if result.returncode != 0:
        print(f"fetch не вдався ({url}): {last_line(result)}", file=sys.stderr)
        return False
    return True


def remove_local(item):
    """Прибрати worktree (без --force) і по ньому гілку. Порядок саме такий."""
    record = {"name": item["name"], "removed_worktree": False, "removed_branch": False,
              "error": ""}
    worktree = item.get("worktree")
    if worktree and not worktree.get("prunable"):
        result = git("worktree", "remove", worktree["path"], check=False)
        if result.returncode != 0:
            record["error"] = f"worktree не прибрано: {last_line(result)}"
            return record
        record["removed_worktree"] = True
    result = git("branch", "-D", item["name"], check=False)
    if result.returncode != 0:
        record["error"] = f"гілку не видалено: {last_line(result)}"
        return record
    record["removed_branch"] = True
    return record


def render_local_report(facts, decisions, applied, *, dry_run, now, fresh_hours,
                        stale_days=DEFAULT_STALE_DAYS, refused=None):
    removed = [item for item in applied if item["removed_branch"]]
    failed = [item for item in applied if item["error"]]
    to_delete = [d for d in decisions if d["action"] == "delete"]
    kept = [d for d in decisions if d["action"] == "keep"]
    by_name = {d["name"]: d for d in decisions}

    lines = [
        "# Порядок у гілках — локальне дерево",
        "",
        "Оновлено: %s UTC · гілок: %d · worktree: %d · PR у вікні: %d"
        % (now.strftime("%Y-%m-%d %H:%M"), len(decisions),
           len(facts.get("worktrees") or []), facts.get("pr_count") or 0),
        "",
    ]
    if refused:
        lines += [f"**Нічого не прибрано.** {refused}", ""]
    if not facts.get("pr_data"):
        lines += [
            "> Даних про PR немає (ні gh, ні GitHub API) — судили лише за гітом, "
            "і гілка з відкритим PR могла не потрапити під захист.",
            "",
        ]

    lines += ["## Що зроблено", ""]
    if dry_run:
        if to_delete:
            lines.append("Сухий прогін. Буде прибрано:")
            for item in to_delete:
                lines.append(f"- `{item['name']}` — {item['reason']}")
        else:
            lines.append("Сухий прогін: прибирати нічого.")
    elif to_delete:
        ok_names = {item["name"] for item in removed}
        if ok_names:
            for item in to_delete:
                if item["name"] in ok_names:
                    worktree = " разом із worktree" if item.get("worktree") else ""
                    lines.append(f"- `{item['name']}` — видалено{worktree} ({item['reason']})")
        else:
            lines.append("Нічого не прибрано.")
        for item in failed:
            lines.append(f"- `{item['name']}` — не вдалося: {item['error']}")
    else:
        lines.append("Нічого не прибрано.")
    lines.append("")

    orphans = [
        d for d in kept
        if not d["protected"] and not d["release"] and d["action"] == "keep"
        and d["proof"] is None and not d["open_prs"]
    ]
    lines += ["## Робота, якої немає в main", ""]
    if orphans:
        for item in sorted(orphans, key=lambda d: -(d["age_days"] or 0)):
            reason = item["reason"]
            branch_name = item["name"]
            if item["merged_into_main"] and not item["open_prs"] and item["proof"] is None:
                hint = ("глянь, що не ввійшло: `git log origin/main..%s` — і або "
                        "перенеси в main, або `git branch -D %s`" % (branch_name, branch_name))
            elif item["merged_prs"]:
                hint = "PR злито в іншу лінію; у main цих змін немає — перенеси їх окремо"
            elif item["closed_prs"]:
                hint = ("або відкрий PR заново, або `git branch -D %s`" % branch_name)
            else:
                hint = ("`git push -u origin %s`, щоб відкрити PR, або "
                        "`git branch -D %s`" % (branch_name, branch_name))
            lines.append(
                "- `%s` (%s, останній коміт %s тому, %s) — %s"
                % (item["name"], item["tip_subject"][:60] or "—", age_text(item["age_days"]),
                   item["tip_author"] or "невідомий автор", reason)
            )
            lines.append(f"  → {hint}")
    else:
        lines.append("Немає.")
    lines.append("")

    held_ignored = [
        w for w in (facts.get("worktrees") or [])
        if w.get("ignored_blocking") and not w["main"] and not w["external"]
    ]
    lines += ["## Ігнорований вміст у worktree", ""]
    if held_ignored:
        for item in held_ignored:
            lines.append(
                "- `%s` (%s) — не порожнє: %s. `git worktree remove` знесе це разом "
                "із деревом, тож сторож не чіпає"
                % (item["path"], item["branch"] or "detached",
                   ", ".join(item["ignored_blocking"][:4]))
            )
    else:
        lines.append("Немає.")
    lines.append("")

    detached = [
        w for w in (facts.get("worktrees") or [])
        if w["detached"] and not w["main"] and not w["prunable"]
    ]
    lines += ["## Відірвані worktree (detached HEAD)", ""]
    if detached:
        for item in detached:
            lines.append(
                "- `%s` — гілки немає, тож довести поглинання нічим; прибирається руками"
                % item["path"]
            )
    else:
        lines.append("Немає.")
    lines.append("")

    dirty = [w for w in (facts.get("worktrees") or []) if w["dirty"] and not w["main"]]
    lines += ["## Брудні worktree", ""]
    if dirty:
        for item in dirty:
            lines.append(
                "- `%s` (%s, %d змін) — `git -C %s status`, потім `git worktree remove %s`"
                % (item["path"], item["branch"] or "detached", item["dirty"],
                   item["path"], item["path"])
            )
    else:
        lines.append("Немає.")
    lines.append("")

    held = [d for d in kept if d["action"] == "keep" and d["claimed_by"]]
    fresh = [
        d for d in kept
        if d["action"] == "keep" and d["proof"] and not d["claimed_by"]
        and d["fresh_hours"] is not None and d["fresh_hours"] < fresh_hours
    ]
    external = [
        d for d in kept
        if d["action"] == "keep" and d["proof"] and (d.get("worktree") or {}).get("external")
    ]
    lines += ["## Поглинуте, але лишене", ""]
    if held or fresh or external:
        for item in held:
            lines.append(f"- `{item['name']}` — {item['reason']}")
        for item in fresh:
            lines.append(f"- `{item['name']}` — {item['reason']}")
        for item in external:
            lines.append(f"- `{item['name']}` — {item['reason']}")
    else:
        lines.append("Немає.")
    lines.append("")

    lines += ["## Лишено з інших причин", ""]
    other = [
        d for d in kept
        if d not in held and d not in fresh and d not in external and d not in orphans
    ]
    reasons = {}
    for item in other:
        reasons[item["reason"]] = reasons.get(item["reason"], 0) + 1
    if reasons:
        for reason, count in sorted(reasons.items(), key=lambda pair: -pair[1]):
            lines.append(f"- {reason}: {count}")
    else:
        lines.append("Немає.")
    lines += [
        "",
        "---",
        "Правила й межі: `docs/runbooks/branch-hygiene.md`. Локально сторож "
        "прибирає лише доведено поглинуте, чисте й не зарезервоване.",
    ]
    return "\n".join(lines) + "\n"


def cmd_local(args):
    if args.fetch:
        fetch_origin()
    facts = load_facts(args, collect_local_facts)
    now = datetime.now(timezone.utc)
    decisions = [
        decide_local(
            branch, protect=args.protect, main=args.main,
            claims=facts.get("claims") or [], fresh_hours=args.fresh_hours, now=now,
        )
        for branch in facts["branches"]
    ]
    to_delete = [d for d in decisions if d["action"] == "delete"]
    applied = []
    refused = None
    if args.apply and not facts.get("pr_data"):
        refused = (
            "Даних про PR немає (ні gh, ні GitHub API) — локальна чистка нічого не "
            "прибирає, бо без них гілка з відкритим PR не відрізняється від закинутої."
        )
        print(refused, file=sys.stderr)
    elif args.apply:
        # Спершу прибираємо адмінзаписи, чиї каталоги вже зникли: інакше git
        # уважає гілку зайнятою неіснуючим worktree і відмовляється її видаляти.
        run(["git", "worktree", "prune"], check=False)
        for item in to_delete:
            record = remove_local(item)
            applied.append(record)
            state = "прибрано" if record["removed_branch"] else f"НЕ прибрано ({record['error']})"
            print(f"{state}: {item['name']}", file=sys.stderr)

    report = render_local_report(
        facts, decisions, applied,
        dry_run=not args.apply, now=now, fresh_hours=args.fresh_hours, refused=refused,
    )
    print(report)
    if not args.no_out:
        out = Path(args.out) if args.out else Path(facts["common_dir"]) / LOCAL_REPORT_NAME
        out.write_text(report, encoding="utf-8")
        print(f"Звіт: {out}", file=sys.stderr)
    if args.json:
        dump_json(args.json, {"dry_run": not args.apply, "refused": refused,
                              "decisions": decisions, "applied": applied})
    if refused:
        return 2
    if [item for item in applied if item["error"]]:
        print("Частину гілок прибрати не вдалося", file=sys.stderr)
        return 1
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def load_facts(args, collector):
    """Факти з `--facts` або зібрані щойно — різниця лише в збирачі."""
    if getattr(args, "facts", None):
        return json.loads(Path(args.facts).read_text(encoding="utf-8"))
    return collector(
        main=args.main,
        use_gh=not getattr(args, "no_gh", False),
        pr_facts_path=getattr(args, "pr_facts", None),
    )


def add_common(parser):
    parser.add_argument("--main", default=DEFAULT_MAIN, help="ім'я головної гілки на origin")
    parser.add_argument("--facts", help="узяти факти з JSON замість збору")
    parser.add_argument("--no-gh", action="store_true", help="не питати gh про PR")
    parser.add_argument("--pr-facts", help="JSON зі списком PR замість gh")
    parser.add_argument("--protect", action="append", default=[],
                        help="додатковий glob гілок, які не чіпаємо (можна кілька)")


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    collect = commands.add_parser("collect", help="зібрати факти про origin у JSON")
    add_common(collect)
    collect.add_argument("--json", help="куди писати JSON (- для stdout)")

    collect_local = commands.add_parser("collect-local",
                                        help="зібрати факти про локальні гілки й worktree")
    add_common(collect_local)
    collect_local.add_argument("--json", help="куди писати JSON (- для stdout)")

    cleanup = commands.add_parser("cleanup", help="прибрати поглинуті гілки")
    add_common(cleanup)
    cleanup.add_argument("--apply", action="store_true", help="справді видаляти (типово сухий прогін)")
    cleanup.add_argument("--fetch", action="store_true",
                         help="спершу оновити origin по HTTPS (без SSH)")
    cleanup.add_argument("--json", help="куди писати рішення й результат")

    local = commands.add_parser("local", help="порядок у локальних гілках і worktree")
    add_common(local)
    local.add_argument("--apply", action="store_true",
                       help="справді прибирати (типово сухий прогін)")
    local.add_argument("--fetch", action="store_true",
                       help="спершу оновити origin по HTTPS (без SSH)")
    local.add_argument("--fresh-hours", type=int, default=FRESH_HOURS,
                       help="що молодше за це — не чіпаємо (типово %d год)" % FRESH_HOURS)
    local.add_argument("--out", help="куди писати звіт (типово <git-common-dir>/%s)"
                       % LOCAL_REPORT_NAME)
    local.add_argument("--no-out", action="store_true", help="не писати звіт у файл")
    local.add_argument("--json", help="куди писати рішення й результат")

    report = commands.add_parser("report", help="скласти звіт")
    add_common(report)
    report.add_argument("--deleted", help="JSON від cleanup")
    report.add_argument("--upsert", action="store_true", help="оновити нічний issue")
    report.add_argument("--no-llm", action="store_true", help="без наративу LLM")
    report.add_argument("--llm-token", help="токен LLM (типово з BRANCH_HYGIENE_LLM_TOKEN або OCR_LLM_TOKEN)")
    report.add_argument("--llm-url", default=os.environ.get("BRANCH_HYGIENE_LLM_URL", DEFAULT_LLM_URL))
    report.add_argument("--llm-model", default=os.environ.get("BRANCH_HYGIENE_LLM_MODEL", DEFAULT_LLM_MODEL))
    report.add_argument("--llm-session", default=DEFAULT_LLM_SESSION)
    report.add_argument("--llm-timeout", type=int, default=120)
    report.add_argument("--stale-days", type=int, default=DEFAULT_STALE_DAYS)
    report.add_argument("--run-url", help="посилання на прогін для підпису звіту")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.command == "collect":
        facts = collect_facts(
            main=args.main,
            use_gh=not args.no_gh,
            pr_facts_path=args.pr_facts,
        )
        if args.json:
            dump_json(args.json, facts)
        else:
            print(json.dumps(facts, ensure_ascii=False, indent=2))
        return 0
    if args.command == "cleanup":
        return cmd_cleanup(args)
    if args.command == "collect-local":
        facts = collect_local_facts(
            main=args.main,
            use_gh=not args.no_gh,
            pr_facts_path=args.pr_facts,
        )
        if args.json:
            dump_json(args.json, facts)
        else:
            print(json.dumps(facts, ensure_ascii=False, indent=2))
        return 0
    if args.command == "local":
        return cmd_local(args)
    if args.command == "report":
        return cmd_report(args)
    raise SystemExit(f"невідома команда: {args.command}")


if __name__ == "__main__":
    sys.exit(main())
