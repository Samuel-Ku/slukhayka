#!/usr/bin/env bash
#
# Віддає один тікет одному воркеру сварму й повертає результат.
#
# Навіщо саме так. Ядро DSH не має протоколу «машина→машина» (перевірено на
# 0.2.0-rc.2: немає ні worker-протоколу, ні ssh-бекенду для subprocess/fs, а
# `--host 0.0.0.0` відхиляється як RCE у мережу). Єдиний наявний канал —
# `dsh --profile headless`: задача зі stdin, фінальний текст у stdout, вердикт
# кодом виходу. Тому hub тримає ssh-процес, сам пише резервування (він єдиний
# координатор), сам коментує GitHub і сам ріже задачу за часом — бо ні таймауту
# сесії, ні ліміту ходів у ядрі немає.
#
# Межі, названі тут:
#   * локальний реєстр резервувань між машинами НЕ синхронізується
#     (docs/agents/work-coordination.md): джерело правди для віддалених
#     виконавців — коментар у GitHub, який пише ЛИШЕ hub;
#   * плани тікета в резервуванні не вигадуються: поле лишається `null`, а бриф
#     їде коментарем і файлом промпта (new-lane.sh #1337 карає вигаданий шлях);
#   * lease/heartbeat — властивість hub-процесу, а не агента: якщо hub помер, це
#     видно саме як пропущені heartbeat-коментарі. Невдала диспетчеризація
#     (воркер недоступний, таймаут, немає SWARM-RESULT, роботи не зʼявилося на
#     origin) звільняє резервування сама — і на виході, і на сигналі
#     (SIGTERM/SIGHUP через EXIT-пастку). Лишається рівно один випадок, який
#     код накрити не може: SIGKILL або падіння машини — тоді запис
#     `.git/agent-work-claims/<n>.json` знімає координатор вручну, переконавшись,
#     що роботи немає;
#   * робота вважається зробленою тільки після пушу гілки з зеленими цільовими
#     тестами; частковий результат лишається в лозі й у коментарі.
#
# Виклик:
#   scripts/swarm/run-issue.sh <номер> <worker-host> [опції]
# Опції:
#   --slug <slug>        суфікс гілки (типово: ticket-<номер>)
#   --timeout-min <N>    жорсткий ліміт задачі (типово 90)
#   --owner <id>         власник резервування (типово swarm-hub)
#   --dry-run            надрукувати промпт і точну команду, нічого не змінивши
#
# Коди виходу: код виконавця; 2 — перевірка не пройшла; 3 — воркер недоступний.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

ISSUE="${1:-}"; WORKER="${2:-}"; shift 2 2>/dev/null || true
[ -n "$ISSUE" ] && [ -n "$WORKER" ] || { echo "usage: $0 <номер> <worker-host> [--slug s] [--timeout-min N] [--owner id] [--dry-run]" >&2; exit 2; }

SLUG=""; TIMEOUT_MIN=90; OWNER="${SWARM_OWNER:-swarm-hub}"; DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --slug) SLUG="${2:?}"; shift 2 ;;
    --timeout-min) TIMEOUT_MIN="${2:?}"; shift 2 ;;
    --owner) OWNER="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SLUG" ] || SLUG="ticket-$ISSUE"

# Який ключ уживає сварм. Явний SWARM_SSH_KEY, далі ключ із правильним підписом
# (stealth@omarchy), далі історичний id_ed25519. Історичний ключ має в коментарі
# чуже ім'я (`rorschach@socanalyst`), і саме це вводило в оману: підпис у файлі —
# лише ярлик, автентифікація йде за матеріалом ключа. Щоб ярлик не плутав,
# типовий вибір — новий ключ, а старий лишається запасним.
SWARM_SSH_KEY="${SWARM_SSH_KEY:-}"
if [ -z "$SWARM_SSH_KEY" ]; then
  for k in "$HOME/.ssh/id_ed25519_omarchy" "$HOME/.ssh/id_ed25519"; do
    [ -f "$k" ] && { SWARM_SSH_KEY="$k"; break; }
  done
fi
[ -n "$SWARM_SSH_KEY" ] || { echo "[swarm] FAIL: не знайшов ssh-ключа (SWARM_SSH_KEY, ~/.ssh/id_ed25519_omarchy, ~/.ssh/id_ed25519)" >&2; exit 2; }

# `/etc/ssh/ssh_config.d/*` на hub має власника nobody:nobody (один файл — 777),
# і штатний ssh падає «Bad owner or permissions». Обхід — локальний, не змінює
# системних файлів: явні опції замість конфіга.
SSH_BASE=(-F /dev/null -o BatchMode=yes -o ConnectTimeout=10
          -o StrictHostKeyChecking=accept-new -o User="${SWARM_WORKER_USER:-semen}"
          -i "$SWARM_SSH_KEY")
# Для git окремий рядок і ОКРЕМИЙ ключ: github-адреса сама несе `git@`, а явний
# користувач перебив би його — git пішов би як `semen@github.com` і дістав
# «Permission denied (publickey)». Головне інше: новий ключ `stealth@omarchy`
# на GitHub НЕ зареєстрований, тож підмінити ним git означало б зламати
# fetch/push. Тому git уживає ключ акаунта (`SWARM_GIT_SSH_KEY`, типово
# id_ed25519 — саме він дає «Hi Samuel-Ku!»), а ssh до воркера — новий ключ.
SWARM_GIT_SSH_KEY="${SWARM_GIT_SSH_KEY:-$HOME/.ssh/id_ed25519}"
export GIT_SSH_COMMAND="ssh -F /dev/null -i $SWARM_GIT_SSH_KEY -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"

# Логи — в робочій теці: у сесії з політикою workspace-write запис поза нею
# заборонений, і типовий ~/.swarm-logs просто не створився б.
LOG_DIR="${SWARM_LOG_DIR:-$REPO_ROOT/.cache-tmp/swarm-logs}"
mkdir -p "$LOG_DIR"
# mktemp типово бере /tmp, а в цій сесії /tmp веде в недоступний ~/.dsh-tmp
# («Read-only file system»). Тому тимчасові файли живуть поруч із логами.
export TMPDIR="$LOG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="$LOG_DIR/$ISSUE-$WORKER-$STAMP.log"
PROMPT="$LOG_DIR/$ISSUE-$WORKER-$STAMP.prompt.txt"

say() { printf '[swarm] %s\n' "$*"; }
fail() { printf '[swarm] FAIL: %s\n' "$*" >&2; exit 2; }

# --- 1. свіжа база й факти тікета -----------------------------------------
say "fetch origin main"
git fetch origin main --quiet || fail "не прочитав origin/main"
BASE="$(git rev-parse origin/main)"
say "base origin/main = ${BASE:0:12}"

ISSUE_JSON="$(mktemp)"
gh issue view "$ISSUE" --json number,title,body,labels,state >"$ISSUE_JSON" 2>/dev/null \
  || fail "не прочитав тікет #$ISSUE (gh?)"
ISSUE_STATE="$(jq -r .state "$ISSUE_JSON")"
ISSUE_TITLE="$(jq -r .title "$ISSUE_JSON")"
[ "$ISSUE_STATE" = "OPEN" ] || fail "тікет #$ISSUE не відкритий ($ISSUE_STATE)"

# Тікет, у якому вже є відкритий PR, не беремо: це друга копія тієї самої роботи.
if gh pr list --state open --limit 60 --json number,title,headRefName 2>/dev/null \
   | jq -e --arg n "$ISSUE" '.[] | select((.title + " " + .headRefName) | test("(?i)(#|/)" + $n + "([^0-9]|$)"))' >/dev/null; then
  say "УВАГА: на #$ISSUE вже схоже є відкритий PR"
fi

if [ "$DRY_RUN" -eq 0 ]; then
  STATUS="$(python3 scripts/agent-work-claim.py status "$ISSUE" 2>/dev/null || true)"
  case "$STATUS" in
    *"$ISSUE"*) fail "тікет #$ISSUE уже зарезервований локально: $STATUS" ;;
  esac
fi

# --- 2. промпт: контракт задачі (окремий скрипт — його покриває тест) -----
bash scripts/swarm/build-prompt.sh \
  --issue "$ISSUE" --worker "$WORKER" --slug "$SLUG" --base "$BASE" \
  --owner "$OWNER" --timeout-min "$TIMEOUT_MIN" \
  <"$ISSUE_JSON" >"$PROMPT" || fail "не зібрав промпт"

# Кожен запуск має ім'я й лишає свій PID, бо втрата ssh-каналу НЕ вбиває
# віддаленого агента: перевірено дослідом — локальний кеп гасить ssh, а `dsh` і
# його `sleep` далі живуть і палять токени. Тому:
#   * віддалений shell сам записує свій PID у ~/.swarm-jobs/<tag>.pid і робить
#     `exec` — PID лишається PID-ом групи, тож `kill -- -PID` накриває все
#     дерево (timeout, dsh, діти);
#   * hub гасить цю групу ЯВНО після кожного запуску (нижче), і робить це
#     навіть тоді, коли задача завершилась сама — повторне вбивство мертвої
#     групи нічого не робить.
JOB_TAG="$ISSUE-$WORKER-$STAMP"
REMOTE_JOB='source $HOME/swarm/env.sh && cd "$REPO_DIR" && mkdir -p $HOME/.swarm-jobs'\
' && echo $$ > $HOME/.swarm-jobs/'"$JOB_TAG"'.pid'\
' && exec nice -n 10 ionice -c2 -n7 env DSH_PERMISSION_MODE=danger-full-access timeout -k 30 '"$TIMEOUT_MIN"'m dsh --profile headless -'
REMOTE_STOP='p=$HOME/.swarm-jobs/'"$JOB_TAG"'.pid; if [ -f "$p" ]; then pid=$(cat "$p");'\
' if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then'\
' if grep -qa dsh /proc/"$pid"/cmdline 2>/dev/null; then'\
' kill -TERM -"$pid" 2>/dev/null; sleep 2; kill -KILL -"$pid" 2>/dev/null; echo "swarm-stop: групу $pid погашено";'\
' else echo "swarm-stop: pid $pid не наш — не чіпаю"; fi;'\
' else echo "swarm-stop: група вже мертва"; fi; rm -f "$p"; fi'
SSH_CMD=(ssh -T "${SSH_BASE[@]}" "$WORKER" "$REMOTE_JOB")

if [ "$DRY_RUN" -eq 1 ]; then
  say "DRY-RUN: промпт -> $PROMPT"
  cat "$PROMPT"
  # Друком у машиночитному вигляді: ці два рядки — єдиний шов, який тест може
  # перевірити без мережі, gh і ssh (scripts/tests/test_swarm_dispatch.py).
  say "REMOTE_JOB=$REMOTE_JOB"
  say "REMOTE_STOP=$REMOTE_STOP"
  say "DRY-RUN: команда -> ${SSH_CMD[*]}"
  exit 0
fi

# --- 3. резервування hub-а + оголошення в GitHub --------------------------
say "reserve #$ISSUE locally as $OWNER"
# `--plan` НЕ передаємо: поле запису за конвенцією (#1337/#1340) або називає
# шлях, що існує в origin/main як blob, або null. Бриф задачі — `$PROMPT` —
# hub-локальний, незакомічений і тимчасовий, тож у СПІЛЬНОМУ реєстрі він був би
# саме тим записом, який карає new-lane.sh. У origin/main `--plan`
# необов'язковий (default=None), і без нього запис чесно отримує null.
python3 scripts/agent-work-claim.py acquire "$ISSUE" --owner "$OWNER" \
  --reservation "swarm-$WORKER" >/dev/null \
  || fail "резервування не вдалося (код 2 = зайнято; якщо копія agent-work-claim.py старіша за origin/main — онови дерево)"

# Резервування, яке ніщо не знімає, назавжди блокує тікет: у пілоті невдала
# диспетчеризація #1286 (протермінований ключ, exit 1 за 0 хв) лишила запис, і
# повторний запуск упав на власній перевірці вище. Тому якщо роботи фактично не
# було — запис знімаємо самі. Перед зняттям перевіряємо, що файл САМЕ НАШ:
# інакше після перевикористання імені чи чужого запуску ми зняли б чуже.
release_claim() {
  # Адресу беремо ТАК САМО, як `agent-work-claim.py acquire`: спільний каталог
  # через git, а не `$REPO_ROOT/.git`. У звʼязаній смузі `.git` — це ФАЙЛ, тож
  # шлях `$REPO_ROOT/.git/...` не існує, і функція мовчки казала б «звільняти
  # нічого», лишаючи запис (знахідка огляду #1351, п.4).
  local common f
  common="$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
  [ -n "$common" ] || common="$REPO_ROOT/.git"
  f="$common/agent-work-claims/$ISSUE.json"
  [ -f "$f" ] || { say "резервування немає — звільняти нічого"; return 0; }
  if jq -e --arg o "$OWNER" --arg r "swarm-$WORKER" \
       '.ownerThread == $o and .reservation == $r' "$f" >/dev/null 2>&1; then
    rm -f "$f" && say "резервування #$ISSUE знято (роботи не було)"
  else
    say "УВАГА: запис резервування не наш — лишаю як є (зніме координатор)"
  fi
}

gh issue comment "$ISSUE" --body "$(printf '%s\n' \
  "<!-- swarm:claim -->" \
  "**Сварм:** тікет узято на воркері \`$WORKER\` (hub $(hostname), власник \`$OWNER\`)." \
  "" \
  "- база: \`origin/main ${BASE:0:12}\`" \
  "- гілка: \`t$ISSUE-$SLUG\`" \
  "- heartbeat кожні 5 хв від hub-процесу; поки hub живий, тікет зайнятий" \
  "- якщо диспетчеризація не дала роботи (воркер недоступний, таймаут, немає SWARM-RESULT) — раннер знімає резервування сам; це видно в коментарі результату" \
  "- бриф задачі — \`$PROMPT\` (hub-локальний; у спільному реєстрі не зберігається, там \`plan: null\`)" \
  "- Задача ріжеться жорстко о ${TIMEOUT_MIN} хв")" >/dev/null

# --- 4. heartbeat, поки живе процес --------------------------------------
heartbeat() {
  local started=$1
  while sleep 300; do
    gh issue comment "$ISSUE" --body "<!-- swarm:heartbeat --> swarm/$WORKER: задача виконується, $(( ($(date +%s) - started) / 60 )) хв" >/dev/null 2>&1 || true
  done
}
STARTED="$(date +%s)"
say "dispatch -> $WORKER (timeout ${TIMEOUT_MIN}m), log: $LOG"
heartbeat "$STARTED" & HB_PID=$!
# Звільнення висить на EXIT-пастці, а не лише на хвості скрипта: hub, убитий
# сигналом посеред диспетчу (SIGTERM/SIGHUP), до хвоста не дійде, а запис
# лишиться назавжди й заблокує тікет для того самого скрипта — рівно пастка, на
# якій спіткнувся #1286 (знахідка огляду #1351, п.1). Змінні на цей момент
# можуть бути не встановлені, тож беремо `${RC:-1}`: невідомість трактуємо як
# невдачу. SIGKILL накрити неможливо — такий запис знімає координатор вручну
# (це названо в docs/agents/swarm.md).
trap 'kill "$HB_PID" 2>/dev/null || true
      if [ "${RC:-1}" -ne 0 ] || [ -z "${RESULT_LINE:-}" ] || [ "${BRANCH_PRESENT:-0}" -eq 0 ]; then
        release_claim
      fi' EXIT

set +e
"${SSH_CMD[@]}" <"$PROMPT" 2>&1 | tee "$LOG"
RC="${PIPESTATUS[0]}"
set -e
kill "$HB_PID" 2>/dev/null || true

ELAPSED="$(( ($(date +%s) - STARTED) / 60 ))m"
RESULT_LINE="$(grep -E '^SWARM-RESULT:' "$LOG" | tail -1 || true)"
say "exit=$RC, elapsed=${ELAPSED}, ${RESULT_LINE:-без SWARM-RESULT}"

# --- 4a. backstop: погасити віддалену групу, якщо вона пережила обрив -----
# Нормальний шлях (спрацював віддалений timeout) не лишає нічого; цей крок
# потрібен рівно для обриву каналу — тоді віддалений агент живий і коштує
# грошей. Ідемпотентно: мертву групу вбити неможливо, і це названо в лозі.
say "backstop: перевіряю віддалену групу"
ssh -T "${SSH_BASE[@]}" "$WORKER" "$REMOTE_STOP" 2>&1 | sed 's/^/  /' \
  || say "УВАГА: не вдалося прибрати віддалену групу (воркер недоступний?)"

# --- 5. що саме приїхало: гілка на origin --------------------------------
BRANCH_INFO="(гілки t$ISSUE-$SLUG на origin не видно)"
BRANCH_PRESENT=0
if git ls-remote --exit-code origin "refs/heads/t$ISSUE-$SLUG" >/dev/null 2>&1; then
  BRANCH_PRESENT=1
  git fetch origin "t$ISSUE-$SLUG" --quiet 2>/dev/null || true
  BRANCH_INFO="$(git log --oneline "origin/main..FETCH_HEAD" 2>/dev/null | head -10 || true)"
fi

gh issue comment "$ISSUE" --body "$(printf '%s\n' \
  "<!-- swarm:result -->" \
  "**Сварм:** задача на \`$WORKER\` завершилась, код виходу \`$RC\`, час \`$ELAPSED\`." \
  "" \
  "${RESULT_LINE:-_агент не надрукував SWARM-RESULT_}" \
  "" \
  '```' "$BRANCH_INFO" '```' \
  "Резервування: $([ "$BRANCH_PRESENT" -eq 1 ] && echo 'лишається — гілка є, чекає огляду й мерджу' || echo 'знято — роботи на origin не зʼявилося')." \
  "Повна лога: \`$LOG\`")" >/dev/null

# Звільнення робить EXIT-пастка (див. вище) — тут лише вихідний код.

if [ "$RC" -eq 255 ]; then say "воркер недоступний (ssh 255)"; exit 3; fi
exit "$RC"
