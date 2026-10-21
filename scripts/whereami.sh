#!/usr/bin/env bash
#
# «На якій гілці я стою і чим вона відрізняється від main» — однією командою.
#
# Навіщо. Сесія чотири рази запускала `scripts/merge-pr.sh` з кореневої копії,
# де теки `scripts/` немає взагалі — і отримувала «No such file or directory»
# замість відповіді «ти не в тому дереві». Двічі з цього народилось хибне
# «файлу не існує» про файл, який у `main` є.
#
# Що друкує: гілку, ahead/behind `origin/main`, і різницю набору файлів
# (лише-в-main / лише-тут), плюс кількість незакомічених змін.
#
# Коди виходу:
#   0 — робоче дерево збігається з `origin/main` за набором файлів
#   1 — НЕ збігається (ти не на main)
#   2 — посилання `origin/main` недоступне (не вдалось прочитати)
#
# `--quiet` — один рядок вердикту.

set -uo pipefail

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "whereami: не всередині робочого дерева git" >&2
    exit 2
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
BASE="origin/main"

if ! git rev-parse --verify --quiet "$BASE" >/dev/null 2>&1; then
    echo "whereami: посилання '$BASE' недоступне — зроби git fetch" >&2
    exit 2
fi

if [ "$QUIET" = "1" ]; then
    if [ "$BRANCH" = "main" ] && [ "$(git rev-list --count "$BASE"..HEAD 2>/dev/null || echo 0)" = "0" ]; then
        echo "на main"
        exit 0
    fi
    echo "НЕ на main (гілка: $BRANCH)"
    exit 1
fi

COUNTS="$(git rev-list --left-right --count "$BASE...HEAD" 2>/dev/null || echo '? ?')"
BEHIND="$(echo "$COUNTS" | awk '{print $1}')"
AHEAD="$(echo "$COUNTS" | awk '{print $2}')"

echo "гілка:        $BRANCH"
echo "origin/main:  позаду $BEHIND, попереду $AHEAD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/whereami.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
git ls-tree -r --name-only "$BASE" 2>/dev/null | sort > "$TMP/main.txt"
git ls-tree -r --name-only HEAD 2>/dev/null | sort > "$TMP/here.txt"

ONLY_MAIN="$(comm -23 "$TMP/main.txt" "$TMP/here.txt" | wc -l | tr -d ' ')"
ONLY_HERE="$(comm -13 "$TMP/main.txt" "$TMP/here.txt" | wc -l | tr -d ' ')"

echo "файлів лише в main: $ONLY_MAIN"
echo "файлів лише тут:    $ONLY_HERE"

DIRTY="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
echo "незакомічених змін: $DIRTY"

if [ "$ONLY_MAIN" = "0" ] && [ "$ONLY_HERE" = "0" ]; then
    exit 0
fi
exit 1
