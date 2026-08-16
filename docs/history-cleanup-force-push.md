# Force-push після перезапису історії (git filter-repo)

**Дата:** 2026-08-16 · **Що сталося:** з усієї історії видалено бінарні
блоби (`audit/`, `archive/`, бінарники `docs/phone-test/`, будь-який блоб
> 5 MB). Локальна гілка `main` переписана — у старій історії (включно з
тим, що вже на GitHub) цих блобів більше немає.

**Ключові факти:**

| Що | SHA / шлях |
|---|---|
| Новий локальний HEAD (після перезапису) | `02b9e1c43c2b705c93fcb71cd1a658341dd8ceca` |
| Старий HEAD на GitHub (`origin/main` зараз) | `40e514b09b63e9337a0bafaf17dc41a2fe486859` |
| Резервна копія старої історії (mirror) | `/tmp/slukhayka-backup-2026-08-16.git` (272 MB) |
| Remote | `https://github.com/Samuel-Ku/slukhayka.git` |

`git filter-repo` сам видалив remote `origin` — перед пушем його треба
додати заново.

---

## Крок 1 — переконатися, що локальна історія правильна

```bash
git log --oneline -3
# очікуємо:
#   02b9e1c docs: regenerate codemaps for the current 8-module structure
#   ddaba37 docs: rewrite AGENTS.md as contributor-facing guide and record ADR-0016
#   967f4da chore: strip internal QA artifacts and dead code from the tree

git status        # робоче дерево чисте
du -sh .git       # очікуємо ~18-20 MB, не ~250 MB
```

## Крок 2 — переконатися, що резервна копія на місці

```bash
git -C /tmp/slukhayka-backup-2026-08-16.git log --oneline -3
# стара історія: 88f5f1e … 87711d3 … 00d6c79 … (з усіма блобами)
```

Якщо щось піде не так — **до пуша** відновлення просте: клон з бек-апу
(`git clone /tmp/slukhayka-backup-2026-08-16.git`). Після успішного пуша
стара історія існує тільки тут.

## Крок 3 — додати remote і підтягнути його стан

```bash
git remote add origin https://github.com/Samuel-Ku/slukhayka.git
git fetch origin
# ОЧІКУВАНО: fetch принесе стару історію (≈250 MB) в refs/remotes/origin/main —
# це тимчасово роздує локальний .git. Це нормально: це база для --force-with-lease.
# Після пуша + повторного fetch старе стає недосяжним і gc його прибере.
```

## Крок 4 — dry-run, потім force-push з lease

```bash
git push --dry-run origin main          # покаже, що буде запушено (без змін)

# ТІЛЬКИ force-with-lease, ніколи не plain --force:
git push --force-with-lease origin main
```

`--force-with-lease` порівнює remote-гілку зі щойно зтягнутим станом
(`40e514b…`) і **відмовиться** пушити, якщо хтось встиг запхати новий
коміт — тоді зупинись і переглянь, що сталося.

## Крок 5 — перевірка після пуша

```bash
git ls-remote origin refs/heads/main        # має дорівнювати 02b9e1c…
git fetch origin                            # оновлює origin/main до нової історії
git reflog expire --expire=now --all && git gc --prune=now   # прибрати старі об'єкти локально
du -sh .git                                 # знову ~18-20 MB

# Свіжий клон з GitHub — фінальна перевірка:
git clone https://github.com/Samuel-Ku/slukhayka.git /tmp/slukhayka-verify
du -sh /tmp/slukhayka-verify                # ≈ 36 MB на диску = 18 MB дерево + 18 MB .git
```

На сторінці GitHub розмір репозиторію (repo size) впаде з ~300 MB до ~18 MB.

## Крок 6 — сповістити всіх, хто має клон

Force-push **осиротює** старі клони. Кожен співробітник має:

```bash
git fetch origin && git reset --hard origin/main
# або просто пере-клонувати
```

Будь-які локальні коміти на основі старої історії доведеться перебазувати
(вони тепер не є предками нової історії).

## Крок 7 — коли все підтверджено

```bash
rm -rf /tmp/slukhayka-backup-2026-08-16.git   # бек-ап більше не потрібен
```

---

## Чого НЕ робити

- ❌ Не пушити `--force` без lease — це знищить чужі свіжі коміти мовчки.
- ❌ Не пушити до того, як зроблено крок 2 (перевірка бек-апу).
- ❌ Не оновлювати жодних посилань на старі SHA (`88f5f1e`, `87711d3`,
  `00d6c79`, `40e514b`) — цих комітів у новій історії не існує.
- ❌ Не видаляти бек-ап, поки хоча б один співробітник не пере-клонувався.

## Чому все це безпечно

- Весь перезапис зроблено локально (`git filter-repo`), GitHub не чіпався.
- До кроку 4 remote стан — недоторканий `40e514b…`; пуш — єдиний момент
  впливу на GitHub, і він закритий lease-перевіркою.
- Повний відкат: клон з mirror-бек-апу + push його на місце.
