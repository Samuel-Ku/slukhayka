#!/usr/bin/env python3
"""Порядок у гілках: зібрати факти, прибрати поглинуте main, скласти нічний звіт.

Три команди, які викликає нічний воркфлоу (і які можна гнати руками):

    collect  — факти про віддалені гілки та їхні PR (JSON, нічого не змінює)
    cleanup  — рішення про видалення; без --apply це сухий прогін
    report   — markdown-звіт, з наративом LLM, якщо є токен (--upsert оновлює issue)

Правила, пороги й захищені назви — docs/runbooks/branch-hygiene.md.
Скрипт ніколи не видаляє гілку, поки не доведено, що її вміст уже в main:
предок main, злитий PR із тим самим tip, або нуль унікальних патчів.
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

ISSUE_TITLE = "Порядок у гілках — нічний звіт"
ISSUE_LABEL = "branch-hygiene"
ISSUE_MARKER = "<!-- branch-hygiene-report -->"

DEFAULT_LLM_URL = "https://opencode.ai/zen/go/v1"
DEFAULT_LLM_MODEL = "deepseek-v4.1-flash"
DEFAULT_LLM_SESSION = "branch-hygiene-github-actions"
LLM_MAX_TOKENS = 1200

RULE_TEXT = {
    "ancestor": "tip — предок main",
    "pr-merged-sha": "злитий PR із тим самим tip",
    "patch-absorbed": "нуль унікальних патчів щодо main",
}

LLM_SYSTEM_PROMPT = (
    "Ти — сторож порядку в гілках репозиторію «Слухайка». Тобі дають факти про "
    "віддалені гілки та їхні PR. Напиши розділ «Що варто зробити» українською: "
    "3-8 пунктів, кожен — «гілка або PR → дія і причина». Спирайся ЛИШЕ на надані "
    "факти, нічого не вигадуй. Не пропонуй видаляти захищені гілки, релізні гілки "
    "без тега v* або гілки з відкритим PR. Тон — спокійний і сухий, без маркетингу "
    "й канцеляриту (docs/voice.md). Без вступів і без заголовка: одразу пункти."
)


# --------------------------------------------------------------------------- #
# Дрібні помічники
# --------------------------------------------------------------------------- #


def run(cmd, *, check=True, input_text=None):
    result = subprocess.run(
        cmd, capture_output=True, text=True, input=input_text, check=False
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"{' '.join(cmd)} → код {result.returncode}: {result.stderr.strip()}"
        )
    return result


def git(*args, check=True):
    return run(["git", *args], check=check)


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


def fetch_prs(use_gh=True, pr_facts_path=None):
    """Список PR усіх станів. None означає «даних немає» — це не помилка."""
    if pr_facts_path:
        return json.loads(Path(pr_facts_path).read_text(encoding="utf-8"))
    if not use_gh:
        return None
    result = run(
        [
            "gh", "pr", "list", "--state", "all", "--limit", "500",
            "--json",
            "number,headRefName,baseRefName,state,isDraft,title,updatedAt,"
            "mergedAt,headRefOid,url,headRepositoryOwner",
        ],
        check=False,
    )
    if result.returncode != 0:
        print(f"gh pr list недоступний: {result.stderr.strip()}", file=sys.stderr)
        return None
    try:
        return json.loads(result.stdout)
    except ValueError:
        return None


def repo_owner():
    result = run(["gh", "repo", "view", "--json", "nameWithOwner"], check=False)
    if result.returncode != 0:
        return None
    try:
        return json.loads(result.stdout)["nameWithOwner"].split("/")[0]
    except (ValueError, KeyError):
        return None


def normalize_prs(raw, owner):
    prs = []
    for item in raw or []:
        head_owner = (item.get("headRepositoryOwner") or {}).get("login")
        # PR з форка може мати таку саму назву гілки, як локальна: не плутаємо.
        if owner and head_owner and head_owner != owner:
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
                "url": item.get("url"),
            }
        )
    return prs


def collect_facts(main=DEFAULT_MAIN, use_gh=True, pr_facts_path=None, now=None):
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

    raw_prs = fetch_prs(use_gh=use_gh, pr_facts_path=pr_facts_path)
    owner = repo_owner() if (use_gh and pr_facts_path is None) else None
    prs_by_branch = {}
    for pr in normalize_prs(raw_prs, owner):
        prs_by_branch.setdefault(pr["headRefName"], []).append(pr)

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
        tip = git("log", "-1", "--format=%cI\t%an\t%s", sha, check=False).stdout.rstrip("\n").split("\t")
        tip_date = parse_iso(tip[0]) if tip else None
        ancestor = git("merge-base", "--is-ancestor", sha, main_sha, check=False).returncode == 0
        unique_patches = None
        if not ancestor:
            cherry = git("cherry", main_sha, sha, check=False)
            if cherry.returncode == 0:
                unique_patches = sum(1 for row in cherry.stdout.splitlines() if row.startswith("+"))
        tags = git("tag", "--merged", sha, "--list", "v*", check=False).stdout.split()
        branches.append(
            {
                "name": name,
                "sha": sha,
                "ancestor": ancestor,
                "unique_patches": unique_patches,
                "tagged_release": tags[0] if tags else None,
                "tip_date": tip_date.isoformat() if tip_date else None,
                "tip_author": tip[1] if len(tip) > 1 else "",
                "tip_subject": tip[2] if len(tip) > 2 else "",
                "prs": prs_by_branch.get(name, []),
            }
        )

    branches.sort(key=lambda b: b["name"])
    return {
        "collected_at": now.isoformat(),
        "main": main,
        "main_sha": main_sha,
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
        return verdict("keep", None, "захищена назва")

    absorbed = None
    if branch["ancestor"]:
        absorbed = "ancestor"
    elif any(p.get("headRefOid") == branch["sha"] for p in base["merged_into_main"]):
        # Злитий у main PR із тим самим tip: гілка не рухалася після злиття,
        # отже не несе нічого, чого немає в main (працює й для squash/rebase).
        # PR, злитий у релізну лінію, доказом не є: у main його може не бути.
        absorbed = "pr-merged-sha"
    elif branch.get("unique_patches") == 0:
        absorbed = "patch-absorbed"

    if base["open_prs"]:
        return verdict("keep", None, f"відкритий PR {numbers_of(base['open_prs'])}")

    if name.startswith(RELEASE_PREFIX):
        # Релізну лінію видаляємо лише з тегом v* — жодна непроанонсована не зникає.
        if absorbed is None:
            return verdict("keep", None, "релізна гілка з унікальними комітами")
        if not branch.get("tagged_release"):
            return verdict("keep", None, "релізна гілка без тега v*")
        return verdict(
            "delete", absorbed,
            f"поглинуто main ({RULE_TEXT[absorbed]}), тег {branch['tagged_release']}",
        )

    if absorbed is None:
        other_lines = [p for p in base["merged_prs"] if p not in base["merged_into_main"]]
        if base["merged_into_main"]:
            return verdict(
                "keep", None,
                f"PR {numbers_of(base['merged_into_main'])} злито в main, але гілка рухалася далі",
            )
        if other_lines:
            lines = ", ".join(
                f"#{p['number']} → {p.get('baseRefName') or '?'}" for p in other_lines
            )
            return verdict("keep", None, f"PR злито не в main ({lines}), у main цих змін немає")
        if base["closed_prs"]:
            return verdict("keep", None, "PR закрито без злиття")
        return verdict("keep", None, "має унікальні коміти, PR немає")

    return verdict("delete", absorbed, f"поглинуто main ({RULE_TEXT[absorbed]})")


def decide_all(facts, *, protect=(), now=None):
    main = facts.get("main", DEFAULT_MAIN)
    return [
        decide(branch, protect=protect, main=main, now=now)
        for branch in facts["branches"]
    ]


# --------------------------------------------------------------------------- #
# cleanup
# --------------------------------------------------------------------------- #


def delete_branch(name):
    result = git("push", "origin", "--delete", name, check=False)
    if result.returncode == 0:
        return True, ""
    output = (result.stderr or result.stdout).strip()
    lines = output.splitlines() if output else []
    return False, lines[-1] if lines else "невідома помилка"


def render_cleanup_summary(decisions, applied, dry_run):
    deleted = [d for d in decisions if d["action"] == "delete"]
    kept = [d for d in decisions if d["action"] == "keep"]
    lines = ["## Порядок у гілках — чистка", ""]
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
    facts = load_facts(args)
    decisions = decide_all(facts, protect=args.protect)
    to_delete = [d for d in decisions if d["action"] == "delete"]

    applied = []
    if args.apply:
        for item in to_delete:
            ok, error = delete_branch(item["name"])
            applied.append({"name": item["name"], "ok": ok, "error": error})
            state = "видалено" if ok else f"НЕ видалено ({error})"
            print(f"{state}: {item['name']}", file=sys.stderr)

    summary = render_cleanup_summary(decisions, applied, dry_run=not args.apply)
    print(summary)
    if args.json:
        dump_json(
            args.json,
            {
                "dry_run": not args.apply,
                "decisions": decisions,
                "applied": applied,
            },
        )
    return 0


# --------------------------------------------------------------------------- #
# report
# --------------------------------------------------------------------------- #


def branch_label(item):
    return f"`{item['name']}`"


def age_text(days):
    if days is None:
        return "невідомо"
    return f"{days} дн."


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
                    f"- {branch_label(item)} — релізна лінія без тега v*, тому чистка її не чіпає. "
                    "Або тег, або свідоме закриття."
                )
            continue
        if not item["prs"] and stale:
            lines.append(
                f"- {branch_label(item)} — PR немає, останній коміт {age_text(item['age_days'])} тому. "
                "Відкрити PR або видалити гілку."
            )
        elif item["merged_prs"] and not item["open_prs"]:
            lines.append(
                f"- {branch_label(item)} — {item['reason']}. "
                "Перевірити, чи не загубилася робота."
            )
    stale_drafts = [d for d in decisions if d.get("stale_draft")]
    for item in stale_drafts:
        numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
        lines.append(
            f"- PR {numbers} ({item['name']}) — драфт без руху {age_text(item.get('pr_stale_days'))}. "
            "Оновити або закрити."
        )
    stale_open = [d for d in decisions if d.get("stale_open") and not d.get("stale_draft")]
    for item in stale_open:
        numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
        lines.append(
            f"- PR {numbers} ({item['name']}) — без руху {age_text(item.get('pr_stale_days'))}. "
            "Дотиснути або закрити."
        )
    return lines


def with_pr_freshness(decisions, stale_days, now):
    """Дописує до рішень свіжість PR — потрібна лише звіту."""
    for item in decisions:
        opened = item["open_prs"]
        if opened:
            stamps = [parse_iso(p.get("updatedAt")) for p in opened]
            stamps = [s for s in stamps if s is not None]
            item["pr_stale_days"] = age_days(min(stamps), now) if stamps else None
        else:
            item["pr_stale_days"] = None
        stale_pr = item["pr_stale_days"] is not None and item["pr_stale_days"] >= stale_days
        item["stale_draft"] = bool(stale_pr and opened and all(p["isDraft"] for p in opened))
        item["stale_open"] = bool(stale_pr and opened and any(not p["isDraft"] for p in opened))
    return decisions


def render_report(facts, decisions, deleted_payload, *, stale_days, narrative, now,
                  run_url=None, narrative_note="наратив LLM не використано"):
    branches = decisions
    lines = [
        ISSUE_MARKER,
        "# " + ISSUE_TITLE,
        "",
        f"Оновлено: {now.strftime('%Y-%m-%d %H:%M')} UTC · гілок на origin: {len(branches)} "
        f"· з них із PR: {sum(1 for b in branches if b['prs'])}",
        "",
    ]
    if not facts.get("pr_data"):
        lines += ["> Даних про PR немає (gh недоступний) — зіставлення з PR неповне.", ""]

    dry_run = (deleted_payload or {}).get("dry_run")
    applied = (deleted_payload or {}).get("applied") or []
    deletions = [d for d in branches if d["action"] == "delete"]
    lines.append("## Що зроблено цієї ночі")
    lines.append("")
    if deleted_payload is None:
        lines.append(
            "Чистку не запускали в цьому прогоні, або вона впала — дивись лог прогону."
        )
    elif dry_run:
        if deletions:
            lines.append("Сухий прогін. Буде видалено:")
            lines += [f"- {branch_label(d)} — {d['reason']}" for d in deletions]
        else:
            lines.append("Сухий прогін: поглинутих гілок немає.")
    else:
        ok = [a for a in applied if a["ok"]]
        ok_names = {a["name"] for a in ok}
        if ok:
            lines += [
                f"- {branch_label(d)} — видалено ({d['reason']})"
                for d in deletions
                if d["name"] in ok_names
            ]
        else:
            lines.append("Поглинутих гілок не було — видаляти нічого.")
        for item in applied:
            if not item["ok"]:
                lines.append(f"- {branch_label(item)} — видалити не вдалося: {item['error']}")
    lines.append("")

    no_pr = [b for b in branches if not b["prs"]]
    lines += ["## Гілки без PR", ""]
    if no_pr:
        lines += ["| гілка | вік | останній коміт |", "|---|---|---|"]
        for item in sorted(no_pr, key=lambda b: -(b["age_days"] or 0))[:20]:
            lines.append(
                f"| `{item['name']}` | {age_text(item['age_days'])} | {item['tip_subject'][:70]} |"
            )
        if len(no_pr) > 20:
            lines.append(f"\n…і ще {len(no_pr) - 20}.")
    else:
        lines.append("Немає.")
    lines.append("")

    leftover = [
        b for b in branches
        if b["merged_prs"] and b["action"] == "keep" and not b["open_prs"]
    ]
    lines += ["## Злиті PR, чиї гілки лишилися", ""]
    if leftover:
        for item in leftover:
            numbers = ", ".join(
                f"#{p['number']} → {p.get('baseRefName') or '?'}" for p in item["merged_prs"]
            )
            lines.append(f"- {branch_label(item)} — PR {numbers}: {item['reason']}.")
    else:
        lines.append("Немає.")
    lines.append("")

    lines += [f"## Драфти без руху (≥ {stale_days} днів)", ""]
    stale_drafts = [b for b in branches if b.get("stale_draft")]
    if stale_drafts:
        for item in stale_drafts:
            numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
            titles = " / ".join(p["title"][:60] for p in item["open_prs"])
            lines.append(
                f"- {numbers} {branch_label(item)} — {age_text(item.get('pr_stale_days'))}: {titles}"
            )
    else:
        lines.append("Немає.")
    lines.append("")

    lines += [f"## Відкриті PR без руху (≥ {stale_days} днів)", ""]
    stale_open = [b for b in branches if b.get("stale_open")]
    if stale_open:
        for item in stale_open:
            numbers = ", ".join(f"#{p['number']}" for p in item["open_prs"])
            titles = " / ".join(p["title"][:60] for p in item["open_prs"])
            lines.append(
                f"- {numbers} {branch_label(item)} — {age_text(item.get('pr_stale_days'))}: {titles}"
            )
    else:
        lines.append("Немає.")
    lines.append("")

    lines += ["## Що варто зробити", ""]
    if narrative:
        lines += [narrative.strip(), ""]
    else:
        fallback = deterministic_actions(branches, stale_days)
        if fallback:
            lines += fallback
        else:
            lines.append("Усе в межах правил: нічого не застрягло.")
        lines.append("")
        lines.append(f"_({narrative_note} — це детермінований перелік)_")
    lines += [
        "",
        "---",
        "Правила й пороги: `docs/runbooks/branch-hygiene.md`. "
        "Видаляються лише гілки, чий вміст доведено вже в `main`.",
    ]
    if run_url:
        lines.append(f"Прогін: {run_url}")
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
         "--description", "Нічний звіт про порядок у гілках"],
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
    facts = load_facts(args)
    now = datetime.now(timezone.utc)
    decisions = with_pr_freshness(
        decide_all(facts, protect=args.protect, now=now), args.stale_days, now
    )

    deleted_payload = None
    if args.deleted:
        deleted_payload = json.loads(Path(args.deleted).read_text(encoding="utf-8"))

    narrative = None
    narrative_note = "наратив LLM не використано"
    token = (
        args.llm_token
        or os.environ.get("BRANCH_HYGIENE_LLM_TOKEN")
        or os.environ.get("OCR_LLM_TOKEN")
    )
    if args.no_llm:
        narrative_note = "наратив LLM вимкнено"
    elif not token:
        narrative_note = "LLM-токена немає"
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
            narrative_note = "LLM не відповів"

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
# CLI
# --------------------------------------------------------------------------- #


def load_facts(args):
    if getattr(args, "facts", None):
        return json.loads(Path(args.facts).read_text(encoding="utf-8"))
    return collect_facts(
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

    collect = commands.add_parser("collect", help="зібрати факти у JSON")
    add_common(collect)
    collect.add_argument("--json", help="куди писати JSON (- для stdout)")

    cleanup = commands.add_parser("cleanup", help="прибрати поглинуті гілки")
    add_common(cleanup)
    cleanup.add_argument("--apply", action="store_true", help="справді видаляти (типово сухий прогін)")
    cleanup.add_argument("--json", help="куди писати рішення й результат")

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
    if args.command == "report":
        return cmd_report(args)
    raise SystemExit(f"невідома команда: {args.command}")


if __name__ == "__main__":
    sys.exit(main())
