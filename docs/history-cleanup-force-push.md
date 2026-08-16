# Force-push після перезапису історії (git filter-repo)

**Дата:** 2026-08-16
**Статус:** ✅ **виконано** — `main` переписано і запушено, тег `v1.0`
перепризначено, свіжий клон важить **36 MB** (18 MB дерево + 18 MB `.git`).
Залишилось два кроки, які залежать від людей: крок 6 (сповістити
колабораторів) і крок 7 (видалити бек-ап).

**Що сталося:** з усієї історії видалено бінарні блоби (`audit/`,
`archive/`, бінарники `docs/phone-test/`, будь-який блоб > 5 MB).
У старій історії (включно з тим, що було на GitHub) цих блобів більше
немає — ні в дереві, ні в жодному коміті.

## Ключові факти

| Що | SHA / шлях |
|---|---|
| Поточний HEAD на GitHub (після пуша) | `a9f0b6f1c1007078ff1356a3800ccacd702fc65f` |
| Нова (переписана) історія `main` | `967f4da → ddaba37 → 02b9e1c → 4fcbb06 → a9f0b6f` |
| Старий HEAD на GitHub до пуша | `40e514b09b63e9337a0bafaf17dc41a2fe486859` |
| Тег `v1.0` (перепризначено) | tag-object `415c4f99…` → коміт `06fcc50` (переписаний еквівалент старого `dd4f3bf`) |
| Резервна копія старої історії (mirror) | `/tmp/slukhayka-backup-2026-08-16.git` (272 MB) |
| Remote | `https://github.com/Samuel-Ku/slukhayka.git` |
| Свіжий клон з GitHub | 36 MB = 18 MB дерево + 18 MB `.git` (repo size на GitHub ~18 MB) |

---

## ✅ Виконано 2026-08-16 (хід виконання)

### Крок 1 — локальна історія
`git log --oneline -3` → `4fcbb06 … 02b9e1c … ddaba37`; `git status` чистий;
`.git` = 18 MB.

### Крок 2 — резервна копія
`/tmp/slukhayka-backup-2026-08-16.git` (272 MB, стара історія з усіма
блобами, `git clone --mirror` до запуску фільтра). Відновлення до пуша:
`git clone /tmp/slukhayka-backup-2026-08-16.git`.

### Крок 3 — remote + fetch
```bash
git remote add origin https://github.com/Samuel-Ku/slukhayka.git
git fetch origin    # приніс стару історію ≈250 MB (база для lease) + тег v1.0
```
`origin/main` = `40e514b…` — ніхто нічого не пушив, lease-база вірна.

### Крок 4 — dry-run, потім force-push з lease
```bash
git push --dry-run origin main          # показав non-fast-forward (очікувано)
git push --force-with-lease origin main # + 40e514b...4fcbb06 main -> main (forced update)
```
`--force-with-lease` (ніколи не plain `--force`) відмовиться пушити, якщо
remote-гілка зміниться між fetch і push.

### Крок 5 — перевірка після пуша
```bash
git ls-remote origin refs/heads/main    # = a9f0b6f…
git fetch origin
git reflog expire --expire=now --all && git gc --prune=now
du -sh .git                             # 19 MB
git clone https://github.com/Samuel-Ku/slukhayka.git /tmp/slukhayka-verify
du -sh /tmp/slukhayka-verify            # 36 MB
```

### Тег v1.0 — вирішено (важлива деталь)
Під час кроку 5 виявилось: анотований тег `v1.0` вказував на **старий**
коміт `dd4f3bf` (з нього досяжні ~249 шляхів `audit/` + `docs/phone-test/`).
`git clone` тягне теги → клон важив 290 MB навіть після перепису `main`.
Тег перепризначено на переписаний еквівалент релізу (дерево без
QA-артефактів), оригінальне повідомлення збережено:

```bash
git push origin --delete v1.0                       # - [deleted] v1.0
git tag -d v1.0                                     # локально (був 4870c3b)
git tag -a v1.0 06fcc50 -F /tmp/v10-tag-msg.txt     # повідомлення релізу дослівно
git push origin v1.0                                # * [new tag] v1.0 -> v1.0
git reflog expire --expire=now --all && git gc --prune=now
```
Після цього: `.git` = 19 MB; старий коміт `dd4f3bf` і старий tag-object
`4870c3b` зникли; клон з GitHub = 36 MB.

---

## ⏳ Залишилось (залежить від людей)

### Крок 6 — сповістити всіх, хто має клон
Force-push **осиротює** старі клони. Кожен співробітник має:

```bash
git fetch origin && git reset --hard origin/main
# або просто пере-клонувати
```

Локальні коміти на основі старої історії не є предками нової — їх
доведеться перебазувати.

### Крок 7 — коли всі пере-клонувались
```bash
rm -rf /tmp/slukhayka-backup-2026-08-16.git   # бек-ап більше не потрібен
```

---

## Чого НЕ робити

- ❌ Не пушити `--force` без lease — це знищить чужі свіжі коміти мовчки.
- ❌ Не посилатися на старі SHA (`88f5f1e`, `87711d3`, `00d6c79`,
  `40e514b`, `dd4f3bf`) — цих комітів у новій історії не існує.
- ❌ Не видаляти бек-ап, поки хоча б один співробітник не пере-клонувався.

## Чому це безпечно

- Весь перезапис зроблено локально (`git filter-repo`); до force-push
  remote-стан був недоторканий, а сам пуш закритий lease-перевіркою.
- Повний відкат: клон з mirror-бек-апу + push його на місце.
- Після перепризначення тега жоден ref (гілка чи тег) не тримає старі
  блоби — перевірено свіжим клоном: 0 шляхів `audit/`/`docs/phone-test/`
  у всій історії.
