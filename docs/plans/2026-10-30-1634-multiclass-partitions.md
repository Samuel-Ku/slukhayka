# План: #1634 — партиціонер бачить усі тест-класи файлу, не лише file stem

Знахідка огляду PR #1632: `scripts/test_partitions.py` будував FQCN як
`package + path.stem`, тобто рівно один тест-клас на файл. Якщо у `*Test.kt`
оголошено другий клас (або ім'я класу не збігається з ім'ям файлу), він не
потрапляв у жодну партицію — а CI запускає саме цей список. Наслідок:
`CanonicalStateFacadesTest.kt` із `CanonicalStateFacadesGuardTest` і
`CanonicalStateFacadesBehaviorTest` **не запускався ніде**, хоча виглядав
покритим; у PR #1632 так само мовчки не біг `LibraryTabsFitEnTest`, поки не
винесли в окремий файл.

## Рішення

1. **`discover()` повертає ВСІ top-level `*Test`-класи файлу.** Регекс по
   початку рядка (top-level, не вкладені) + фільтр «ім'я закінчується на
   `Test`» — хелпери/фейки/абстрактні бази не стають тест-таргетами.
   Stem-клас лишається **лише fallback'ом** для файлу, що не оголошує жодного
   `*Test`-класу (сьогодні таких немає) — фантомних FQCN не додаємо.
2. **`select --changed-file <*Test.kt>`** повертає всі класи цього файлу (раніше
   шукав лише stem → `unknown changed test` → fullSuite).
3. **`validate`** без змін: унікальність FQCN (тепер ловить і два однакові
   класи в різних файлах) і «рівно одна партиція».
4. **Тести** `scripts/tests/test_test_partitions.py`:
   - файл із двома класами → `list` містить обидва;
   - `select --changed-file` по цьому файлу обирає обидва (не fullSuite);
   - оголошене ім'я перемагає stem (без фантомного класу).

## Межі

- змінюється лише `scripts/test_partitions.py` і його unit-тести; формат
  виводу/партиції/маркери — ті самі;
- CI-фільтри отримують +1 реальний клас (`CanonicalStateFacades*`) і -1
  фантомний stem — фактичний набір тестів розширюється свідомо;
- `verify_partition_coverage.py` не зачеплений (читає Kover, не список).

## Перевірка

- `python3 -m unittest scripts.tests.test_test_partitions` — 17/17.
- `python3 scripts/test_partitions.py validate` — OK; `list` містить обидва
  `CanonicalStateFacades*` класи; `select` по їхньому файлу повертає обидва.
- CI `Script unit tests` + повний набір (зміна в `scripts/` -> fullSuite) —
  гейт; мутація: повернути `package + path.stem` → нові тести червоніють.
