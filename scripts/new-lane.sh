#!/usr/bin/env bash
#
# Одна команда замість трьох ручних: резервує тікет, бере свіжий `origin/main`,
# створює копію смуги й друкує факти для брифа.
#
# Навіщо. Усе в цьому репозиторії має скрипт — премиса, числа, межі, сторожі,
# бюджет, час, мердж, вердикт, — а диспетчеризація смуги була трьома командами
# руками. Саме там сталися ВСІ координаційні помилки серії: чотири хибні числа
# в брифи (бо їх згадували, а не брали командою), дві смуги в одній копії
# (`commit --amend`, `reset --hard`, стерті правки) і хибна база виміру після
# squash (`--ff-only` не спрацював, і міряли дерево гілки, не `main`). Жоден
# наявний гейт цього не ловив: усі вони дивляться на КОД, а помилки були в
# ПРОЦЕСІ. Тут процес і отримує механізм.
#
# Що робить:
#   1) `git fetch origin main` — свіжа база, а не поточний HEAD;
#   2) резервує тікет НАЯВНИМ `scripts/agent-work-claim.py` (нового механізму
#      немає; зайнятий тікет — код 2 і КОПІЇ НЕ СТВОРЕНО);
#   3) створює `.worktrees/w<номер>` від `origin/main`, гілкою `t<номер>-<slug>`
#      (з `--no-track`: інакше гілка смуги «відстежує» `origin/main`, і
#      `git status` бреше про базу — та сама хибна база, тільки тихіша);
#   4) друкує ФАКТИ ДЛЯ БРИФА — їх вставляють, а не згадують: гілку, базу
#      (`sha`), скільки записів у реєстрі сторожів (`--count`, а не рядків
#      `--list`), ліміт джоби й команди, які смуга проганяє сама.
#
# Виклик:
#   scripts/new-lane.sh <номер> <slug>
#   scripts/new-lane.sh --release <номер>
#
# `--release` — гігієна копій після мерджу: прибирає копію смуги, коли в ній
# нема чого втрачати. Гілку не видаляє НІКОЛИ (та сама межа, що в
# `merge-pr.sh`), резервування не чіпає (його знімає координатор — доки тікет
# не закрито, запис лишається).
#
# Межі названі тут, у коді, а не лише в звіті:
#   * `--release` не спрацьовує сам після мерджу, і це навмисно. Мердж-гейт не
#     знає, чия копія і чи не тримає вона правок огляду; `git worktree remove`
#     без `--force` відмовляє на брудному дереві, тож автоматичне прибирання з
#     чужого процесу або мовчки не робило б нічого, або вимагало б `--force`,
#     який стирає чужу роботу. Прибирає координатор ЯВНО — і рівно тоді, коли
#     переконався, що правок немає;
#   * гілку не видаляє жодна вітка цього скрипта: `t<номер>-<slug>` лишається
#     в `refs/heads`, як і після `merge-pr.sh`. Названа й ціна цієї межі: якщо
#     `switch -c` упаде ВЖЕ після запису ref'а, гілка лишиться, і наступна
#     спроба впреться в «гілка вже існує». Це вузьке вікно (ref пишеться
#     останнім кроком, коли все вже перевірено), і видаляти гілку тут означало б
#     завести прибирання чужого ref'а — тому межа названа, а не замовчана;
#   * `--release` читає `git` у копії ЛИШЕ коли копія — ЖИВИЙ чекаут смуги:
#     `worktree_live` вимагає запису без позначки `prunable`, а `lane_is_checkout`
#     — щоб `rev-parse --show-toplevel` у ній дорівнював їй самій. Обидві
#     перевірки потрібні, і ось чому: незареєстрована тека І застарілий
#     (prunable) запис із текою, створеною знову на тому ж шляху, однаково
#     змусили б `git -C` піднятися до БАТЬКІВСЬКОГО репозиторію й прочитати
#     дерево КООРДИНАТОРА (той самий клас, проти якого цей скрипт і писався).
#     Такий шлях скрипт або прибирає (порожню теку — `rmdir`, застарілий запис —
#     `worktree prune`), або не чіпає й каже про це; живого запису, що не є
#     чекаутом, він не чіпає взагалі — це стан, якого ми не розуміємо;
#   * механізм стає РОБОЧИМ ПІСЛЯ МЕРДЖУ, і це названо, а не замовчано: факти
#     беруться з КОПІЇ (`guard_witnesses.py --count`, `ci_job_time.py --list`), а
#     копія — це `origin/main`. Проти бази БЕЗ цього тікета створення падає
#     раніше — `guard_witnesses.py: error: unrecognized arguments: --count` — і
#     чисто відкочується (копії, резервування й гілки не лишається). Тести цього
#     не бачать: фікстура копіює скрипти з дерева самого PR, тож перевіряють
#     майбутню базу, а не сьогоднішню;
#   * резервування не знімається автоматично: у `agent-work-claim.py` навмисно
#     немає примусового звільнення, і цей скрипт його не вигадує. Він лише
#     НАЗИВАЄ шлях запису;
#   * власника резервування скрипт не вигадує: без `--owner`/`NEW_LANE_OWNER`
#     (або `DSH_SESSION_ID` у цьому середовищі) він відмовляє, бо запис із
#     вигаданим власником — це та сама обіцянка в прозі;
#   * план не створюється: скрипт лише називає шлях у резервуванні. Порожній
#     файл плану — не план, і механізм не вдає, що його написав;
#   * факти збираються з КОПІЇ (вона ж і є свіжий `origin/main`), а не з тієї
#     робочої копії, з якої скрипт запущено: саме так число належить базі
#     смуги, а не дереву координатора, яке могло відстати.
#
# Коди виходу:
#   0 — смугу створено (або копію прибрано);
#   1 — відмова: у копії є що втрачати (незакомічені зміни, незавершена
#       операція) — тільки `--release`;
#   2 — перевірити не вдалося АБО смуга зайнята: невідомий аргумент, немає
#       `git`/мережі, копія вже існує, тікет зарезервовано, база не прочитана,
#       факти не зібрано. Копія при цьому НЕ створюється: якщо її встигли
#       створити, її прибирає той самий прогін (відкат — лише те, що створив
#       він сам).
#
# Змінні (ті самі, що в тестах):
#   NEW_LANE_REPO / NEW_LANE_GIT / NEW_LANE_PYTHON / NEW_LANE_OWNER /
#   NEW_LANE_RESERVATION / NEW_LANE_PLAN
set -euo pipefail

GIT="${NEW_LANE_GIT:-git}"
PYTHON="${NEW_LANE_PYTHON:-python3}"
# Абсолютна тека самого скрипта: нею резервування кличеться з будь-якого cwd
# (нижче ми заходимо в `$REPO`), і `NEW_LANE_REPO` може вказувати на ІНШУ копію,
# у якій `agent-work-claim.py` може бути старішим або його не бути зовсім.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Тека смуг у робочій копії — константа, а не здогад: за нею копію знаходять і
# створення, і `--release`, і саме її названо в брифі.
LANES_DIR=".worktrees"

usage() {
  printf 'usage: scripts/new-lane.sh <номер> <slug> [--owner <id>] [--reservation <назва>] [--plan <шлях>]\n'
  printf '       scripts/new-lane.sh --release <номер>\n'
  printf '\n'
  printf 'Резервує тікет, створює копію смуги від свіжого origin/main і друкує факти для брифа.\n'
  printf -- '--release прибирає копію, коли в ній немає незакомічених змін (гілку не видаляє).\n'
  printf 'Власник резервування: --owner, або NEW_LANE_OWNER, або DSH_SESSION_ID.\n'
  printf 'Коди виходу: 0 — створено (прибрано); 1 — у копії є що втрачати; 2 — відмова/невідомість.\n'
}

fail() {
  printf 'FAIL  new-lane: %s\n' "$*" >&2
  exit 2
}

refuse() {
  printf 'FAIL  new-lane: %s\n' "$*" >&2
  exit 1
}

# --------------------------------------------------------------------------
# Аргументи
# --------------------------------------------------------------------------

ISSUE=""
SLUG=""
RELEASE=false
OWNER_FLAG=""
RESERVATION_FLAG=""
PLAN_FLAG=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h | --help) usage; exit 0 ;;
    --release) RELEASE=true; shift ;;
    --owner)
      OWNER_FLAG="${2:-}"
      [ -n "$OWNER_FLAG" ] || fail "--owner потребує значення"
      shift 2
      ;;
    --reservation)
      RESERVATION_FLAG="${2:-}"
      [ -n "$RESERVATION_FLAG" ] || fail "--reservation потребує значення"
      shift 2
      ;;
    --plan)
      PLAN_FLAG="${2:-}"
      [ -n "$PLAN_FLAG" ] || fail "--plan потребує значення"
      shift 2
      ;;
    -*)
      fail "невідомий прапорець: $1 (спробуй --help)"
      ;;
    *)
      if [ -z "$ISSUE" ]; then
        ISSUE="$1"
      elif [ -z "$SLUG" ]; then
        SLUG="$1"
      else
        fail "зайвий аргумент: $1"
      fi
      shift
      ;;
  esac
done

case "$ISSUE" in
  '') fail "не названо номер тікета (спробуй --help)" ;;
  *[!0-9]*) fail "номер тікета мусить бути числом: $ISSUE" ;;
esac
if [ "$ISSUE" -le 0 ]; then
  fail "номер тікета мусить бути додатним: $ISSUE"
fi

if [ "$RELEASE" = true ] && [ -n "$SLUG" ]; then
  fail "--release не бере slug: він знаходить копію за номером ($SLUG зайвий)"
fi
if [ "$RELEASE" = false ]; then
  case "$SLUG" in
    '') fail "не названо slug смуги (спробуй --help)" ;;
    *[!a-z0-9-]*) fail "slug дозволяє лише [a-z0-9-]: $SLUG" ;;
    *[a-z0-9]*) ;;
    *) fail "slug мусить мати принаймні одну літеру чи цифру: $SLUG" ;;
  esac
fi

# --------------------------------------------------------------------------
# Робоча копія, спільний git-каталог і шляхи
# --------------------------------------------------------------------------

REPO="${NEW_LANE_REPO:-}"
if [ -z "$REPO" ]; then
  REPO="$("$GIT" rev-parse --show-toplevel)" ||
    fail "не вдалося знайти корінь робочої копії (немає git?)"
fi
REPO="$(cd "$REPO" 2>/dev/null && pwd)" ||
  fail "робоча копія не знайдена: ${NEW_LANE_REPO:-$REPO}"
if [ ! -d "$REPO/.git" ] && [ ! -f "$REPO/.git" ]; then
  fail "$REPO не є робочою копією git (немає .git)"
fi

LANE="$REPO/$LANES_DIR/w$ISSUE"
BRANCH="t$ISSUE-$SLUG"

# Запис worktree Є — будь-який, і живий, і застарілий (`prunable`).
worktree_recorded() {
  # Абсолютні шляхи: `git worktree list --porcelain` друкує рівно їх.
  "$GIT" -C "$REPO" worktree list --porcelain 2>/dev/null |
    grep -Fxq "worktree $1"
}

# Запис worktree ЖИВИЙ: він Є й git НЕ позначив його `prunable`. Різниця не
# косметична: застарілий запис (теку прибрали out-of-band, а `.git/worktrees/<id>`
# лишився) друкує `prunable …`, і якщо вважати його зареєстрованим, то
# `git -C "$LANE"` у теці на тому ж шляху піднімається до БАТЬКІВСЬКОГО
# репозиторію — тобто симптом F1 повертається разом із глухим кутом.
worktree_live() {
  "$GIT" -C "$REPO" worktree list --porcelain 2>/dev/null | awk -v lane="$1" '
    /^worktree / { path = substr($0, 10); stale = 0; next }
    /^prunable/  { if (path == lane) stale = 1; next }
    /^$/         { if (path == lane && !stale) { print "yes"; exit } path = ""; next }
    END          { if (path == lane && !stale) print "yes" }
  ' | grep -qx yes
}

# Шлях — СПРАВЖНІЙ чекаут смуги, а не тека всередині батьківського репозиторію:
# `--show-toplevel` у ній дорівнює їй самій. Це друга, незалежна від запису
# перевірка — і єдина, що стоїть між `git -C "$LANE"` і деревом координатора.
lane_is_checkout() {
  [ -d "$1" ] || return 1
  [ "$("$GIT" -C "$1" rev-parse --show-toplevel 2>/dev/null)" = "$(cd "$1" && pwd -P)" ]
}

COMMON="$("$GIT" -C "$REPO" rev-parse --path-format=absolute --git-common-dir)" ||
  fail "не вдалося прочитати спільний git-каталог"
CLAIM_FILE="$COMMON/agent-work-claims/$ISSUE.json"

# --------------------------------------------------------------------------
# Відкат: прибираємо ЛИШЕ те, що створив цей самий прогін
# --------------------------------------------------------------------------

WORKTREE_CREATED=false
CLAIM_CREATED=false

rollback() {
  if [ "$WORKTREE_CREATED" = true ]; then
    # `--force` тут законний рівно тому, що копію створив ЦЕЙ прогін: перед
    # `worktree add` перевірено, що ні теки, ні реєстрації не було, тож
    # видаляти нічиє. (Факти збираються в копії, і перший же прогін
    # `guard_witnesses.py --count` лишає в ній `__pycache__`: у цьому
    # репозиторії він ігнорований, а в чужому дереві міг би й не бути — і тоді
    # `remove` без `--force` відмовив би, лишивши по собі саме ту напівкопію,
    # яку відкат і прибирає.)
    "$GIT" -C "$REPO" worktree remove --force "$LANE" 2>/dev/null ||
      "$GIT" -C "$REPO" worktree prune 2>/dev/null || true
  fi
  if [ "$CLAIM_CREATED" = true ] && [ -f "$CLAIM_FILE" ]; then
    # Прибираємо рівно той запис, який створили самі: чужий запис із тим самим
    # номером неможливий (acquire атомарний), але перевірка лишається — інакше
    # відкат був би примусовим звільненням, якого в механізмі немає.
    if grep -Fq "\"reservation\": \"$RESERVATION\"" "$CLAIM_FILE"; then
      rm -f "$CLAIM_FILE"
    fi
  fi
}

# --------------------------------------------------------------------------
# --release: прибрати копію, коли в ній нема чого втрачати
# --------------------------------------------------------------------------

if [ "$RELEASE" = true ]; then
  if [ ! -e "$LANE" ] && ! worktree_recorded "$LANE"; then
    fail "немає смуги w$ISSUE у $REPO — нема чого звільняти"
  fi
  LANE_BRANCH=""
  if worktree_live "$LANE" && lane_is_checkout "$LANE"; then
    # ЖИВИЙ чекаут смуги — і ТІЛЬКИ тут `git -C "$LANE"` читає саму копію.
    LANE_BRANCH="$("$GIT" -C "$LANE" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
    DIRTY="$("$GIT" -C "$LANE" status --porcelain)" ||
      fail "не вдалося прочитати стан копії $LANE"
    if [ -n "$DIRTY" ]; then
      printf 'FAIL  копія %s має незакомічені зміни — прибирання втратило б їх:\n' "$LANE" >&2
      printf '%s\n' "$DIRTY" | sed 's/^/      /' >&2
      refuse "закоміть або збережи їх, тоді повтори scripts/new-lane.sh --release $ISSUE"
    fi
    for marker in rebase-merge rebase-apply MERGE_HEAD; do
      if [ -e "$("$GIT" -C "$LANE" rev-parse --git-path "$marker")" ]; then
        refuse "копія $LANE посеред операції ($marker) — заверши або скасуй її, тоді повтори"
      fi
    done
    if ! "$GIT" -C "$REPO" worktree remove "$LANE"; then
      fail "git worktree remove не прибрав $LANE"
    fi
    printf 'OK    копію прибрано: %s\n' "$LANE"
  elif [ -e "$LANE" ]; then
    # Шлях Є, але чекаутом смуги НЕ є: незареєстрована тека або ЗАСТАРІЛИЙ
    # (prunable) запис, чия тека зникла й була створена знову як звичайна.
    # `git -C "$LANE"` тут піднявся б до БАТЬКІВСЬКОГО репозиторію й прочитав
    # дерево КООРДИНАТОРА — і тоді «копія має зміни» назвало б чужі зміни, а
    # номер лишився б заблокованим назавжди: створити — відмова, а підказане
    # лікування не працює. Тому тут ми НЕ кличемо git у цій теці взагалі.
    if worktree_live "$LANE"; then
      # Живий запис, але не чекаут: стан, якого ми не розуміємо, — не чіпаємо.
      refuse "шлях $LANE має живий worktree-запис, але не є чекаутом смуги — стан невідомий; розбери його руками"
    fi
    # Застарілий запис більше ні на що не вказує: прибираємо його (живих prune
    # не чіпає), щоб той самий номер не лишався зайнятим привидом.
    if ! "$GIT" -C "$REPO" worktree prune; then
      fail "не вдалося прибрати застарілий запис копії $LANE"
    fi
    if [ -d "$LANE" ] && [ -z "$(ls -A "$LANE")" ]; then
      if ! rmdir "$LANE"; then
        fail "порожню незареєстровану теку $LANE не прибрано"
      fi
      printf 'OK    порожню незареєстровану теку прибрано: %s (чекаутом смуги вона не була)\n' "$LANE"
    else
      refuse "шлях $LANE не є чекаутом смуги, але не порожній — прибирання втратило б його вміст ($(ls -A "$LANE" | tr '\n' ' ')); прибери його руками, якщо впевнений"
    fi
  else
    # Теки немає, а запис є — застарілий. Прибираємо запис, а не вдаємо, що
    # смуги не було: інакше він лишився б привидом, на який впав би `worktree add`.
    if ! "$GIT" -C "$REPO" worktree prune; then
      fail "не вдалося прибрати застарілий запис копії $LANE"
    fi
    printf 'OK    застарілий запис копії прибрано (теки не було): %s\n' "$LANE"
  fi
  printf 'OK    гілку %s не видаляю: вона лишається в refs/heads (та сама межа, що в merge-pr.sh).\n' \
    "${LANE_BRANCH:-смуги}"
  if [ -f "$CLAIM_FILE" ]; then
    printf 'резервування лишається: %s\n' "$CLAIM_FILE"
    printf '      знімає його координатор — видали файл, коли тікет закрито.\n'
  fi
  exit 0
fi

# --------------------------------------------------------------------------
# Створення смуги
# --------------------------------------------------------------------------

# Свіжа база — ДО резервування: якщо мережі немає, тікет не лишається зайнятим.
if ! "$GIT" -C "$REPO" fetch origin main; then
  fail "не вдалося оновити origin/main (мережа?) — копії не створено, тікет не зарезервовано"
fi
if ! BASE="$("$GIT" -C "$REPO" rev-parse origin/main)"; then
  fail "не вдалося прочитати origin/main — копії не створено, тікет не зарезервовано"
fi
BASE_SHORT="$("$GIT" -C "$REPO" rev-parse --short origin/main)"

# Застарілі записи (тека зникла, `.git/worktrees/<id>` лишився) прибираємо ДО
# перевірки: інакше номер вважався б зайнятим записом-привидом, а `worktree add`
# упав би на «missing but already registered worktree». `prune` живих не чіпає.
if ! "$GIT" -C "$REPO" worktree prune; then
  fail "не вдалося прибрати застарілі записи worktree — копії не створено"
fi

# Копія зайнята — відмова ДО резервування: друга смуга в ту саму копію не сідає,
# і тікет не лишається зайнятим неіснуючою смугою.
if [ -e "$LANE" ] || worktree_recorded "$LANE"; then
  fail "копія вже існує: $LANE — друга смуга в ту саму копію не сідає. Прибери її (scripts/new-lane.sh --release $ISSUE) або візьми інший номер."
fi
if "$GIT" -C "$REPO" show-ref --verify --quiet "refs/heads/$BRANCH"; then
  fail "гілка $BRANCH вже існує — це залишок попередньої смуги (гілки цей репозиторій не видаляє). Візьми інший slug або прибери залишок руками, якщо він злитий."
fi

OWNER="${OWNER_FLAG:-${NEW_LANE_OWNER:-${DSH_SESSION_ID:-}}}"
if [ -z "$OWNER" ]; then
  fail "не названо власника резервування: дай --owner <thread-id>, NEW_LANE_OWNER або DSH_SESSION_ID"
fi
RESERVATION="${RESERVATION_FLAG:-${NEW_LANE_RESERVATION:-$SLUG}}"
PLAN="${PLAN_FLAG:-${NEW_LANE_PLAN:-docs/plans/$(date -u +%F)-$ISSUE-$SLUG.md}}"

# Резервування — наявним механізмом, із його власним кодом виходу 2 на зайнятий
# тікет. Копії тут ще немає, тож «зайнято» не лишає по собі нічого.
set +e
CLAIM_OUT="$(
  cd "$REPO" &&
    "$PYTHON" "$SCRIPT_DIR/agent-work-claim.py" acquire "$ISSUE" \
      --owner "$OWNER" --reservation "$RESERVATION" --plan "$PLAN" 2>&1
)"
CLAIM_CODE=$?
set -e
if [ "$CLAIM_CODE" -ne 0 ]; then
  printf '%s\n' "$CLAIM_OUT" >&2
  fail "тікет #$ISSUE уже зарезервовано — копії не створено (код $CLAIM_CODE від agent-work-claim.py)"
fi
CLAIM_CREATED=true

if ! "$GIT" -C "$REPO" worktree add --detach "$LANE" origin/main; then
  rollback
  fail "git worktree add не вдався — копію й резервування прибрано"
fi
WORKTREE_CREATED=true

# Факти — з КОПІЇ: вона і є свіжий origin/main. Джерел два, і кожне мусить
# відповісти; інакше це невідомість (2), а не бриф із вигаданим числом.
if ! WITNESSES="$(cd "$LANE" && "$PYTHON" scripts/guard_witnesses.py --count)"; then
  rollback
  fail "не вдалося прочитати реєстр сторожів із копії (scripts/guard_witnesses.py --count) — копію прибрано"
fi
case "$WITNESSES" in
  '' | *[!0-9]*)
    rollback
    fail "guard_witnesses.py --count віддав не число: '$WITNESSES' — копію прибрано"
    ;;
esac

if ! JOB_LIST="$(cd "$LANE" && "$PYTHON" scripts/ci_job_time.py --list --root "$LANE")"; then
  rollback
  fail "не вдалося прочитати ліміт джоби з копії (scripts/ci_job_time.py --list) — копію прибрано"
fi
JOB_NAME="$(printf '%s\n' "$JOB_LIST" | sed -n 's/^джоба: \([^ ]*\).*/\1/p')"
JOB_LIMIT="$(printf '%s\n' "$JOB_LIST" | sed -n 's/^ліміт: //p')"
if [ -z "$JOB_NAME" ] || [ -z "$JOB_LIMIT" ] || [ "${JOB_LIMIT#НЕВІДОМО}" != "$JOB_LIMIT" ]; then
  rollback
  fail "ліміт джоби не прочитано з копії ('$JOB_NAME' / '$JOB_LIMIT') — копію прибрано"
fi

# Гілку смуги створюємо ОСТАННІМ кроком, коли все вже перевірено: якщо факти не
# зібралися, відкат прибирає саму копію, і жодної гілки по собі не лишається —
# інакше наступна спроба вперлася б у «гілка вже існує» від власного невдалого
# прогону. `--no-track`: гілка смуги не «відстежує» origin/main, бо тоді
# `git status` бреше про базу — та сама хибна база, тільки тихіша.
if ! "$GIT" -C "$LANE" switch -q --no-track -c "$BRANCH"; then
  rollback
  fail "не вдалося створити гілку $BRANCH у копії — копію й резервування прибрано"
fi

# --------------------------------------------------------------------------
# Факти для брифа: їх вставляють, а не згадують
# --------------------------------------------------------------------------

# Команди, які смуга проганяє САМА. Мердж сюди не входить: його запускає
# координатор, і він названий окремим рядком.
LANE_COMMANDS=(
  'python3 scripts/guard_witnesses.py'
  'python3 scripts/ci_job_time.py --check-docs'
  'python3 scripts/doc_numbers.py'
  'python3 scripts/named_boundaries.py'
  'python3 scripts/declared_fields.py'
  'python3 -m unittest discover -s scripts/tests -p "test_*.py"'
  'scripts/test-changed.sh'
)

printf 'смуга: %s\n' "$BRANCH"
printf 'копія: %s\n' "$LANE"
printf 'база: %s (%s, origin/main)\n' "$BASE_SHORT" "$BASE"
printf 'резервування: %s (owner: %s, %s)\n' "$CLAIM_FILE" "$OWNER" "$RESERVATION"
printf 'сторожів у реєстрі: %s\n' "$WITNESSES"
printf 'ліміт джоби %s: %s\n' "$JOB_NAME" "$JOB_LIMIT"
printf 'план: %s\n' "$PLAN"
printf 'команди смуги (прогнати самій):\n'
for command in "${LANE_COMMANDS[@]}"; do
  printf '  %s\n' "$command"
done
printf 'мердж: scripts/merge-pr.sh <номер PR> — зливає координатор, не `gh pr merge`\n'
printf 'звільнити копію після мерджу: scripts/new-lane.sh --release %s\n' "$ISSUE"
