#!/usr/bin/env bash
#
# Мердж-гейт PR: зливає лише те, що має зелений CI і свіжий вердикт огляду.
#
# Навіщо. Доти гейтом була петля «усі чеки не fail» в голові координатора
# (docs/agents/local-environment.md), а `main` не захищений. PR #1215 злили
# з відкритим Spec FAIL: вердикт огляду жив ПРОЗОЮ (коментар у #1205), CI був
# зелений, тож петля його пропустила — і знахідку закривали другим PR #1217
# з другим колом огляду. Машиночитний вердикт (мітка) робить «огляд пройдено»
# частиною процесу, а не умовою, яку хтось тримає в голові.
#
# Що перевіряє, у цьому порядку:
#   1) PR відкритий, не чернетка і не в конфлікті — інакше зливати нічого;
#   2) жоден чек не впав, не скасований і не висить. Порожній список чеків —
#      теж відмова: без доказів вердикту нема на чому стояти;
#   3) на PR стоїть мітка вердикту (типово `review:pass`) — інакше відмова;
#   4) мітка НОВІША за все, що вона засвідчує: за кожен коміт PR
#      (committedDate), за кожен прогін чеків HEAD-ревізії (filter=all, тож
#      видно й перезапущені спроби) і за будь-який force-push гілки PR.
#      Вердикт, старіший за новий коміт, новий прогін чи новий fail, не
#      покриває те, що зливається, — і гейт відмовляє;
#   5) у репозиторії вимкнено auto-delete гілок: увімкнений
#      `delete_branch_on_merge` знищив би гілку PR так само, як колись
#      `gh pr merge --delete-branch` зніс worktree.
#
# Чому мітка, а не `reviewDecision`. Схвалення GitHub-рев'ю («Approve») автор
# PR поставити собі не може, а всі PR цього репозиторію відкриває той самий
# акаунт — тож reviewDecision ніколи не стане APPROVED. Мітку автор поставити
# може. Це процесний сигнал, а не криптографічний доказ, і саме тому п.4
# перевіряє ЧАС мітки, а не саму її наявність.
#
# Хто ставить мітку і коли — docs/agents/work-coordination.md, розділ
# «Вердикт огляду і мердж»: незалежний огляд (Spec + Standards) на поточному
# HEAD після зеленого CI, ставить агент-оглядач. Автор PR вердикт собі не
# ставить. Мітка має існувати в репозиторії; якщо її немає, скрипт підкаже
# команду.
#
# Чого тут НЕМАЄ: перевірки «останній прогін — не мутаційний». Це #1221.
#
# Виклик: scripts/merge-pr.sh <номер-PR> [--dry-run]
# Змінні: MERGE_PR_GH — шлях до gh (типово `gh`; потрібен для тестів),
#         MERGE_PR_LABEL — назва мітки вердикту (типово `review:pass`).
# Коди виходу: 0 — злито (--dry-run: злив би); 1 — відмова гейта;
#              2 — не вдалося перевірити (аргументи, gh, мережа).
set -euo pipefail

GH="${MERGE_PR_GH:-gh}"
LABEL="${MERGE_PR_LABEL:-review:pass}"

usage() {
  printf 'usage: scripts/merge-pr.sh <номер-PR> [--dry-run]\n'
  printf '\n'
  printf 'Мердж-гейт: зливає PR лише за зеленого CI і свіжого вердикту огляду\n'
  printf "(мітка '%s'; іншу назву дає MERGE_PR_LABEL).\n" "$LABEL"
  printf 'Зливає тільки --squash і ніколи не видаляє гілку.\n'
  printf 'Коди виходу: 0 — злито (--dry-run: злив би); 1 — відмова; 2 — не перевірити.\n'
}

PR_NUMBER=""
DRY_RUN=false
for argument in "$@"; do
  case "$argument" in
    -h | --help) usage; exit 0 ;;
    --dry-run) DRY_RUN=true ;;
    -*) printf 'FAIL  невідомий прапорець: %s\n' "$argument" >&2; usage >&2; exit 2 ;;
    *)
      if [ -n "$PR_NUMBER" ]; then
        printf 'FAIL  зайвий аргумент: %s\n' "$argument" >&2
        usage >&2
        exit 2
      fi
      PR_NUMBER="$argument"
      ;;
  esac
done

if [ -z "$PR_NUMBER" ]; then
  usage >&2
  exit 2
fi
case "$PR_NUMBER" in
  *[!0-9]* | '') printf "FAIL  номер PR має бути числом: '%s'\n" "$PR_NUMBER" >&2; exit 2 ;;
esac
# Назва мітки йде в шлях REST-запиту, тож складні імена не підтримуємо свідомо.
case "$LABEL" in
  *[!A-Za-z0-9._:-]* | '')
    printf "FAIL  назва мітки має бути простою (літери, цифри, . _ : -): '%s'\n" "$LABEL" >&2
    exit 2
    ;;
esac

if ! command -v "$GH" >/dev/null 2>&1; then
  printf 'FAIL  gh не знайдено (%s) — встанови GitHub CLI.\n' "$GH" >&2
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok() { printf 'OK    %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*" >&2; }
note() { printf '      %s\n' "$*" >&2; }
refuse() { printf 'FAIL  %s\n' "$1" >&2; printf 'Далі: %s\n' "$2" >&2; exit 1; }

# Розбір JSON gh. Одна програма на всі фази: mode вибирає, що саме читати.
# Друк — `key=value` у stdout (bash читає без eval, тож дані з GitHub не
# стають кодом), списки — у файли всередині $TMP.
json_facts() {
  python3 - "$@" <<'PY'
import json
import sys
from datetime import datetime

MODE, *ARGS = sys.argv[1:]

# Висновки, які не є доказом зеленості. CANCELLED — окремо: «скасовано»
# і «впало» — різні причини відмови, хоч обидві блокують.
CHECK_FAIL = {"FAILURE", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED", "STALE", "ERROR"}
RUN_FAIL = {"failure", "timed_out", "startup_failure", "action_required", "stale", "error"}
PUSH_EVENTS = {"head_ref_force_pushed", "head_ref_deleted", "head_ref_restored"}


def load(path):
    with open(path, encoding="utf-8") as handle:
        text = handle.read().strip()
    return json.loads(text) if text else None


def pages(path):
    """gh api --paginate --slurp: масив сторінок; розгортаємо в один список."""
    flat = []
    for page in load(path) or []:
        if isinstance(page, list):
            flat.extend(page)
        elif page is not None:
            flat.append(page)
    return flat


def moment(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def stamp(value):
    return "" if value is None else value.isoformat().replace("+00:00", "Z")


def write(out_dir, name, lines):
    with open("%s/%s" % (out_dir, name), "w", encoding="utf-8") as handle:
        for line in lines:
            handle.write(line + "\n")


if MODE == "pr":
    pr = load(ARGS[0]) or {}
    out_dir, label = ARGS[1], ARGS[2]
    fails, pending, cancels, untimed = [], [], [], []
    for context in pr.get("statusCheckRollup") or []:
        name = context.get("name") or context.get("context") or "?"
        url = context.get("detailsUrl") or context.get("targetUrl") or ""
        if context.get("__typename") == "StatusContext":
            state = (context.get("state") or "").upper()
            if state in {"FAILURE", "ERROR"}:
                fails.append((name, state, url))
            elif state in {"PENDING", "EXPECTED"}:
                pending.append((name, state, url))
            if not context.get("createdAt"):
                untimed.append((name, state or "?", url))
            continue
        conclusion = (context.get("conclusion") or "").upper()
        status = (context.get("status") or "").upper()
        if conclusion in CHECK_FAIL:
            fails.append((name, conclusion, url))
        elif conclusion == "CANCELLED":
            cancels.append((name, conclusion, url))
        elif conclusion in {"SUCCESS", "SKIPPED", "NEUTRAL"}:
            pass
        elif status == "COMPLETED":
            # Завершився без висновку — це не «зелено», це невідомість.
            fails.append((name, "COMPLETED без висновку", url))
        else:
            pending.append((name, status or "?", url))
        if not (context.get("startedAt") or context.get("completedAt")):
            untimed.append((name, conclusion or status or "?", url))
    print("state=%s" % (pr.get("state") or ""))
    print("draft=%s" % str(bool(pr.get("isDraft"))).lower())
    print("mergeable=%s" % (pr.get("mergeable") or ""))
    print("merge_status=%s" % (pr.get("mergeStateStatus") or ""))
    print("base=%s" % (pr.get("baseRefName") or ""))
    print("head_sha=%s" % (pr.get("headRefOid") or ""))
    print("has_label=%s" % str(any(item.get("name") == label for item in pr.get("labels") or [])).lower())
    print("checks_total=%d" % len(pr.get("statusCheckRollup") or []))
    print("n_fail=%d" % len(fails))
    print("n_pending=%d" % len(pending))
    print("n_cancel=%d" % len(cancels))
    print("n_untimed=%d" % len(untimed))
    write(out_dir, "fails.txt", ["\t".join(row) for row in fails])
    write(out_dir, "pending.txt", ["\t".join(row) for row in pending])
    write(out_dir, "cancel.txt", ["\t".join(row) for row in cancels])
    write(out_dir, "untimed.txt", ["\t".join(row) for row in untimed])

elif MODE == "timeline":
    out_dir, label = ARGS[1], ARGS[2]
    verdict, rewrites = None, []
    for event in pages(ARGS[0]):
        kind, when = event.get("event"), moment(event.get("created_at"))
        if kind == "labeled" and (event.get("label") or {}).get("name") == label and when:
            if verdict is None or when > verdict:
                verdict = when
        if kind in PUSH_EVENTS and when:
            after = (event.get("after_commit") or event.get("commit_id") or "")[:7]
            rewrites.append((when, kind, after))
    # Перезапис гілки ловимо тут-таки: вердикт уже відомий, а порівнювати
    # ISO-рядки в bash означало б покладатися на лексикографіку.
    late = [row for row in rewrites if verdict and row[0] > verdict]
    print("verdict_ts=%s" % stamp(verdict))
    print("n_late_rewrites=%d" % len(late))
    write(
        out_dir,
        "late_rewrites.txt",
        ["%s\t%s\t%s" % (stamp(when), kind, after) for when, kind, after in sorted(late)],
    )

elif MODE == "commits":
    verdict = moment(ARGS[1])
    late = []
    for item in pages(ARGS[0]):
        commit = item.get("commit") or {}
        when = moment((commit.get("committer") or {}).get("date"))
        if when and verdict and when > verdict:
            headline = (commit.get("message") or "").splitlines()
            late.append(((item.get("sha") or "")[:7], stamp(when), headline[0] if headline else ""))
    print("n_late_commits=%d" % len(late))
    write(ARGS[2], "late_commits.txt", ["\t".join(row) for row in late])

elif MODE == "runs":
    verdict = moment(ARGS[1])
    late_runs, late_fails = [], []
    for run in pages(ARGS[0]):
        started, completed = moment(run.get("started_at")), moment(run.get("completed_at"))
        late_start = bool(started and verdict and started > verdict)
        late_end = bool(completed and verdict and completed > verdict)
        if not (late_start or late_end):
            continue
        row = (
            run.get("name") or "?",
            (run.get("conclusion") or run.get("status") or "?"),
            stamp(started if late_start else completed),
            run.get("html_url") or "",
        )
        late_runs.append(row)
        if (run.get("conclusion") or "") in RUN_FAIL:
            late_fails.append(row)
    print("n_late_runs=%d" % len(late_runs))
    print("n_late_fails=%d" % len(late_fails))
    write(ARGS[2], "late_runs.txt", ["\t".join(row) for row in late_runs])
    write(ARGS[2], "late_fails.txt", ["\t".join(row) for row in late_fails])

elif MODE == "repo":
    print("delete_on_merge=%s" % str(bool((load(ARGS[0]) or {}).get("delete_branch_on_merge"))).lower())

else:
    sys.exit("невідомий режим розбору: %s" % MODE)
PY
}

# gh api --paginate --slurp: повні списки без обрізання (--slurp потрібен
# gh ≥ 2.43; без нього сторінки не складаються у валідний JSON).
gh_api_slurp() { # gh_api_slurp <path> <outfile> <що читали>
  if ! "$GH" api --paginate --slurp "$1" >"$2" 2>"$TMP/api.err"; then
    printf 'FAIL  не вдалося прочитати %s (gh api).\n' "$3" >&2
    sed 's/^/      /' "$TMP/api.err" >&2 || true
    printf 'Далі: перевір `gh auth status` і що gh ≥ 2.43 (--slurp); PR не змінено.\n' >&2
    exit 2
  fi
}

# Мітка може існувати в репозиторії, але не стояти на PR, і навпаки. Це різні
# підказки, тож перевіряємо обидві. 404 = мітки немає (не помилка мережі).
label_exists_in_repo() {
  "$GH" api "repos/{owner}/{repo}/labels/$LABEL" >/dev/null 2>&1
}

read_facts() { # read_facts <mode> <args...> ; stdout = key=value, exit 2 якщо не розібрали
  json_facts "$@" || {
    printf 'FAIL  не вдалося розібрати відповідь gh (режим %s).\n' "$1" >&2
    exit 2
  }
}

# --- 1) Стан PR -------------------------------------------------------------
PR_JSON="$TMP/pr.json"
if ! "$GH" pr view "$PR_NUMBER" \
  --json state,isDraft,mergeable,mergeStateStatus,baseRefName,headRefOid,labels,statusCheckRollup \
  >"$PR_JSON" 2>"$TMP/pr.err"; then
  printf 'FAIL  не вдалося прочитати PR #%s через gh:\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/pr.err" >&2 || true
  printf 'Далі: перевір номер PR і автентифікацію (`gh auth status`).\n' >&2
  exit 2
fi

STATE="" DRAFT="" MERGEABLE="" MERGE_STATUS="" BASE="" HEAD_SHA=""
HAS_LABEL=false CHECKS_TOTAL=0 N_FAIL=0 N_PENDING=0 N_CANCEL=0 N_UNTIMED=0
PR_FACTS="$(read_facts pr "$PR_JSON" "$TMP" "$LABEL")"
while IFS='=' read -r key value; do
  case "$key" in
    state) STATE="$value" ;;
    draft) DRAFT="$value" ;;
    mergeable) MERGEABLE="$value" ;;
    merge_status) MERGE_STATUS="$value" ;;
    base) BASE="$value" ;;
    head_sha) HEAD_SHA="$value" ;;
    has_label) HAS_LABEL="$value" ;;
    checks_total) CHECKS_TOTAL="$value" ;;
    n_fail) N_FAIL="$value" ;;
    n_pending) N_PENDING="$value" ;;
    n_cancel) N_CANCEL="$value" ;;
    n_untimed) N_UNTIMED="$value" ;;
  esac
done <<<"$PR_FACTS"

# Порожня відповідь gh — це не «PR без чеків», а відсутність даних: без стану
# й HEAD перевіряти нічого, а вгадувати заборонено.
if [ -z "$STATE" ] || [ -z "$HEAD_SHA" ]; then
  printf 'FAIL  gh не повернув стан PR #%s (порожня відповідь) — перевіряти нічого.\n' "$PR_NUMBER" >&2
  printf 'Далі: повтори; якщо повторюється — подивись `gh pr view %s --json state,headRefOid`.\n' "$PR_NUMBER" >&2
  exit 2
fi

HEAD_SHORT="${HEAD_SHA:0:7}"
REISSUE="gh pr edit $PR_NUMBER --remove-label '$LABEL' && gh pr edit $PR_NUMBER --add-label '$LABEL'"
REVIEW_REMEDY="делегуй незалежний огляд (Spec + Standards) на HEAD $HEAD_SHORT і простав вердикт: gh pr edit $PR_NUMBER --add-label '$LABEL'"

if [ "$STATE" != "OPEN" ]; then
  refuse "PR #$PR_NUMBER у стані $STATE — це не відкритий PR, зливати нічого." \
    'нічого не роби; якщо потрібна саме ця зміна — відкрий новий PR.'
fi
if [ "$DRAFT" = true ]; then
  refuse "PR #$PR_NUMBER — чернетка (draft): гейт зливає лише готові PR." \
    "зніми чернетку: gh pr ready $PR_NUMBER, тоді повтори scripts/merge-pr.sh $PR_NUMBER"
fi
if [ "$MERGEABLE" = "CONFLICTING" ] || [ "$MERGE_STATUS" = "DIRTY" ]; then
  refuse "PR #$PR_NUMBER: гілка конфліктує з '$BASE' (mergeable=$MERGEABLE, mergeStateStatus=$MERGE_STATUS) — злиття неможливе." \
    "у worktree гілки: git fetch origin && git merge origin/$BASE, розв'яжи конфлікт, запуш і повтори гейт."
fi
if [ "$MERGEABLE" = "UNKNOWN" ]; then
  refuse "PR #$PR_NUMBER: GitHub ще рахує мержабельність (mergeable=UNKNOWN) — стан невідомий." \
    "зачекай кілька секунд і повтори scripts/merge-pr.sh $PR_NUMBER"
fi
if [ "$MERGE_STATUS" = "BLOCKED" ]; then
  refuse "PR #$PR_NUMBER: GitHub позначив злиття як BLOCKED (захист гілки або потрібні рев'ю) — merge не пройде." \
    "перевір налаштування захисту '$BASE' і вимоги PR; сам гейт цього не обходить."
fi

# --- 2) Чеки ----------------------------------------------------------------
if [ "$CHECKS_TOTAL" -eq 0 ]; then
  refuse "PR #$PR_NUMBER: немає жодного чека — вердикту нема на чому стояти." \
    "переконайся, що CI запустився на HEAD $HEAD_SHORT (gh pr checks $PR_NUMBER); якщо ні — перезапусти workflow."
fi

if [ "$N_FAIL" -gt 0 ]; then
  printf 'FAIL  PR #%s: чек(и) упали — гейт не зливає з червоним CI.\n' "$PR_NUMBER" >&2
  while IFS=$'\t' read -r name conclusion url; do
    printf '      ✗ %s — %s\n        %s\n' "$name" "$conclusion" "$url" >&2
  done <"$TMP/fails.txt"
  printf 'Далі: закрий знахідки й дочекайся зеленого CI на HEAD %s;\n' "$HEAD_SHORT" >&2
  printf '      якщо після вердикту були нові коміти — потрібен новий огляд і новий вердикт:\n' >&2
  printf '      %s\n' "$REISSUE" >&2
  exit 1
fi

if [ "$N_PENDING" -gt 0 ]; then
  printf 'FAIL  PR #%s: CI ще не завершився (%s чек(ів)) — незавершений прогін не є доказом.\n' \
    "$PR_NUMBER" "$N_PENDING" >&2
  while IFS=$'\t' read -r name status url; do
    printf '      … %s — %s\n        %s\n' "$name" "$status" "$url" >&2
  done <"$TMP/pending.txt"
  printf 'Далі: дочекайся кінця прогону (gh pr checks %s), тоді повтори scripts/merge-pr.sh %s\n' \
    "$PR_NUMBER" "$PR_NUMBER" >&2
  exit 1
fi

if [ "$N_CANCEL" -gt 0 ]; then
  printf 'FAIL  PR #%s: чек(и) скасовано — це не доказ зеленості.\n' "$PR_NUMBER" >&2
  while IFS=$'\t' read -r name status url; do
    printf '      ✗ %s — %s\n        %s\n' "$name" "$status" "$url" >&2
  done <"$TMP/cancel.txt"
  printf 'Далі: перезапусти скасовані чеки й повтори scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 1
fi

if [ "$N_UNTIMED" -gt 0 ]; then
  warn "у $N_UNTIMED чек(ів) немає часу в статусі — їхній вік проти вердикту не перевірити:"
  while IFS=$'\t' read -r name conclusion url; do
    printf '      ? %s — %s\n        %s\n' "$name" "$conclusion" "$url" >&2
  done <"$TMP/untimed.txt"
fi

# --- 3) Вердикт огляду ------------------------------------------------------
if [ "$HAS_LABEL" != true ]; then
  printf 'FAIL  PR #%s: немає машиночитного вердикту огляду — мітки '\''%s'\'' на PR немає.\n' \
    "$PR_NUMBER" "$LABEL" >&2
  note "Ревізію $HEAD_SHORT ніхто не підтвердив незалежним оглядом (Spec + Standards)."
  note "Саме так #1215 злили з відкритим Spec FAIL — вердикт жив прозою в коментарі."
  if ! label_exists_in_repo; then
    note "Мітки '$LABEL' немає і в репозиторії — її створює координатор (один раз):"
    note "gh label create '$LABEL' --description 'Незалежний огляд пройдено (Spec + Standards)' --color 0E8A16"
  fi
  printf 'Далі: %s\n' "$REVIEW_REMEDY" >&2
  printf '      потім повтори: scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 1
fi

# --- 4) Вердикт новіший за те, що він засвідчує ------------------------------
gh_api_slurp "repos/{owner}/{repo}/issues/$PR_NUMBER/timeline" "$TMP/timeline.json" \
  "таймлайн PR #$PR_NUMBER (час вердикту)"

TIMELINE_FACTS="$(read_facts timeline "$TMP/timeline.json" "$TMP" "$LABEL")"
VERDICT_TS=""
N_LATE_REWRITES=0
while IFS='=' read -r key value; do
  case "$key" in
    verdict_ts) VERDICT_TS="$value" ;;
    n_late_rewrites) N_LATE_REWRITES="$value" ;;
  esac
done <<<"$TIMELINE_FACTS"

if [ -z "$VERDICT_TS" ]; then
  refuse "PR #$PR_NUMBER: мітка '$LABEL' стоїть, але події її проставлення в таймлайні немає — час вердикту невідомий, отже невідомо, яку ревізію він покриває." \
    "перепростав мітку, щоб з'явився свіжий час: $REISSUE"
fi

if [ "$N_LATE_REWRITES" -gt 0 ]; then
  printf 'FAIL  PR #%s: після вердикту (%s) гілку PR перезаписували — вердикт стосується іншої ревізії.\n' \
    "$PR_NUMBER" "$VERDICT_TS" >&2
  while IFS=$'\t' read -r when kind after; do
    printf '      ✗ %s о %s (нова ревізія %s)\n' "$kind" "$when" "${after:-?}" >&2
  done <"$TMP/late_rewrites.txt"
  printf 'Далі: оглянь HEAD %s і перепростав вердикт: %s\n' "$HEAD_SHORT" "$REISSUE" >&2
  exit 1
fi

# Коміти беремо з REST, а не з `gh pr view --json commits`: REST пагінується
# і не обрізає довгі PR.
gh_api_slurp "repos/{owner}/{repo}/pulls/$PR_NUMBER/commits" "$TMP/commits.json" \
  "коміти PR #$PR_NUMBER"
COMMITS_FACTS="$(read_facts commits "$TMP/commits.json" "$VERDICT_TS" "$TMP")"
N_LATE_COMMITS=0
while IFS='=' read -r key value; do
  case "$key" in
    n_late_commits) N_LATE_COMMITS="$value" ;;
  esac
done <<<"$COMMITS_FACTS"

if [ "$N_LATE_COMMITS" -gt 0 ]; then
  printf 'FAIL  PR #%s: після вердикту огляду (%s) у PR з’явилися нові коміти — вердикт стосується іншої ревізії.\n' \
    "$PR_NUMBER" "$VERDICT_TS" >&2
  while IFS=$'\t' read -r sha when subject; do
    printf '      ✗ %s «%s» (commit %s)\n' "$sha" "$subject" "$when" >&2
  done <"$TMP/late_commits.txt"
  printf 'Далі: оглянь ревізію %s і перепростав вердикт: %s\n' "$HEAD_SHORT" "$REISSUE" >&2
  exit 1
fi

# filter=all — саме він показує перезапущені спроби: fail, який уже зелено
# перезапустили, у зведенні PR не видно, а вердикт він все одно не покриває.
gh_api_slurp "repos/{owner}/{repo}/commits/$HEAD_SHA/check-runs?filter=all" "$TMP/runs.json" \
  "прогони чеків HEAD $HEAD_SHORT"
RUNS_FACTS="$(read_facts runs "$TMP/runs.json" "$VERDICT_TS" "$TMP")"
N_LATE_RUNS=0 N_LATE_FAILS=0
while IFS='=' read -r key value; do
  case "$key" in
    n_late_runs) N_LATE_RUNS="$value" ;;
    n_late_fails) N_LATE_FAILS="$value" ;;
  esac
done <<<"$RUNS_FACTS"

if [ "$N_LATE_FAILS" -gt 0 ]; then
  printf 'FAIL  PR #%s: після вердикту (%s) чек падав — навіть якщо зараз зелений, вердикт цього не покриває.\n' \
    "$PR_NUMBER" "$VERDICT_TS" >&2
  while IFS=$'\t' read -r name conclusion when url; do
    printf '      ✗ %s — %s, завершився %s\n        %s\n' "$name" "$conclusion" "$when" "$url" >&2
  done <"$TMP/late_fails.txt"
  printf 'Далі: подивись, чому чек упав, і перепростав вердикт на зеленому CI: %s\n' "$REISSUE" >&2
  exit 1
fi

if [ "$N_LATE_RUNS" -gt 0 ]; then
  printf 'FAIL  PR #%s: вердикт (%s) старіший за останній прогін CI — докази під вердиктом застаріли.\n' \
    "$PR_NUMBER" "$VERDICT_TS" >&2
  while IFS=$'\t' read -r name conclusion when url; do
    printf '      ✗ %s — %s, %s\n        %s\n' "$name" "$conclusion" "$when" "$url" >&2
  done <"$TMP/late_runs.txt"
  printf 'Далі: дочекайся кінця прогону і перепростав вердикт: %s\n' "$REISSUE" >&2
  exit 1
fi

# --- 5) Гілка має пережити мердж --------------------------------------------
if "$GH" api "repos/{owner}/{repo}" >"$TMP/repo.json" 2>"$TMP/repo.err"; then
  DELETE_ON_MERGE=false
  while IFS='=' read -r key value; do
    case "$key" in
      delete_on_merge) DELETE_ON_MERGE="$value" ;;
    esac
  done <<<"$(read_facts repo "$TMP/repo.json")"
  if [ "$DELETE_ON_MERGE" = true ]; then
    refuse "PR #$PR_NUMBER: у репозиторії ввімкнено delete_branch_on_merge — злиття знищило б гілку PR (колись це знесло worktree)." \
      "вимкни авто-видалення (gh api -X PATCH repos/{owner}/{repo} -f delete_branch_on_merge=false), тоді повтори scripts/merge-pr.sh $PR_NUMBER"
  fi
else
  warn "не вдалося прочитати налаштування репозиторію — delete_branch_on_merge не перевірено."
  warn "сам скрипт --delete-branch не передає, тож гілку не видалить він."
fi

# --- 6) Злиття --------------------------------------------------------------
MERGE_CMD=("$GH" pr merge "$PR_NUMBER" --squash --match-head-commit "$HEAD_SHA")
ok "PR #$PR_NUMBER: $CHECKS_TOTAL чек(ів) зелені; вердикт '$LABEL' від $VERDICT_TS новіший за HEAD $HEAD_SHORT і за останній прогін CI."
ok "гілку не видаляю: --delete-branch не передається."

if [ "$DRY_RUN" = true ]; then
  ok "DRY-RUN: злив би: ${MERGE_CMD[*]}"
  exit 0
fi

if ! "${MERGE_CMD[@]}" </dev/null 2>"$TMP/merge.err"; then
  printf 'FAIL  gh pr merge не злито — PR #%s лишився як був:\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/merge.err" >&2 || true
  printf 'Далі: розбери причину вище і повтори scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 1
fi
ok "PR #$PR_NUMBER злито squash-ом у '$BASE'; гілку не видалено."
