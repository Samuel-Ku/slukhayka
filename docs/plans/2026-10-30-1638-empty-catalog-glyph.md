# План: #1638 — останній icon-заглушка «Огляду» + спільний guard

Після #1624 (і F17/F33 раніше) на «Огляд» лишився один порожній стан з
іконкою-заглушкою: `EmptyCatalogState` (перший прогін «Огляду», коли каталог
порожній) малював `icon = PaperInkIcons.Book`. Спека §4 напряму 2026:
порожній стан — Literata 20 + Meta, **без іконки-заглушки**.

## Рішення

1. **`EmptyCatalogState` без `icon`** — канонічний iconless `EmptyState`
   (`DesignSystem.kt` уже підтримує `icon: ImageVector? = null` і має сітку
   `empty_state_icon`). Тексти/кнопки/теги стану не змінюються.
2. **`PaperInkIcons.FilterOff` лишається.** Після #1624 його ніде не вжито,
   але `docs/audits/assets/2026-10-28-device/icon-map.tsv` називає його
   канонічним гліфом, і `PaperInkIconsTest` вимагає присутності всіх
   канонічних імен. Видалення зламало б контракт «наступний PR заміни не
   вигадує гліф» і перезаписало б галерею заради нічого; натомість мертвий
   гліф у родині — свідомо прийнятна ціна (зафіксовано тут).
3. **Guard «жодного icon у EmptyState» стає СПІЛЬНИМ** — `EmptyStateGlyphGuard`:
   - витягує аргументи call site з балансом дужок (замість 400-символьного
     вікна з #1624), тож довгі `actions`-лямбди й вкладені виклики не
     зсувають вікно;
   - читає ПЕРШИЙ аргумент: `icon = …` (іменований) або аргумент без `=` у
     першій позиції (позиційний icon) — icon; `iconContent =` не рахується
     (це живий індикатор завантаження, а не заглушка);
   - застосовується до `HomeFeedContent.kt` (3 call sites) і `HomeScreen.kt`
     (1), із self-test на named/positional/`iconContent`/вкладені коми.
4. **Еталон `catalog_empty_state.png` перезаписується через CI
   `record-snapshots`** (локальний рендер не byte-identical); інші еталони не
   змінюються, бо `FilterOff` лишився в галереї.

## Перевірка

- `HomeFeedEmptyStateGlyphTest`: render `EmptyCatalogState` без
  `empty_state_icon`; guard self-test; guard по обох файлах; feed-стани
  (рендер) як у #1624.
- `PaperInkIconsTest` — зелений (канонічні імена цілі).
- Мутація: повернути `icon = PaperInkIcons.Book` в `EmptyCatalogState` →
  render-тест і guard падають.
