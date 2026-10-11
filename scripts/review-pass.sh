#!/usr/bin/env bash
#
# Перепроставлення вердикту огляду: зняти мітку й поставити заново — однією
# операцією, щоб подія `labeled` створилася ЗАВЖДИ.
#
# Навіщо. `gh pr edit <PR> --add-label review:pass` на PR, який УЖЕ має цю
# мітку, — тиха ні-операція: GitHub не створює події `labeled`, вердикт лишає
# стару позначку часу, і `scripts/merge-pr.sh` слушно відмовляє («після вердикту
# огляду з'явилися нові коміти», «вердикт старіший за останній прогін CI»).
# #1259: координатор витратив на це ціле коло — мітку «перепроставили», а час
# лишився старий; урятувало тільки те, що гейт уміє цю тишу ловити. Тому тут
# тиша не толерується: скрипт не вірить, що подія створилася, а перевіряє це.
#
# Що робить, у цьому порядку:
#   1) перевіряє, що мітка існує в репозиторії (інакше — відмова з командою
#      `gh label create`), і читає HEAD PR — ревізію, яку вердикт засвідчить;
#   2) читає таймлайн ДО: скільки `labeled`-подій цієї мітки вже було;
#   3) знімає мітку (REST DELETE). 404 — не помилка: у PR мітки могло й не бути
#      (перший вердикт), і цей стан скрипт теж мусить обслуговувати;
#   4) ставить мітку назад (REST POST). Подія `labeled` створюється ЗАВЖДИ, бо
#      перед нею мітку знято, — саме тут на вже позначеному PR і була тиша;
#   5) читає таймлайн ПІСЛЯ і вимагає, щоб `labeled`-подій побільшало: без цього
#      мітка стоїть, але не засвідчує нічого нового, і скрипт відмовляє замість
#      того, щоб надрукувати стару позначку часу як свіжу;
#   6) друкує нову позначку часу й ревізію HEAD, яку вона засвідчує.
#
# Чому не `gh pr edit <PR> --remove-label X --add-label X`. Це покладалося б на
# те, у якому порядку gh виконає обидві мутації, і на те, що він не згорне їх в
# одну. Тут порядок — частина механізму, тож він записаний явно двома
# REST-викликами: спершу DELETE, потім POST. Мітка зникає з PR лише на час між
# ними; якщо POST не вдався, скрипт каже це прямо (код 2) і називає стан, у
# якому PR лишився, — мовчазного «ніби все гаразд» тут немає.
#
# Межі, названі прямо. HEAD читається ДО мутацій: push у вікні між читанням і
# POST у надрукований рядок не потрапить — наслідків для мерджу це не має, бо
# гейт судить за часом події та `--match-head-commit` і новий коміт побачить
# сам. Перевірка (5) читає таймлайн ОДРАЗУ після POST: якщо GitHub віддає
# таймлайн із затримкою, скрипт відмовить (код 1), хоч подію й створено;
# повторний запуск безпечний — він знову зніме й поставить мітку, а подія, якої
# не побачив перший прогін, уже в таймлайні. Повторів із паузою тут немає
# навмисно: сон у скрипті — це або флакі-тест, або непомічена тиша.
#
# Вердикт ставить оглядач, а не автор PR (docs/agents/work-coordination.md,
# «Вердикт огляду і мердж»); скрипт лише робить це перепроставлення машинним.
#
# Виклик: scripts/review-pass.sh <номер-PR>
# Змінні: REVIEW_PASS_GH — шлях до gh (типово `gh`; потрібен для тестів),
#         REVIEW_PASS_LABEL — назва мітки вердикту (типово `review:pass`).
# Коди виходу: 0 — вердикт перепроставлено; 1 — відмова (мітки немає в
#              репозиторії; події не створено); 2 — зробити не вдалося
#              (аргументи, gh, мережа, стан мітки невідомий).
set -euo pipefail

GH="${REVIEW_PASS_GH:-gh}"
LABEL="${REVIEW_PASS_LABEL:-review:pass}"

usage() {
  printf 'usage: scripts/review-pass.sh <номер-PR>\n'
  printf '\n'
  printf "Перепроставляє вердикт огляду: знімає мітку '%s' і ставить назад,\n" "$LABEL"
  printf 'щоб подія `labeled` створилася завжди, і друкує нову позначку часу\n'
  printf 'разом із HEAD-ревізією, яку вона засвідчує.\n'
  printf 'Іншу назву мітки дає REVIEW_PASS_LABEL.\n'
  printf 'Коди виходу: 0 — вердикт перепроставлено; 1 — відмова;\n'
  printf '             2 — зробити не вдалося (аргументи, gh, мережа).\n'
}

PR_NUMBER=""
for argument in "$@"; do
  case "$argument" in
    -h | --help) usage; exit 0 ;;
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
note() { printf '      %s\n' "$*" >&2; }
refuse() { printf 'FAIL  %s\n' "$1" >&2; printf 'Далі: %s\n' "$2" >&2; exit 1; }

# Розбір JSON gh окремою програмою: дані з GitHub не стають кодом, а невдача
# розбору — це код 2, а не «порожньо». Нечитабельний JSON і не та форма
# відповіді називаються людською мовою: сирий python-трейсбек у stderr ПЕРЕД
# рядком FAIL ховав би причину (зауваження огляду PR #1262).
pr_facts() { # pr_facts <файл pr view> → head_sha=… head_ref=…
  python3 - "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    text = handle.read().strip()
try:
    pr = json.loads(text) if text else {}
except ValueError as error:
    sys.exit("відповідь gh про PR — не JSON: %s" % error)
if not isinstance(pr, dict):
    sys.exit("відповідь gh про PR — не об'єкт JSON, а %s" % type(pr).__name__)
print("head_sha=%s" % (pr.get("headRefOid") or ""))
print("head_ref=%s" % (pr.get("headRefName") or ""))
PY
}

# Таймлайн — єдине місце, де видно, чи подія СПРАВДІ створилася. Рахуємо лише
# `labeled` цієї мітки З ПОЗНАЧКОЮ ЧАСУ: подія без часу не вердикт, і гейт її
# теж не читає. Сторінка-об'єкт — це не «подій немає», а форма, якої API не
# віддає: ковтнути її означало б порахувати нуль і на цьому «довести» тишу —
# рівно та діра, що вже була в merge-pr.sh (#1233). Нечитабельний JSON і не та
# форма відповіді називаються людською мовою, а не трейсбеком (PR #1262).
timeline_facts() { # timeline_facts <файл> → count=… newest=…
  python3 - "$1" "$LABEL" <<'PY'
import json
import sys
from datetime import datetime

with open(sys.argv[1], encoding="utf-8") as handle:
    text = handle.read().strip()
try:
    pages = json.loads(text) if text else []
except ValueError as error:
    sys.exit("таймлайн gh api --slurp — не JSON: %s" % error)
if not isinstance(pages, list):
    sys.exit("таймлайн gh api --slurp: не список сторінок, а %s" % type(pages).__name__)

events = []
for page in pages:
    if isinstance(page, list):
        events.extend(page)
    else:
        shape = sorted(page)[:8] if isinstance(page, dict) else page
        sys.exit("несподівана форма сторінки таймлайну: %r" % (shape,))


def moment(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


label = sys.argv[2]
verdicts = []
for event in events:
    if event.get("event") != "labeled":
        continue
    if (event.get("label") or {}).get("name") != label:
        continue
    when = moment(event.get("created_at"))
    if when:
        verdicts.append((when, str(event["created_at"])))
print("count=%d" % len(verdicts))
print("newest=%s" % (max(verdicts)[1] if verdicts else ""))
PY
}

# --- 1) Мітка існує в репозиторії -------------------------------------------
# Мітка може стояти на PR, але не існувати в репозиторії (її знесли), і навпаки.
# Без мітки в репозиторії POST не спрацює, і єдине, що тут зарадить, — підказка
# «створи». Три стани: 0 — є, 1 — 404 (немає), 2 — не вдалося спитати (мережа,
# права); невідомість не сміє читатися як «немає».
label_exists_in_repo() {
  local probe
  probe="$("$GH" api "repos/{owner}/{repo}/labels/$LABEL" 2>&1 >/dev/null)" && return 0
  case "$probe" in
    *"HTTP 404"* | *"Not Found"*) return 1 ;;
  esac
  return 2
}

probe_status=0
label_exists_in_repo || probe_status=$?
if [ "$probe_status" -eq 1 ]; then
  printf "FAIL  мітки '%s' немає в репозиторії — перепроставляти нічого.\n" "$LABEL" >&2
  printf 'Далі: створи її (один раз) і повтори scripts/review-pass.sh %s\n' "$PR_NUMBER" >&2
  printf "      gh label create '%s' --description 'Незалежний огляд пройдено (Spec + Standards)' --color 0E8A16\n" \
    "$LABEL" >&2
  exit 1
fi
if [ "$probe_status" -eq 2 ]; then
  printf "FAIL  не вдалося перевірити, чи існує мітка '%s' у репозиторії (мережа чи права) — стан невідомий.\n" \
    "$LABEL" >&2
  printf 'Далі: перевір `gh auth status` і повтори scripts/review-pass.sh %s; PR не змінено.\n' \
    "$PR_NUMBER" >&2
  exit 2
fi

# --- 2) HEAD PR: ревізія, яку вердикт засвідчить ----------------------------
if ! "$GH" pr view "$PR_NUMBER" --json headRefOid,headRefName >"$TMP/pr.json" 2>"$TMP/pr.err"; then
  printf 'FAIL  не вдалося прочитати PR #%s через gh:\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/pr.err" >&2 || true
  printf 'Далі: перевір номер PR і автентифікацію (`gh auth status`); PR не змінено.\n' >&2
  exit 2
fi

if ! PR_FACTS="$(pr_facts "$TMP/pr.json")"; then
  printf 'FAIL  не вдалося розібрати відповідь gh про PR #%s — це невідомість, а не «порожньо».\n' \
    "$PR_NUMBER" >&2
  printf 'Далі: повтори scripts/review-pass.sh %s; якщо повторюється — глянь `gh pr view %s --json headRefOid,headRefName`.\n' \
    "$PR_NUMBER" "$PR_NUMBER" >&2
  exit 2
fi

HEAD_SHA="" HEAD_REF=""
while IFS='=' read -r key value; do
  case "$key" in
    head_sha) HEAD_SHA="$value" ;;
    head_ref) HEAD_REF="$value" ;;
  esac
done <<<"$PR_FACTS"

# Порожня відповідь gh — це не «PR без HEAD», а відсутність даних: без ревізії
# вердикт засвідчував би невідомо що, а вгадувати заборонено.
if [ -z "$HEAD_SHA" ] || [ -z "$HEAD_REF" ]; then
  printf 'FAIL  gh не повернув HEAD PR #%s (порожня відповідь) — невідомо, яку ревізію вердикт засвідчить.\n' \
    "$PR_NUMBER" >&2
  printf 'Далі: повтори; якщо повторюється — подивись `gh pr view %s --json headRefOid,headRefName`; PR не змінено.\n' \
    "$PR_NUMBER" >&2
  exit 2
fi
HEAD_SHORT="${HEAD_SHA:0:7}"

# --- 3) Таймлайн ДО: скільки вердиктів уже було -----------------------------
if ! "$GH" api --paginate --slurp "repos/{owner}/{repo}/issues/$PR_NUMBER/timeline" \
  >"$TMP/before.json" 2>"$TMP/before.err"; then
  printf 'FAIL  не вдалося прочитати таймлайн PR #%s (gh api):\n' "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/before.err" >&2 || true
  printf 'Далі: перевір `gh auth status` і що gh ≥ 2.43 (--slurp); PR не змінено.\n' >&2
  exit 2
fi
if ! BEFORE_FACTS="$(timeline_facts "$TMP/before.json")"; then
  printf 'FAIL  не вдалося розібрати таймлайн PR #%s (до зміни мітки) — невідомо, чи подія створилася.\n' \
    "$PR_NUMBER" >&2
  printf 'Далі: повтори scripts/review-pass.sh %s; PR не змінено.\n' "$PR_NUMBER" >&2
  exit 2
fi

COUNT_BEFORE=0
while IFS='=' read -r key value; do
  case "$key" in
    count) COUNT_BEFORE="$value" ;;
  esac
done <<<"$BEFORE_FACTS"

# --- 4) Зняти мітку ---------------------------------------------------------
# 404 тут — штатний стан «на PR мітки немає» (перший вердикт), а не помилка.
# Будь-яка інша відмова лишає стан мітки невідомим: чи знято її — не знати, і
# вгадувати не можна.
remove_label() {
  local output=""
  if output="$("$GH" api -X DELETE "repos/{owner}/{repo}/issues/$PR_NUMBER/labels/$LABEL" 2>&1)"; then
    return 0
  fi
  case "$output" in
    *"HTTP 404"* | *"Not Found"*) return 1 ;;
  esac
  return 2
}

REMOVED=0
remove_label || REMOVED=$?
case "$REMOVED" in
  0) note "мітку '$LABEL' знято — тепер її буде поставлено заново." ;;
  1) note "мітки '$LABEL' на PR не було — це перший вердикт, а не помилка." ;;
  *)
    printf "FAIL  не вдалося зняти мітку '%s' з PR #%s (не 404) — невідомо, чи вона ще стоїть.\n" \
      "$LABEL" "$PR_NUMBER" >&2
    printf 'Далі: перевір `gh auth status` і стан мітки (`gh pr view %s --json labels`), тоді повтори scripts/review-pass.sh %s.\n' \
      "$PR_NUMBER" "$PR_NUMBER" >&2
    exit 2
    ;;
esac

# --- 5) Поставити мітку заново ----------------------------------------------
# Саме цей POST створює подію `labeled`: мітки на PR уже немає.
if ! "$GH" api -X POST "repos/{owner}/{repo}/issues/$PR_NUMBER/labels" \
  -f "labels[]=$LABEL" >"$TMP/add.json" 2>"$TMP/add.err"; then
  printf "FAIL  мітку '%s' знято, але поставити не вдалося — PR #%s лишився БЕЗ вердикту.\n" \
    "$LABEL" "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/add.err" >&2 || true
  printf 'Далі: розбери причину вище і повтори scripts/review-pass.sh %s (повтор безпечний: він знову зніме й поставить мітку).\n' \
    "$PR_NUMBER" >&2
  exit 2
fi

# --- 6) Подія створилася? ---------------------------------------------------
if ! "$GH" api --paginate --slurp "repos/{owner}/{repo}/issues/$PR_NUMBER/timeline" \
  >"$TMP/after.json" 2>"$TMP/after.err"; then
  printf "FAIL  мітку '%s' перепроставлено, але таймлайн PR #%s прочитати не вдалося — нової позначки часу не підтверджено.\n" \
    "$LABEL" "$PR_NUMBER" >&2
  sed 's/^/      /' "$TMP/after.err" >&2 || true
  printf 'Далі: подивись таймлайн сам (gh api --paginate --slurp repos/{owner}/{repo}/issues/%s/timeline) або повтори scripts/review-pass.sh %s.\n' \
    "$PR_NUMBER" "$PR_NUMBER" >&2
  exit 2
fi
if ! AFTER_FACTS="$(timeline_facts "$TMP/after.json")"; then
  printf 'FAIL  не вдалося розібрати таймлайн PR #%s (після зміни мітки) — чи подія створилася, невідомо.\n' \
    "$PR_NUMBER" >&2
  printf 'Далі: повтори scripts/review-pass.sh %s; повтор безпечний.\n' "$PR_NUMBER" >&2
  exit 2
fi

COUNT_AFTER=0 NEWEST_AFTER=""
while IFS='=' read -r key value; do
  case "$key" in
    count) COUNT_AFTER="$value" ;;
    newest) NEWEST_AFTER="$value" ;;
  esac
done <<<"$AFTER_FACTS"

# Мітка стоїть — але вердикт засвідчує те, що засвідчував. Саме цю тишу #1259 і
# коштував кола роботи, тож вона тут — відмова, а не «майже вдалося».
if [ "$COUNT_AFTER" -le "$COUNT_BEFORE" ]; then
  refuse "PR #$PR_NUMBER: мітку '$LABEL' перепроставлено, але labeled-подій не побільшало ($COUNT_BEFORE → $COUNT_AFTER) — подія не створилася, і вердикт засвідчує стару позначку часу." \
    "повтори scripts/review-pass.sh $PR_NUMBER (повтор безпечний); якщо повторюється — глянь таймлайн: gh api --paginate --slurp repos/{owner}/{repo}/issues/$PR_NUMBER/timeline"
fi

# --- 7) Що саме засвідчено --------------------------------------------------
ok "PR #$PR_NUMBER: вердикт '$LABEL' перепроставлено о $NEWEST_AFTER (labeled-подій $COUNT_BEFORE → $COUNT_AFTER)."
ok "він засвідчує HEAD $HEAD_SHORT (гілка $HEAD_REF)."
