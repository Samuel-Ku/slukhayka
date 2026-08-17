---
status: accepted
---

# UI component taxonomy: three cards, three chips, and a navigation-depth rule

Six independent UI documents of 2026-08-16 agreed the app's visual defects were
not missing design tokens — the wayfinder #23 theme already ships in
`Color.kt`/`Dimens.kt`/`Type.kt` — but that every screen reinvented its own
chips, cards and navigation containers. Two of the docs proposed two chip types
while two proposed three; three docs named the same three card shapes with
different words; and the same task (chapters) appeared once as a sheet and once
as a tab. A future reader would otherwise pick whichever doc they read first
and «fix» the screen to a different vocabulary. This ADR fixes the canonical
component system so every future screen builds against one vocabulary.

## Decision

- **Three chip types, distinguished by shape, not by label text.** A
  `NavigationChip` (filled dark surface, e.g. «ТОП 100», «Автори») *navigates*
  to another screen. A `FilterChip` (outlined default, filled accent/subtle when
  selected, e.g. «Фентезі», «Завантажені») *filters* the current list and has a
  selected state. A `MetadataChip` (smaller, muted, no button affordance, e.g.
  «65 розділів», source badge «4read») is *non-actionable* metadata. The
  design-guide's two-type split is rejected because metadata chips must never
  read as tappable filters.
- **Three canonical card types, one per context.** `HeroCard` — the single
  resume card on «Слухати», the only card allowed `surfaceElevated` + shadow and
  an accent CTA. `CompactBookCard` — the horizontal-shelf card (cover + title +
  author, no default play triangle); the docs' `PosterCard`, `Collection card`
  and `Compact book card` are aliases. `BookRow` — the flat vertical list row
  (64 dp cover, thin progress hairline, divider not border); the docs' `ListRow`
  and `Book row` are aliases. No new card variant may be introduced without
  collapsing one of these three.
- **Navigation depth follows the task, not the screen.** *Push* (full screen)
  for navigating to a new persistent content surface — book details, cycle
  page, full catalog, full chapter list, «Керувати завантаженнями». *Sheet*
  (bottom sheet) for transient selection or quick navigation — timer, speed,
  quick chapters, add-audio source picker. *Dialog* (or destructive sheet) for
  irreversible confirmation — delete-all-downloads, clear cache. One task, one
  container: the chapters list therefore lives in *one* visual model whether it
  is reached from the player sheet or the book-page tab.
- **«Розділ» is the canonical UI label for Chapter.** The domain term stays
  `Chapter` in the glossary and in code identifiers; only the rendered label is
  unified, replacing the stray «Глава» occurrences.

## Rejected alternatives (considered during the synthesis)

- **Two chip types (`NavChip`/`FilterChip`), per the design-guide** — rejected:
  leaves the metadata chip without a home, so genre tags and source badges get
  styled as tappable filters (the very defect the docs flagged on «Огляд»).
- **Cutting «Слухати» to 3–5 sections** — rejected: contradicts ADR-0015, which
  keeps eight user-reorderable blocks; the observed defect was duplicate/equal
  cards, not the count.
- **Adopting the docs' palette/type/spacing tables** — rejected: they are stale
  screenshot approximations; the shipped theme tokens are the source of truth.

## Consequences

- New screens name their components against this vocabulary; a component that
  does not fit one of the three card/chip types is a signal the taxonomy is
  wrong, not that a fourth type is needed.
- The chapters sheet-vs-tab inconsistency resolves to one `BookRow` model.
- This feeds spec-27 (#184): the P0 fixes apply the taxonomy so the first
  shippable delta is a consistent screen, not a further document.
