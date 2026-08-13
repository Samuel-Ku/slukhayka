# Rule-based personalized Listen — wayfinder prototype (#62)

Status: prototype for «Rule-based personalized Listen screen» (#62), wayfinder
map «Smart Library, Sync & Listening Intelligence» (#45). Grounded in the
inherited hero/«нещодавно слухали» rows, #57 series membership, #59 offline
lifecycle, #60 derived listening states, #58 grouped search, and the map's
constraint: **no opaque recommendation model** — every row must explain
itself, be reorderable, hideable, and offline-safe.

## 1. The block set — eligibility, priority, reason

The Listen screen is a vertically ordered list of **blocks**; each block is a
pure function `(repository flows) → block?` (null = not eligible) plus a
one-line **reason** shown on the block header. Order is the priority below;
a block that isn't eligible simply doesn't render.

| # | Block | Eligibility (derived from) | Reason line example |
|---|---|---|---|
| 1 | **Продовжити** (hero) | most recent non-completed progress | — |
| 2 | **Майже дочитали** | progress ≥ 80 %, not completed | «До кінця 20 хв» |
| 3 | **Поверніться** | progress exists, not completed, `lastListenedAt` older than 14 days | «Ви слухали 2 тижні тому» |
| 4 | **Далі по серії** | #57 membership, next not-completed member (#60 Q4) | «Наступний том: …» |
| 5 | **Готово до поїздки** | offline-ready books (#59 lifecycle); plus the recently-listened-not-offline hint (#59 Q9) | «Завантажено · працює офлайн» |
| 6 | **Щось коротке** | `totalDuration` ≤ 3 h | «~2 год прослуховування» |
| 7 | **Улюблені автори / диктори** | `isFavorite` books grouped by author/narrator, most-listened first | «Ви часто слухаєте цього автора» |
| 8 | **Нещодавно додані** | `createdAt` recency | «Додано цього тижня» |
| 9 | **Нове з джерел** | per-source feeds (existing) | — |

Every reason is derived text from local data — the block header answers
«чому це тут?» without an explanation screen. Nothing is fabricated: a
cold-start library renders only block 9 (feeds) plus the two first-run CTAs
(«Знайти книгу» / «Імпортувати з пристрою»), and blocks appear as their
eligibility becomes true.

## 2. Controls — reorder, hide, not-interested (all reversible)

- **Reorder**: per-block drag handle; the order is persisted locally. The
  priority above is the default; a user's order wins.
- **Hide a block**: per-block menu «Сховати цей блок» → persisted, restorable
  from settings («Повернути приховані блоки»). Hidden blocks stay computed
  but unrendered (re-showing is instant, no state loss).
- **Not interested (per card)**: «Не цікаво» on a card adds its Work to a
  local `dismissed_works` list; the card is filtered from every block. Fully
  reversible from settings; **local-only by design** — a preference, not an
  identity correction (never synced via the #56 corrections store, which is
  for identity facts, not taste).
- All three controls are themselves derived-state consumers — they mutate a
  small local prefs table, and the composer re-runs. Prior art: the existing
  three-level deletion confirmations and the sort/filter chips in the library.

## 3. The rule engine — transparent by construction

A pure **`ListenComposer`** takes the repository flows (progress, library,
series, offline readiness, feeds, prefs) and returns the ordered block list
with eligibility + reasons. Rules are a small **declarative data list**
(block id, predicate, priority, reason template), not an opaque model — each
rule is a unit-testable pure function, and the whole composer is
snapshot-tested like `LibraryModel.filterAndSort` / `mergeGlobalSearchResults`.

## 4. Cold start & empty states

- No data at all → first-run screen (the two CTAs) + block 9.
- Data present, all blocks hidden → a single «налаштувати блоки» row
  (restore-all), never a dead screen.
- Offline → blocks 1–8 work from Room (no network anywhere in the composer);
  block 9 collapses to the #58 «офлайн — повторіть пізніше» state.
- New feeds failing → block 9 shows its retry row, others unaffected.

## 5. Out of scope (of this prototype)

- Any learned/opaque ranking, demographics, cross-device taste sync
  (dismissed works stay local), editorial «за настроєм» collections
  (a later content slice), and anything network-dependent in the composer.
