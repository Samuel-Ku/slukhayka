---
status: accepted
---

# v1.4 consistency baseline: the written component vocabulary ships in code

ADR-0018 fixed the canonical component system on paper (three cards,
three chips, sheet/push/dialog depth); eighteen screen-shipping
iterations later (spec-28…45, audits #545–555) the code still speaks
five header styles, four poster-card twins, six list-row styles and
four metadata-chip implementations — while the canonical
`AppSectionHeader` is referenced only by tests. The v1.4 goal is visual
polish and consistency; polishing twenty-five surfaces screen-by-screen
without first collapsing the vocabulary would reproduce the defect at a
new layer (the same diagnosis ADR-0014 recorded in 2026-08: «every
screen reinvented its own chips, cards and containers»). A grilling
session on 2026-09-06 walked every screen against the written rules and
produced the decisions below (evidence: `docs/audits/2026-09-06-v14-ui-consistency-grill.md`).

## Decision

- **Canonical components become real, screens migrate.** One
  `PosterCard` (120×168 portrait, slots for author/duration/progress/
  caption/dismiss/download) replaces `CompactBookCard`, `CatalogBookCard`,
  `UnifiedCatalogCard`, `CollectionBookCard`; one `CycleCard` (landscape)
  replaces the three series-card twins; `RecommendedBookCard` rejoins the
  rhythm (no more 200 dp outlier). One `BookRow` (64 dp cover, hairline
  progress, divider not border) replaces every vertical row style. One
  `MetadataChip` (non-interactive) replaces `LanguageBadge`,
  `SourceBadgePill`, `TagPill` and the recommendation reason surfaces.
  Two-level section headers (group = headlineSmall; section = uppercase
  titleSmall with an optional action slot) replace the five ad-hoc
  header styles. Two canonical empty/loading states absorb the six
  variants. Counters live in section headers, not free-standing rows.
- **ADR-0015 stands; its chrome shrinks.** The eight user-reorderable
  Listen shelves stay the product. The eight identical per-block ⋮ menus
  collapse into one «Керувати полицями» sheet (reorder, hide, restore);
  block headers render through the canonical header's action slot.
- **One feedback door on the book page.** «Відгуки» is the single entry
  (stars + text + narration tag); the post-completion prompt (ADR-0032)
  opens the same form automatically. The «Ваші враження» button after the
  chapter list is removed — «one tool, one place» (ADR-0014).
- **One search pattern.** Медіатека adopts Огляд's collapsible search
  (🔍 in the tab header, ✕/Back clears); the always-visible field goes.
  *(Superseded 2026-09-18: the single pattern is now the always-visible field
  — see «Зміна рішення 2026-09-18» below. The decision text above is kept as
  it was written.)*
- **One tab-header model.** Every bottom tab renders title (+ optional
  action) through one composable; the brand lockup, the
  title+subtitle+CTA trio and the bare headline variants collapse.
- **EN strings complete in v1.4.** Hardcoded Ukrainian chrome
  (Медіатека header/filters, book-page blocks, player labels, timer
  options, Огляд rails) moves to resources; a mixed-language UI is a
  consistency defect, not a deferred i18n story (audits #549/#550).
- **Android only.** The Web Client inherits the same contracts in a
  later spec; v1.4 does not touch `web/`.
- **Chrome-spacing token.** `AppDimens.SpaceAboveMiniPlayer` replaces
  the twelve hardcoded `bottom = 120.dp` paddings.

## Rejected alternatives (considered during the grilling)

- **Two feedback doors with clearer labels** — rejected: two entries to
  one system is exactly the competing-affordance defect «one tool, one
  place» kills.
- **Keep per-block Listen menus, restyled** — rejected: eight identical
  ⋮ icons are chrome per ADR-0014's control-to-content rule; the manage
  sheet keeps every capability.
- **Keep two search patterns** — rejected: same gesture (find a book),
  two behaviors, two screens apart.
- **Bordered rows instead of flat BookRows** — rejected: ADR-0018
  already wrote «divider not border»; the audit shows the rule was never
  applied, not that it was wrong.
- **Reopening ADR-0015 (merge shelves) or ADR-0018 (fourth card)** —
  rejected: the defects are duplicate implementations, not the written
  vocabulary.

## Consequences

- Spec-27's P0/P1 language gets its missing layer: the component base
  finally matches the ADR, and future screens inherit consistency by
  default instead of by discipline.
- Snapshot pins (Roborazzi) update with each component migration; the
  a11y contracts (panes, headings, 48 dp, focus-restore) move INTO the
  canonical components as mandatory behavior.
- The delivery order and per-screen deltas live in
  `docs/specs/2026-09-06-ui-consistency-v14.md`; each closes only with a
  phone-verified visible delta (ADR-0017).
- `AuthorDiscoveryScaffold` dies; `IndexScreenScaffold` is the one
  pushed-screen chrome.

## Зміна рішення 2026-09-18 — постійно видиме поле стає єдиним патерном

**Що змінилося.** Правило більше не звучить як «Медіатека переймає
згортуваний пошук Огляду, постійно видиме поле прибираємо». Тепер навпаки:
**поле пошуку постійно видиме на кожному кореневому екрані, і жодна дія 🔍
його не ховає.** Огляд отримав це першим (#910, 2026-09-18); Бібліотека
повторює той самий патерн у цій зміні. Обидва корені рендерять однакове
поле над своїм вмістом.

**Чому.** Грилінг 2026-09-06 (доказова база цього ADR) відкинув «два
патерни пошуку» саме тому, що один жест — знайти книжку — мав дві різні
поведінки на двох екранах. Аргумент про єдиність лишався правильним;
хибним був вибір, *який саме* патерн робити єдиним. Цей вибір зроблено до
дизайн-напрямку 2026-09-16: основні екрани визначають «Постійно видимий
пошук… Знайти й відкрити книгу», а IA §4 додає «Над ними — пошук і
компактний вхід до каталогу». Постійне поле — це основна дія кореня;
ховати його за магніфіром означає брати зайвий тап за те, заради чого
екран існує. Тож змінено вибір патерна, а не вимогу єдиного патерна:
патерн і далі один — тепер постійно видимий.

**Історію збережено.** Текст v1.4 вище не переписано: він фіксує, що
вирішили 2026-09-06 і чому. Відкинутий варіант «лишити два патерни пошуку»
лишається відкинутим. Свідомо не змінено: новий контент шукає «Огляд», а
локальний пошук Бібліотеки й далі шукає лише власні книжки слухача (IA §4).

**Поведінка, обовʼязкова для кожного кореня з пошуком:**

- поле видиме без жодної дії; перемикача 🔍 не існує;
- ✕ лише очищає запит — поле не зникає з екрана;
- системний Back спершу очищає введений запит; на порожньому полі Back не
  перехоплюється й веде далі за навігаційним контрактом;
- автофокуса немає: відкриття кореня не піднімає клавіатуру.

Лишається Android-only: Web Client успадкує контракт пізніше (ADR-0034).
