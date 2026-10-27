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
#   2) робоча копія не посеред операції: незавершений ребаз (`rebase-merge` /
#      `rebase-apply`) ГІЛКИ, ЯКУ ЗЛИВАЮТЬ (або такий, чию гілку не названо), —
#      це гілка, яку рухали, і поки маркер на місці, на remote могла поїхати її
#      половина. Саме так злився #1239: ребаз упав на конфлікті, один пік із
#      чотирьох комітів поїхав force-push-ем, CI пройшов (перевіряти не було
#      чого), і в `main` потрапила оригінальна версія скрипта — без правок
#      огляду (лікував окремий PR #1245). Ребаз ЧУЖОЇ названої гілки — WARN, не
#      відмова: у репозиторії десятки worktree й паралельні смуги, і чужий
#      ребаз, що спинився, не має зупиняти чергу — тим паче що «Далі» радило б
#      завершувати операцію в чужій копії й знищило б чуже розв'язання;
#   3) вершина гілки PR містить УСЮ локальну гілку того самого імені — за
#      ПАТЧАМИ, не за SHA (`git cherry`): так обрізане дерево відрізняється від
#      гілки, яку перебазували в іншому клоні, — там патчі вже у вершині, і
#      відмова з порадою force-push повернула б доребазову історію поверх
#      доребазованої. Коміт, чийого патча у вершині немає, — це те, що
#      рецензували, але не злили. GitHub тут не свідок: його список комітів PR
#      будується з тієї самої вершини, тож обрізання він показує як «один
#      коміт» (у #1239 — рівно так);
#   4) жоден чек не впав, не скасований і не висить. Порожній список чеків —
#      теж відмова: без доказів вердикту нема на чому стояти;
#   5) на PR стоїть мітка вердикту (типово `review:pass`) — інакше відмова;
#   6) мітка НОВІША за все, що вона засвідчує: за кожен коміт PR
#      (committedDate), за кожен прогін чеків HEAD-ревізії (filter=all, тож
#      видно й перезапущені спроби) і за будь-який force-push гілки PR.
#      Вердикт, старіший за новий коміт, новий прогін чи новий fail, не
#      покриває те, що зливається, — і гейт відмовляє. Власний чек вердикту
#      (`MERGE_PR_VERDICT_CHECK`, типово `Review gate`) із цього списку
#      виключений: його запускає сама мітка, тож «пізній» він завжди; його
#      чесність тримає власна логіка (без свіжої мітки він червоний);
#   7) у репозиторії вимкнено auto-delete гілок: увімкнений
#      `delete_branch_on_merge` знищив би гілку PR так само, як колись
#      `gh pr merge --delete-branch` зніс worktree.
#
# Межа перевірки 3, названа прямо: гейт бачить лише ту робочу копію, з якої
# його запущено. Якщо локальної гілки немає (PR приїхав з іншого клона) або PR
# з форка — це WARN, а не відмова: «немає гілки» — відомий безневинний стан, а
# не доказ обрізання. Невідомість інша: якщо гілка локально є, а ревізії
# вершини PR у копії немає — порівнювати нічого, і це код 2 з підказкою
# `git fetch`. Перевірка 2, навпаки, дивиться на всю копію — і спільний `.git`,
# і кожен worktree: ребаз #1239 жив у w1224, а гейт запускають звідки завгодно.
# Гілку ребаза вона читає з `head-name`: чужа НАЗВАНА гілка — WARN, а
# «detached HEAD» чи порожньо — відмова, бо приписати такий ребаз комусь
# іншому нема підстав, а мовчазно пропустити означало б пропустити і свій.
#
# Чому мітка, а не `reviewDecision`. Схвалення GitHub-рев'ю («Approve») автор
# PR поставити собі не може, а всі PR цього репозиторію відкриває той самий
# акаунт — тож reviewDecision ніколи не стане APPROVED. Мітку автор поставити
# може. Це процесний сигнал, а не криптографічний доказ, і саме тому п.6
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
#         MERGE_PR_LABEL — назва мітки вердикту (типово `review:pass`),
#         MERGE_PR_VERDICT_CHECK — назва CI-чека, що публікує вердикт (типово
#         `Review gate`); його власний прогін не вважається «пізнім», бо його
#         створює сама мітка,
#         MERGE_PR_REPO — робоча копія для перевірок 2 і 3 (типово — git-корінь
#         поточного каталогу; потрібен для тестів),
#         MERGE_PR_GIT — шлях до git (типово `git`; теж для тестів: їм треба
#         показати старий git, який не знає --path-format).
# Коди виходу: 0 — злито (--dry-run: злив би); 1 — відмова гейта (огляд, чеки,
#              стан PR, обрізана гілка, незавершений ребаз); 2 — перевірити
#              або злити не вдалося (аргументи, gh, мережа, робоча копія без
#              ревізії вершини PR, відмова GitHub на самому мерджі).
set -euo pipefail

GH="${MERGE_PR_GH:-gh}"
LABEL="${MERGE_PR_LABEL:-review:pass}"
# The CI check that publishes this verdict. It is triggered BY the label, so it
# necessarily starts after the verdict and must not be counted as a "late CI
# run" — otherwise every correctly reviewed PR would be refused forever. Its
# own freshness logic (no label / label older than HEAD) is what keeps it
# honest; a failing or pending gate still blocks through check 4 above.
VERDICT_CHECK="${MERGE_PR_VERDICT_CHECK:-Review gate}"
export MERGE_PR_VERDICT_CHECK="$VERDICT_CHECK"
GIT="${MERGE_PR_GIT:-git}"

usage() {
  printf 'usage: scripts/merge-pr.sh <номер-PR> [--dry-run]\n'
  printf '\n'
  printf 'Мердж-гейт: зливає PR лише за зеленого CI і свіжого вердикту огляду\n'
  printf "(мітка '%s'; іншу назву дає MERGE_PR_LABEL).\n" "$LABEL"
  printf 'Відмовляє, якщо робоча копія посеред ребаза або гілка PR на remote неповна.\n'
  printf 'Зливає тільки --squash і ніколи не видаляє гілку.\n'
  printf 'Коди виходу: 0 — злито (--dry-run: злив би); 1 — відмова гейта;\n'
  printf '             2 — перевірити або злити не вдалося (gh, мережа, відмова GitHub).\n'
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
# Порожній номер сюди не доходить: його ловить `-z` вище. Альтернативу `''` тут
# тримав лише здогад — і саме такі недосяжні альтернативи машина й знаходить
# (scripts/empty_shape_mutants.py): форма, яку не виробляє жоден тест, не
# піниться нічим, тож код не сміє її обіцяти.
case "$PR_NUMBER" in
  *[!0-9]*) printf "FAIL  номер PR має бути числом: '%s'\n" "$PR_NUMBER" >&2; exit 2 ;;
esac
# Назва мітки йде в шлях REST-запиту, тож складні імена не підтримуємо свідомо.
# Порожньою вона бути не може: `:-` вище підставляє типову.
case "$LABEL" in
  *[!A-Za-z0-9._:-]*)
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
import os
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


def pages(path, wrapper=None):
    """gh api --paginate --slurp: масив сторінок; розгортаємо в один список.

    Сторінка буває двома формами: списком (`issues/<n>/timeline`,
    `pulls/<n>/commits`) або обгорткою-об'єктом зі списком усередині
    (`/commits/<sha>/check-runs` віддає `{"total_count": N, "check_runs": [...]}`).
    Режим називає ключ обгортки другим аргументом. Невідома форма сторінки — це
    помилка, а не «порожньо»: перша версія цієї функції ковтала об'єктну
    сторінку одним елементом, і перевірка «вердикт старіший за CI» мовчки
    стала мертвою (n_late_runs=0 на справжніх даних).
    """
    flat = []
    for page in load(path) or []:
        if isinstance(page, list):
            flat.extend(page)
        elif isinstance(page, dict) and wrapper and wrapper in page:
            flat.extend(page[wrapper] or [])
        else:
            shape = sorted(page)[:8] if isinstance(page, dict) else page
            sys.exit("несподівана форма сторінки gh api: %r" % (shape,))
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
    print("head_ref=%s" % (pr.get("headRefName") or ""))
    print("head_sha=%s" % (pr.get("headRefOid") or ""))
    print("cross_repo=%s" % str(bool(pr.get("isCrossRepository"))).lower())
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
    self_check = os.environ.get("MERGE_PR_VERDICT_CHECK", "")
    for run in pages(ARGS[0], "check_runs"):
        # The verdict check is created by the label itself, so it is always
        # "late" by construction. Excluding it is not a loophole: its script
        # fails without a fresh label, and checks 4/5/6 still see it fail or
        # the label missing.
        if self_check and (run.get("name") or "") == self_check:
            continue
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
# підказки, тож перевіряємо обидві. Три стани: 0 — мітка є, 1 — 404 (немає),
# 2 — не вдалося спитати (мережа, права): це не «немає», і вгадувати не можна.
label_exists_in_repo() {
  local probe
  probe="$("$GH" api "repos/{owner}/{repo}/labels/$LABEL" 2>&1 >/dev/null)" && return 0
  case "$probe" in
    *"HTTP 404"* | *"Not Found"*) return 1 ;;
  esac
  return 2
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
  --json state,isDraft,mergeable,mergeStateStatus,baseRefName,headRefName,headRefOid,isCrossRepository,labels,statusCheckRollup \
  >"$PR_JSON" 2>"$TMP/pr.err"; then
  printf 'FAIL  не вдалося прочитати PR #%s через gh:\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/pr.err" >&2 || true
  printf 'Далі: перевір номер PR і автентифікацію (`gh auth status`).\n' >&2
  exit 2
fi

STATE="" DRAFT="" MERGEABLE="" MERGE_STATUS="" BASE="" HEAD_REF="" HEAD_SHA="" IS_CROSS_REPO=false
HAS_LABEL=false CHECKS_TOTAL=0 N_FAIL=0 N_PENDING=0 N_CANCEL=0 N_UNTIMED=0
PR_FACTS="$(read_facts pr "$PR_JSON" "$TMP" "$LABEL")"
while IFS='=' read -r key value; do
  case "$key" in
    state) STATE="$value" ;;
    draft) DRAFT="$value" ;;
    mergeable) MERGEABLE="$value" ;;
    merge_status) MERGE_STATUS="$value" ;;
    base) BASE="$value" ;;
    head_ref) HEAD_REF="$value" ;;
    head_sha) HEAD_SHA="$value" ;;
    cross_repo) IS_CROSS_REPO="$value" ;;
    has_label) HAS_LABEL="$value" ;;
    checks_total) CHECKS_TOTAL="$value" ;;
    n_fail) N_FAIL="$value" ;;
    n_pending) N_PENDING="$value" ;;
    n_cancel) N_CANCEL="$value" ;;
    n_untimed) N_UNTIMED="$value" ;;
  esac
done <<<"$PR_FACTS"

# Порожня відповідь gh — це не «PR без чеків», а відсутність даних: без стану,
# імені гілки й HEAD перевіряти нічого, а вгадувати заборонено.
if [ -z "$STATE" ] || [ -z "$HEAD_SHA" ] || [ -z "$HEAD_REF" ]; then
  printf 'FAIL  gh не повернув стан PR #%s (порожня відповідь) — перевіряти нічого.\n' "$PR_NUMBER" >&2
  printf 'Далі: повтори; якщо повторюється — подивись `gh pr view %s --json state,headRefName,headRefOid`.\n' "$PR_NUMBER" >&2
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

# --- 1b) #1338: дерево PR не може підмінити гейт, який його перевіряє --------
# pull_request біжить у контексті merge-коміта, тож крок у ci.yml і самі
# скрипти гейтів приходять ІЗ PR. Захист гілки на main закриває частину:
# видалений обов'язковий чек стає ВІДСУТНІМ, і GitHub блокує мердж. Але дерево
# PR усе ще може переписати САМ гейт — тому такі PR не зливаються без явного
# дозволу власника (MERGE_PR_ALLOW_GATE_EDITS=1).
GATE_PATHS='^\.github/workflows/|^scripts/merge-pr\.sh$|^scripts/review-pass\.sh$'
if [ "${MERGE_PR_ALLOW_GATE_EDITS:-0}" != "1" ]; then
  if "$GH" pr view "$PR_NUMBER" --json files >"$TMP/gate-files.json" 2>/dev/null; then
    TOUCHED="$(python3 - "$TMP/gate-files.json" <<'PYGATE'
import json
import sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    raise SystemExit(0)
for item in data.get("files") or []:
    if isinstance(item, dict) and item.get("path"):
        print(item["path"])
PYGATE
)"
    if printf '%s\n' "$TOUCHED" | grep -qE "$GATE_PATHS"; then
      refuse "PR #$PR_NUMBER змінює сам гейт (.github/workflows/ або scripts/merge-pr.sh|review-pass.sh): дерево PR не може переписувати те, що його перевіряє (#1338)." \
        "якщо це справді потрібно — MERGE_PR_ALLOW_GATE_EDITS=1 scripts/merge-pr.sh $PR_NUMBER, і скажи вголос, що гейт змінено"
    fi
  fi
fi

# --- 2) Робоча копія не посеред операції ------------------------------------
# Ребаз лишає по собі `rebase-merge` (merge-backend) або `rebase-apply`
# (apply-backend, він же `git am`). Поки маркер на місці, гілка, яку рухали, є
# половиною: гілку PR могло вже поїхати на remote частково (#1239).
# Дивимось УСЮ робочу копію — спільний `.git` і кожен worktree: ребаз #1239 жив
# у w1224, а гейт запускають звідки завгодно.
REPO_ROOT="${MERGE_PR_REPO:-}"
if [ -z "$REPO_ROOT" ]; then
  REPO_ROOT="$("$GIT" rev-parse --show-toplevel 2>/dev/null || true)"
fi
if [ -z "$REPO_ROOT" ] || ! "$GIT" -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  printf 'FAIL  не вдалося визначити робочу копію (git-репозиторій) — цілісність гілки перевірити нічим.\n' >&2
  printf 'Далі: запусти гейт із каталогу репозиторію (або задай MERGE_PR_REPO=<шлях до робочої копії>).\n' >&2
  exit 2
fi
REPO_ROOT="$("$GIT" -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null || printf '%s' "$REPO_ROOT")"

# Друк: "<шлях до маркера>|<гілка>|<робоча копія>". Роздільник — НЕ таб і не
# пробіл: IFS-whitespace згортає повтори, і порожнє поле зникає (`M\t\tW` дає
# marker=M, branch=W, worktree="") — безіменний ребаз тоді читався б як ребаз
# чужої гілки, названої шляхом worktree. `|` лишає порожнє поле порожнім, а в
# останньому полі (шлях) решта рядка зберігається разом із роздільниками.
open_operations() {
  local common git_dir dir marker branch worktree seen
  common="$("$GIT" -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)" || return 1
  git_dir="$("$GIT" -C "$REPO_ROOT" rev-parse --path-format=absolute --git-dir)" || return 1
  seen=" "
  for dir in "$git_dir" "$common" "$common"/worktrees/*; do
    [ -d "$dir" ] || continue
    case "$seen" in *" $dir "*) continue ;; esac
    seen="$seen$dir "
    for marker in rebase-merge rebase-apply; do
      [ -d "$dir/$marker" ] || continue
      branch="$(cat "$dir/$marker/head-name" 2>/dev/null || true)"
      worktree="$dir"
      if [ -f "$dir/gitdir" ]; then
        worktree="$(sed 's:/\.git/*$::' "$dir/gitdir" 2>/dev/null || true)"
        [ -n "$worktree" ] || worktree="$dir"
      elif [ "$dir" = "$common" ]; then
        # Головний worktree: git-каталог — це $REPO_ROOT/.git, а копія — сам $REPO_ROOT.
        worktree="$REPO_ROOT"
      fi
      printf '%s|%s|%s\n' "$dir/$marker" "${branch#refs/heads/}" "$worktree"
    done
  done
  return 0
}

if ! OPERATIONS="$(open_operations)"; then
  printf 'FAIL  не вдалося прочитати стан робочої копії — незавершений ребаз перевірити нічим.\n' >&2
  printf 'Далі: перевір, що MERGE_PR_REPO вказує на робочу копію, і що git ≥ 2.31 (--path-format=absolute); PR не змінено.\n' >&2
  exit 2
fi
: >"$TMP/rebase_own.txt"
: >"$TMP/rebase_other.txt"
while IFS='|' read -r marker branch worktree; do
  [ -n "$marker" ] || continue
  case "$branch" in
    "" | "detached HEAD")
      # Гілку не названо: `git am` узагалі не пише head-name, а ребаз від
      # від'єднаного HEAD пише "detached HEAD". Приписати таку операцію чужій
      # смузі нема підстав, а пропустити означало б пропустити і свій ребаз —
      # гілку рухали, і ми не знаємо, яку.
      printf '%s|%s|%s\n' "$marker" "не названо${branch:+ ($branch)}" "$worktree" >>"$TMP/rebase_own.txt"
      ;;
    "$HEAD_REF") printf '%s|%s|%s\n' "$marker" "$branch" "$worktree" >>"$TMP/rebase_own.txt" ;;
    *) printf '%s|%s|%s\n' "$marker" "$branch" "$worktree" >>"$TMP/rebase_other.txt" ;;
  esac
done <<<"$OPERATIONS"

if [ -s "$TMP/rebase_other.txt" ]; then
  warn "у робочій копії незавершений ребаз чужої гілки — гілку PR '$HEAD_REF' він не рухав, тож мердж не блокую:"
  while IFS='|' read -r marker branch worktree; do
    printf '      ? %s (гілка: %s, робоча копія %s)\n' "$marker" "$branch" "$worktree" >&2
  done <"$TMP/rebase_other.txt"
  note "чужу робочу копію не чіпай: ребаз завершує або скасовує його автор — інакше зникне чуже розвʼязання конфлікту."
fi

if [ -s "$TMP/rebase_own.txt" ]; then
  printf 'FAIL  PR #%s: у робочій копії лишився незавершений ребаз — гілку рухали посеред операції, тож на remote могла поїхати її половина.\n' \
    "$PR_NUMBER" >&2
  while IFS='|' read -r marker branch worktree; do
    printf '      ✗ %s (гілка: %s, робоча копія %s)\n' "$marker" "$branch" "$worktree" >&2
  done <"$TMP/rebase_own.txt"
  printf 'Далі: заверши ребаз (git -C <робоча копія> rebase --continue) або скасуй його (git -C <робоча копія> rebase --abort),\n' >&2
  printf '      переконайся, що гілка на remote повна, і повтори scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 1
fi

# --- 3) Цілісність гілки PR -------------------------------------------------
# Вершина PR (headRefOid) мусить містити ВСЮ локальну гілку того самого імені.
# GitHub тут не свідок: його список комітів PR будується з тієї самої вершини,
# тож обрізану гілку він показує як «один коміт» і мовчить. Локальна гілка —
# єдине місце, де ще живуть коміти, які рецензували.
LOCAL_TIP=""
if [ "$IS_CROSS_REPO" != true ]; then
  LOCAL_TIP="$("$GIT" -C "$REPO_ROOT" rev-parse --verify --quiet "refs/heads/$HEAD_REF" 2>/dev/null || true)"
fi

if [ -z "$LOCAL_TIP" ]; then
  if [ "$IS_CROSS_REPO" = true ]; then
    warn "PR #$PR_NUMBER — з форка: локальної гілки '$HEAD_REF' тут бути не може, тож цілісність гілки НЕ перевірено."
  else
    warn "локальної гілки '$HEAD_REF' немає — цілісність гілки НЕ перевірено (гейт бачить лише цю робочу копію)."
  fi
  note 'це не відмова: «немає гілки» — не доказ обрізання. Переконайся сам, що гілка на remote повна.'
elif ! "$GIT" -C "$REPO_ROOT" cat-file -e "$HEAD_SHA^{commit}" 2>/dev/null; then
  printf 'FAIL  PR #%s: у робочій копії немає ревізії %s (вершина PR) — цілісність гілки не перевірити.\n' \
    "$PR_NUMBER" "$HEAD_SHORT" >&2
  printf 'Далі: git fetch origin %s (щоб копія знала вершину PR) і повтори scripts/merge-pr.sh %s\n' \
    "$HEAD_REF" "$PR_NUMBER" >&2
  exit 2
elif ! "$GIT" -C "$REPO_ROOT" merge-base --is-ancestor "$LOCAL_TIP" "$HEAD_SHA"; then
  # Історії розійшлися — але це ще не обрізання. Гілку могли перебазувати й
  # запушити з ІНШОГО клона: тоді місцевий ref лишається старим, а патчі комітів
  # уже у вершині. `--cherry-mark` порівнює саме патчі, а не SHA:
  #   `=` — коміт гілки вже представлений у вершині (той самий вміст, інший SHA),
  #   `+` — його патча у вершині немає.
  # `--right-only` лишає бік гілки, `--no-merges` не рахує злиття (патча в них
  # немає). Межа цього порівняння, названа прямо: разом із злиттями невидимим
  # лишається і вміст, що живе ЛИШЕ в merge-коміті, — розв'язання конфлікту, яке
  # не повторилося в жодному не-мердж коміті. Відмова з порадою force-push
  # лишається тільки для `+`: у випадку ребазу вона повернула б доребазову
  # історію поверх доребазованої.
  if ! CHERRY="$("$GIT" -C "$REPO_ROOT" rev-list --cherry-mark --no-merges --right-only \
    "$HEAD_SHA...$LOCAL_TIP" 2>"$TMP/cherry.err")"; then
    printf 'FAIL  PR #%s: не вдалося визначити, які коміти гілки '\''%s'\'' не у вершині PR — цілісність не перевірити.\n' \
      "$PR_NUMBER" "$HEAD_REF" >&2
    sed 's/^/      /' "$TMP/cherry.err" >&2 || true
    printf 'Далі: перевір, що копія й ревізії гілки цілі (git fsck), і повтори scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
    exit 2
  fi
  LOCAL_SHORT="${LOCAL_TIP:0:7}"
  N_REWRITTEN=0
  N_LOST=0
  LOST=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "${line:0:1}" in
      "=") N_REWRITTEN=$((N_REWRITTEN + 1)) ;;
      "+")
        N_LOST=$((N_LOST + 1))
        LOST="$LOST${line:1}"$'\n'
        ;;
    esac
  done <<<"$CHERRY"

  if [ "$N_LOST" -eq 0 ]; then
    warn "PR #$PR_NUMBER: історії гілки '$HEAD_REF' ($LOCAL_SHORT) і вершини PR ($HEAD_SHORT) розійшлися, але патчів, яких немає у вершині, не видно ($N_REWRITTEN коміт(ів) гілки вже там) — це ребаз із іншого клона, а не обрізання."
    note "локальна гілка застаріла — онови її з remote: git fetch origin +refs/heads/$HEAD_REF:refs/heads/$HEAD_REF"
    note "мердж не блокую: зміст гілки у вершині є. Переконайся, що це саме той вміст, який рецензували."
  else
    printf 'FAIL  PR #%s: вершина PR (%s) не містить усіх комітів гілки '\''%s'\'' (%s) — на remote поїхала неповна гілка.\n' \
      "$PR_NUMBER" "$HEAD_SHORT" "$HEAD_REF" "$LOCAL_SHORT" >&2
    if [ "$N_REWRITTEN" -gt 0 ]; then
      printf '      %s коміт(ів) гілки вже у вершині за патчем (той самий вміст, інший SHA), а %s — ні:\n' \
        "$N_REWRITTEN" "$N_LOST" >&2
    else
      printf '      у гілці є, у вершині немає — %s коміт(ів):\n' "$N_LOST" >&2
    fi
    printf '%s' "$LOST" | head -n 10 | while IFS= read -r sha; do
      [ -n "$sha" ] || continue
      printf '      ✗ %s %s\n' "${sha:0:7}" \
        "$("$GIT" -C "$REPO_ROOT" log -1 --format=%s "$sha" 2>/dev/null || true)" >&2
    done
    if [ "$N_LOST" -gt 10 ]; then
      printf '      … і ще %s\n' "$((N_LOST - 10))" >&2
    fi
    printf 'Далі: запуш повну гілку (git push --force-with-lease origin %s) і повтори scripts/merge-pr.sh %s;\n' \
      "$HEAD_REF" "$PR_NUMBER" >&2
    printf '      якщо гілку насправді ребазнули в іншому клоні з розвʼязанням конфліктів — не пушити: спершу звір вміст (git diff %s %s).\n' \
      "$HEAD_SHORT" "$LOCAL_SHORT" >&2
    exit 1
  fi
fi

# --- 4) Чеки ----------------------------------------------------------------
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

# --- 5) Вердикт огляду ------------------------------------------------------
if [ "$HAS_LABEL" != true ]; then
  printf 'FAIL  PR #%s: немає машиночитного вердикту огляду — мітки '\''%s'\'' на PR немає.\n' \
    "$PR_NUMBER" "$LABEL" >&2
  note "Ревізію $HEAD_SHORT ніхто не підтвердив незалежним оглядом (Spec + Standards)."
  note "Саме так #1215 злили з відкритим Spec FAIL — вердикт жив прозою в коментарі."
  label_probe=0
  label_exists_in_repo || label_probe=$?
  if [ "$label_probe" -eq 1 ]; then
    note "Мітки '$LABEL' немає і в репозиторії — її створює координатор (один раз):"
    note "gh label create '$LABEL' --description 'Незалежний огляд пройдено (Spec + Standards)' --color 0E8A16"
  elif [ "$label_probe" -eq 2 ]; then
    warn "не вдалося перевірити, чи існує мітка '$LABEL' у репозиторії (мережа чи права) —"
    warn "не стверджую, що її немає; якщо її справді немає, створи: gh label create '$LABEL' …"
  fi
  printf 'Далі: %s\n' "$REVIEW_REMEDY" >&2
  printf '      потім повтори: scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 1
fi

# --- 6) Вердикт новіший за те, що він засвідчує ------------------------------
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

# --- 7) Гілка має пережити мердж --------------------------------------------
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

# --- 8) Злиття --------------------------------------------------------------
MERGE_CMD=("$GH" pr merge "$PR_NUMBER" --squash --match-head-commit "$HEAD_SHA")
ok "PR #$PR_NUMBER: $CHECKS_TOTAL чек(ів) зелені; вердикт '$LABEL' від $VERDICT_TS новіший за HEAD $HEAD_SHORT і за останній прогін CI."
ok "гілку не видаляю: --delete-branch не передається."

if [ "$DRY_RUN" = true ]; then
  ok "DRY-RUN: злив би: ${MERGE_CMD[*]}"
  exit 0
fi

if ! "${MERGE_CMD[@]}" </dev/null 2>"$TMP/merge.err"; then
  # Вихід 2, а не 1: усі перевірки гейта пройдено, злити не вдалося ззовні.
  # Координатор не має прочитати це як «огляд не пройдено» (код 1).
  printf 'FAIL  перевірки пройдено, але gh pr merge не злито — PR #%s лишився як був:\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/merge.err" >&2 || true
  printf 'Далі: розбери причину вище (найчастіше HEAD зрушив після перевірки) і повтори scripts/merge-pr.sh %s\n' "$PR_NUMBER" >&2
  exit 2
fi
ok "PR #$PR_NUMBER злито squash-ом у '$BASE'; гілку не видалено."

# Робочу копію смуги гейт НЕ прибирає, і це названа в КОДІ межа, а не
# забудькуватість: копія може належати іншій смузі, а її дерево — тримати
# незакомічені правки огляду; «PR злито» не означає «в дереві нема чого
# втрачати». `git worktree remove` без `--force` відмовляє на брудному дереві,
# тож автоматичне прибирання з чужого процесу або мовчки не робило б нічого,
# або вимагало б `--force`, який стирає чужу роботу. Прибирає координатор ЯВНО:
# `new-lane.sh --release` відмовляє, якщо в копії є що втрачати.
#
# Рядок стоїть ПІСЛЯ злиття, а не до нього: вказівка прибрати копію на
# незлитому PR звільнила б смугу, яку ще нікуди не злито (#1321).
if [[ "$HEAD_REF" =~ ^t([0-9]+)- ]]; then
  ok "копію смуги прибирає координатор: scripts/new-lane.sh --release ${BASH_REMATCH[1]}"
else
  ok "копію смуги прибирає координатор: scripts/new-lane.sh --release <номер> (гілка PR '$HEAD_REF' не форми t<номер>-<slug>)."
fi
