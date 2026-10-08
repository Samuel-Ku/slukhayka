#!/usr/bin/env bash
#
# Кроки 2, 3 і 6 чек-листа docs/plans/2026-10-06-migration-renumber-rebase.md.
# Скрипт падає, якщо:
#
#   1) однаковий номер файлу схеми існує і в base, і в гілці, але з РІЗНИМ
#      вмістом — хтось (upstream чи ви) уже зайняв цей слот іншою схемою;
#   2) гілка додає зміни схеми/версії, але її верхня версія не вища за
#      верхню в base — після ребейзу свій номер треба підняти на base+1;
#   3) у ланцюжку схем гілки дірка або `version =` у AudiobookDatabase.kt
#      не збігається з верхнім файлом схеми — наслідок невдалого
#      перепродування номера;
#   4) (крок 3) об'єкт MIGRATION_(N-1)_N відсутній або не останній у сирому
#      списку .addMigrations(...) AudiobookDatabase.kt;
#   5) (крок 6) якщо міграція data-only: вміст N.json гілки (без полів
#      version/identityHash — вони змінюються легально) не тотожній верхньому
#      M.json base, або identityHash розійшовся.
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

# --- 4) (крок 3) MIGRATION_(N-1)_N присутній і останній у сирому списку -------
if $DB_CHANGED && [ -n "$B_TOP" ] && [ "$B_TOP" -gt "$M_TOP" ]; then
  OBJ="MIGRATION_$((B_TOP - 1))_$B_TOP"
  RAW_ARGS=$(git show "$HEAD_REF:$DB_FILE" | tr '\n' ' ' \
    | grep -Eo 'addMigrations\([^)]*' | head -1)
  [ -n "$RAW_ARGS" ] \
    || fail "у $DB_FILE гілки '$HEAD_REF' не знайдено сирого списку .addMigrations(...) — перевірте структуру."
  ARGS=$(printf '%s' "$RAW_ARGS" | sed 's|//.*||g')
  printf '%s' "$ARGS" | grep -q "$OBJ" \
    || fail "об'єкт $OBJ не знайдено в списку .addMigrations(...) у $DB_FILE: після перепродування він мусить вести від $((B_TOP - 1)) до $B_TOP (крок 3 і 5 чек-листа)."
  LAST=$(printf '%s' "$ARGS" | grep -Eo 'MIGRATION_[0-9]+_[0-9]+' | tail -1)
  [ "$LAST" = "$OBJ" ] \
    || fail "останній у сирому списку .addMigrations(...) — $LAST, а очікується $OBJ: ваша міграція не остання у списку (крок 3: «останнім у сирому списку»)."
fi

# --- 5) (крок 6) data-only: тіло N.json = тіло верхнього M.json base ----------
# version/identityHash усередині JSON змінюються легально (крок 6 плану), тому
# порівнюємо тіло без них, а identityHash — окремо: тотожні схеми мають той
# самий хеш, ручна правка хеша при однаковому тілі — теж помилка.
#
# Перевірка стосується ЛИШЕ data-only міграцій — так написано і в шапці, і в
# назві кроку 6. Міграція, що ЗМІНЮЄ схему (нові таблиці/колонки/індекси),
# законно має верхній файл, відмінний від base, тож вимагати тотожність
# означало б заборонити будь-яку зміну схеми взагалі (саме так падала вже
# злита 50→51). Тому питаємо саме міграцію: чи її тіло оголошує DDL. Оголошує
# — схема змінилася свідомо, перевірка не застосовується. Не оголошує — це
# data-only, і тоді верхній файл мусить бути копією верхнього в base: інакше
# Room бачить схему, яку ніхто не мігрував (та сама пастка, що й доти).
migration_declares_schema_change() {  # $1 = ref, $2 = верхня версія схеми
  local prev=$(( $2 - 1 ))
  git show "$1:$DB_FILE" \
    | sed -n "/val MIGRATION_${prev}_$2 =/,/val MIGRATION_[0-9][0-9]*_[0-9][0-9]* =/p" \
    | grep -Eq 'CREATE TABLE|ALTER TABLE|DROP TABLE|CREATE (UNIQUE )?INDEX|DROP INDEX'
}

schema_verdict() {  # $1, $2 — "ref:path" двох файлів схем
  python3 - <(git show "$1") <(git show "$2") <<'PY'
import json, sys

def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)

head, base = load(sys.argv[1]), load(sys.argv[2])
hd, bd = head["database"], base["database"]
hh, bh = hd.get("identityHash"), bd.get("identityHash")
for d in (hd, bd):
    d.pop("version", None)
    d.pop("identityHash", None)
if json.dumps(head, sort_keys=True) != json.dumps(base, sort_keys=True):
    print("BODY")
elif hh and bh and hh != bh:
    print("HASH")
else:
    print("OK")
PY
}

DATA_ONLY=false
if $DB_CHANGED && [ -n "$B_TOP" ] && [ "$B_TOP" -gt "$M_TOP" ]; then
  N_FILE="$SCHEMA_DIR/$B_TOP.json"
  M_FILE="$SCHEMA_DIR/$M_TOP.json"
  if ! migration_declares_schema_change "$HEAD_REF" "$B_TOP"; then
    DATA_ONLY=true
  fi
fi

if $DATA_ONLY; then
  VERDICT=$(schema_verdict "$HEAD_REF:$N_FILE" "$BASE:$M_FILE")
  case "$VERDICT" in
    BODY)
      fail "застаріла копія: $B_TOP.json гілки '$HEAD_REF' має ІНШИЙ вміст (без полів version/identityHash), ніж верхній $M_TOP.json у '$BASE'. Data-only міграція мусить бути копією нового верхнього файлу схем base — інакше Room бачить схему, яку ніхто не мігрував (крок 6 чек-листа)."
      ;;
    HASH)
      fail "identityHash розійшовся: $B_TOP.json гілки '$HEAD_REF' проти $M_TOP.json у '$BASE' — вміст тотожний, але хеші різні; схему правили руками після копіювання (крок 6 чек-листа)."
      ;;
  esac
fi

echo "OK: слот вільний — base '$BASE' верх $M_TOP, гілка '$HEAD_REF' верх $MAX;"
echo "спільні версії схем ідентичні, ланцюжок без дірок, version узгоджено;"
echo "MIGRATION_$((MAX - 1))_$MAX останній у addMigrations;"
if $DATA_ONLY; then
  echo "$MAX.json = копія $M_TOP.json base (міграція data-only)."
else
  echo "$MAX.json змінює схему проти $((MAX - 1)).json — перевірка копії не застосовується."
fi
