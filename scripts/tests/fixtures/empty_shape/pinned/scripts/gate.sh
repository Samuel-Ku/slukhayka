#!/usr/bin/env bash
# Мініятюрний гейт із тією самою формою рішення, що merge-pr.sh:478 (#1254).
#
# Гілку операції він читає з `<маркер>/head-name`, як справжній git. Зупинений
# `git am` цього файлу НЕ лишає, і тоді гілка — порожній рядок; ребаз від
# від'єднаного HEAD пише туди `detached HEAD`. Обидві форми означають одне:
# гілку не названо, і гейт мусить відмовити.
set -u

marker="$1"
branch="$(cat "$marker/head-name" 2>/dev/null || true)"

case "$branch" in
  "" | "detached HEAD")
    printf 'FAIL  гілка: не названо\n' >&2
    exit 1
    ;;
  release)
    printf 'OK    своя гілка\n'
    exit 0
    ;;
  *)
    printf 'WARN  чужа гілка\n' >&2
    exit 0
    ;;
esac
