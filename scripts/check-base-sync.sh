#!/usr/bin/env bash
#
# Ґард «гілка розійшлася з base»: падає РАНІШЕ, ніж GitHub покаже конфлікт.
#
# Навіщо. PR #1160 виглядав здоровим, поки main рухався: CI зелений,
# `mergeable=MERGEABLE` — а розсинхрон став видно лише тоді, коли конфлікт уже
# матеріалізувався (три PR, #1169/#1170/#1171, пішли в main, і тоді гілка
# показала два конфліктні хунки в каталозі нагород). GitHub перераховує
# мержабельність ліниво, тож «нічого не видно» не означає «все гаразд».
#
# Що робить ґард на КОЖНОМУ PR:
#
#   1) пробне злиття base+head у сховищі об'єктів
#      (`git merge-tree --write-tree`; робоче дерево й індекс не чіпаються):
#      якщо злиття конфліктне — падає тут же, з переліком конфліктних файлів,
#      не чекаючи перерахунку мержабельності на боці GitHub;
#   2) вік розсинхрону — скільки годин минуло з merge-base, тобто з коміта,
#      який у гілці стоїть на місці верхівки base («коли ви востаннє бачили
#      main»). Довше за BASE_SYNC_MAX_AGE_HOURS (типово 72 год) — падає;
#   3) спільні файли: base і гілка правили ті самі файли, хоч злиття й чисте —
#      попередження. Саме так виникла семантична колізія #1160: обидва боки
#      правили AchievementEvaluator/AchievementNotice, текст злився без
#      маркерів, а семантика зіткнулась (ключі метрик і назви нагород).
#
# Політики «гілка мусить стояти рівно на верхівці base» тут немає: відстати —
# сигнал, а не заборона, і гілки цього репозиторію свідомо зливають main у себе
# перед мержем (конвенція «merge: підтягнути main … у гілку»). Калібрування
# порога: main бере ~24 коміти на добу (727 за останні 30 днів, 75 за 7), тож
# 72 год — це «пропущено ~три доби main», а не «main щойно рушив». Хочете
# щоденну синхронізацію — виставте репозиторну змінну
# BASE_SYNC_MAX_AGE_HOURS=24; `0` лишає тільки пробне злиття й попередження.
#
# Виклик: scripts/check-base-sync.sh <base-ref> [head-ref]
set -euo pipefail

BASE="${1:?usage: check-base-sync.sh <base-ref> [head-ref]}"
HEAD_REF="${2:-HEAD}"
MAX_AGE_HOURS="${BASE_SYNC_MAX_AGE_HOURS:-72}"

# Підказка для людини завжди має форму `git merge origin/<base>` — незалежно
# від того, чи виклик прийшов як `origin/main` (CI), чи як `main` (локально).
case "$BASE" in
  origin/*) MERGE_HINT="git merge $BASE" ;;
  *)        MERGE_HINT="git merge origin/$BASE" ;;
esac
MAX_LIST=30

# Обрізає перелік: 460 конфліктних файлів у лог не влізуть, а перших досить.
list_paths() {
  local shown=0 total
  total=$(printf '%s\n' "$1" | grep -c . || true)
  printf '%s\n' "$1" | while IFS= read -r f; do
    [ -z "$f" ] && continue
    shown=$((shown + 1))
    if [ "$shown" -le "$MAX_LIST" ]; then
      echo "  ✗ $f"
    elif [ "$shown" -eq $((MAX_LIST + 1)) ]; then
      echo "  … і ще $((total - MAX_LIST))"
    fi
  done
}

summary() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

fail() {
  printf '::error::%s\n' "$1"
  summary "❌ $1"
  exit 1
}

git rev-parse --verify -q "${BASE}^{commit}" >/dev/null \
  || fail "base-ref '$BASE' не знайдено (чи fetched він у CI?)"
git rev-parse --verify -q "${HEAD_REF}^{commit}" >/dev/null \
  || fail "head-ref '$HEAD_REF' не знайдено"

MERGE_BASE=$(git merge-base "$HEAD_REF" "$BASE" 2>/dev/null) \
  || fail "спільної історії між '$BASE' і '$HEAD_REF' немає — про розсинхрон судити неможливо."
BEHIND=$(git rev-list --count "$HEAD_REF..$BASE")
AHEAD=$(git rev-list --count "$BASE..$HEAD_REF")
MERGE_BASE_TS=$(git show -s --format=%ct "$MERGE_BASE")
AGE_HOURS=$(( ( $(date +%s) - MERGE_BASE_TS ) / 3600 ))

echo "base '$BASE': +${BEHIND} комітів; гілка '$HEAD_REF': +${AHEAD};"
echo "точка синхронізації $MERGE_BASE ($(git show -s --format=%cI "$MERGE_BASE")) — ${AGE_HOURS} год тому."
summary "- base \`$BASE\`: +${BEHIND}; гілка \`$HEAD_REF\`: +${AHEAD}; останній спільний коміт — ${AGE_HOURS} год тому"

# --- 1) Пробне злиття: конфлікт зараз ---------------------------------------
MT=$(mktemp)
trap 'rm -f "$MT"' EXIT

MERGE_EXIT=0
git merge-tree --write-tree --name-only "$BASE" "$HEAD_REF" >"$MT" 2>/dev/null \
  || MERGE_EXIT=$?
case "$MERGE_EXIT" in
  0) MERGE_CLEAN=true ;;
  1)
    MERGE_CLEAN=false
    # Формат виводу merge-tree: OID дерева, далі конфліктні шляхи, порожній
    # рядок, далі інформаційні повідомлення — беремо рівно середину.
    CONFLICTS=$(awk 'NR == 1 { next } /^[[:space:]]*$/ { exit } { print }' "$MT")
    ;;
  *)
    echo "::warning::git merge-tree недоступний (exit $MERGE_EXIT) — пробне злиття пропущено (потрібен git ≥ 2.38)."
    MERGE_CLEAN=true
    ;;
esac

if [ "$MERGE_CLEAN" = false ]; then
  N_CONFLICTS=$(printf '%s\n' "$CONFLICTS" | grep -c . || true)
  echo "конфліктні файли:" >&2
  list_paths "$CONFLICTS" >&2
  echo "Виправлення: git fetch origin && $MERGE_HINT, далі розв'язати конфлікт." >&2
  echo "Повний перелік: git merge-tree --write-tree --name-only $BASE $HEAD_REF." >&2
  fail "гілка розійшлася з '$BASE': злиття вже конфліктне ($N_CONFLICTS файл(ів)). GitHub покаже це пізніше; чек падає зараз."
fi

# --- 2) Вік розсинхрону -----------------------------------------------------
if [ "$BEHIND" -gt 0 ] && [ "$MAX_AGE_HOURS" -gt 0 ] && [ "$AGE_HOURS" -gt "$MAX_AGE_HOURS" ]; then
  echo "Виправлення: git fetch origin && $MERGE_HINT." >&2
  fail "гілка розійшлася з '$BASE': ${AGE_HOURS} год без синхронізації (поріг ${MAX_AGE_HOURS} год, відставання ${BEHIND} комітів). Підтягніть base у гілку, поки розсинхрон не став конфліктом."
fi

# --- 3) Спільні файли: чисте злиття, але семантика може зіткнутись -----------
OVERLAP=$(LC_ALL=C comm -12 \
  <(LC_ALL=C git diff --name-only "$MERGE_BASE" "$BASE" | LC_ALL=C sort -u) \
  <(LC_ALL=C git diff --name-only "$MERGE_BASE" "$HEAD_REF" | LC_ALL=C sort -u))
if [ -n "$OVERLAP" ]; then
  N_OVERLAP=$(printf '%s\n' "$OVERLAP" | grep -c . || true)
  echo "::warning::base і гілка правлять ті самі $N_OVERLAP файл(ів) — злиття чисте, але перевірте семантику (#1160):"
  printf '%s\n' "$OVERLAP" | while IFS= read -r f; do
    [ -n "$f" ] && echo "  ⚠ $f"
  done
  summary "⚠ спільні файли (семантичний ризик): $(printf '%s' "$OVERLAP" | tr '\n' ' ')"
else
  summary "✅ спільних файлів немає"
fi

if [ "$BEHIND" -eq 0 ]; then
  echo "OK: гілка '$HEAD_REF' стоїть на верхівці '$BASE' — розсинхрону немає, злиття чисте."
else
  echo "OK: розсинхрон ${AGE_HOURS} год / ${BEHIND} комітів — у межах порога ${MAX_AGE_HOURS} год, злиття в '$BASE' чисте."
fi
