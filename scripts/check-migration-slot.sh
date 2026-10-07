#!/usr/bin/env bash
#
# Крок 2 чек-листа docs/plans/2026-10-06-migration-renumber-rebase.md:
# слот верхньої міграційної версії БД має бути вільним. Скрипт падає, якщо:
#
#   1) однаковий номер файлу схеми існує і в base, і в гілці, але з РІЗНИМ
#      вмістом — хтось (upstream чи ви) уже зайняв цей слот іншою схемою;
#   2) гілка додає зміни схеми/версії, але її верхня версія не вища за
#      верхню в base — після ребейзу свій номер треба підняти на base+1;
#   3) у ланцюжку схем гілки дірка або `version =` у AudiobookDatabase.kt
#      не збігається з верхнім файлом схеми — наслідок невдалого
#      перепродування номера.
#
# Перевірка 1 — саме та, що ловить «upstream зайняв 51→52, поки зріз чекав»
# (#702: два різні 52.json зіткнулись лише після ребейзу).
#
# Виклик: scripts/check-migration-slot.sh <base-ref> [head-ref]
set -euo pipefail

BASE="${1:?usage: check-migration-slot.sh <base-ref> [head-ref]}"
HEAD_REF="${2:-HEAD}"
SCHEMA_DIR='app/schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase'
DB_FILE='app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDatabase.kt'
PLAN='docs/plans/2026-10-06-migration-renumber-rebase.md'

fail() {
  echo "::error::$1"
  echo "Чек-лист перепродування номерів: $PLAN" >&2
  exit 1
}

git rev-parse --verify -q "${BASE}^{commit}" >/dev/null \
  || fail "base-ref '$BASE' не знайдено (чи fetched він у CI?)"
git rev-parse --verify -q "${HEAD_REF}^{commit}" >/dev/null \
  || fail "head-ref '$HEAD_REF' не знайдено"

# Версії файлів схем у ref, числовим порядком, по одному на рядок.
schema_versions() {
  git ls-tree "$1" "$SCHEMA_DIR/" --name-only 2>/dev/null \
    | sed 's|.*/||; s|\.json$||' | sort -n
}

db_version() {
  git show "$1:$DB_FILE" 2>/dev/null \
    | sed -n 's/.*version = \([0-9][0-9]*\).*/\1/p' | head -1
}

# --- 1) Спільний номер — спільний вміст -----------------------------------
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if ! cmp -s <(git show "$BASE:$f") <(git show "$HEAD_REF:$f"); then
    n=$(basename "$f" .json)
    fail "слот міграції зайнято: $f існує в '$BASE' і в '$HEAD_REF', але з РІЗНИМ вмістом (версія $n). Хтось уже визначив, що таке версія $n, — ваш номер треба перепродати на $(db_version "$BASE" || echo "?")+1."
  fi
done < <(comm -12 \
  <(git ls-tree -r --name-only "$BASE" -- "$SCHEMA_DIR" | sort) \
  <(git ls-tree -r --name-only "$HEAD_REF" -- "$SCHEMA_DIR" | sort))

# --- 2) Гілка додає БД-зміни — її верх має бути вищим за base ---------------
M_TOP=$(schema_versions "$BASE" | tail -1)
B_TOP=$(schema_versions "$HEAD_REF" | tail -1)
DB_CHANGED=false
if [ -n "$(git diff --name-only "$BASE" "$HEAD_REF" -- "$SCHEMA_DIR" "$DB_FILE")" ] \
  || [ "$(db_version "$BASE")" != "$(db_version "$HEAD_REF")" ]; then
  DB_CHANGED=true
fi
if $DB_CHANGED; then
  if [ -z "$M_TOP" ]; then
    fail "у '$BASE' не знайдено жодного файлу схеми — перевірте ref"
  fi
  if [ -z "$B_TOP" ] || [ "$B_TOP" -le "$M_TOP" ]; then
    fail "верхня міграція гілки '$HEAD_REF' (${B_TOP:-—}) не вища за верхню в base '$BASE' ($M_TOP), хоча гілка змінює БД: слот уже зайнято — ребейз і перепродування на $((M_TOP + 1))."
  fi
fi

# --- 3) Ланцюжок гілки без дірок; version = верхній файл ---------------------
ALL=$(schema_versions "$HEAD_REF")
[ -n "$ALL" ] || fail "у '$HEAD_REF' немає жодного файлу схеми"
MIN=$(printf '%s\n' "$ALL" | head -1)
MAX=$(printf '%s\n' "$ALL" | tail -1)
ACTUAL=$(printf '%s\n' "$ALL" | paste -sd' ' -)
EXPECTED=$(seq "$MIN" "$MAX" | paste -sd' ' -)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  fail "ланцюжок схем '$HEAD_REF' має дірку: є [$ACTUAL], очікується [$EXPECTED] — наслідок невдалого перепродування номера."
fi
V=$(db_version "$HEAD_REF")
if [ "$V" != "$MAX" ]; then
  fail "у '$HEAD_REF' version = ${V:-—} у $DB_FILE, а верхній файл схеми — $MAX.json: нумерація розійшлась."
fi

echo "OK: слот вільний — base '$BASE' верх $M_TOP, гілка '$HEAD_REF' верх $MAX;"
echo "спільні версії схем ідентичні, ланцюжок без дірок, version узгоджено."
