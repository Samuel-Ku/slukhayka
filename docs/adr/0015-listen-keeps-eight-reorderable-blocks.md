---
status: accepted
---

# «Слухати» keeps eight user-reorderable blocks (spec 2026-08-07 said five)

The 2026-08-07 listen-first spec promised exactly five blocks; the shipped
screen has eight (HERO, ALMOST_DONE, RETURN, NEXT_IN_SERIES, TRAVEL, SHORT,
FAVORITE_AUTHORS, RECENTLY_ADDED), each with a reason line, and the user can
reorder and hide them (order persisted in ListenPrefs). Three of the four UX
reviews proposed cutting back to 4–5 sections or merging rows; a future reader
comparing the spec to the code would otherwise «fix» the screen by deleting a
deliberate feature. This ADR records that the eight blocks are the product,
not drift.

## Decision

- **Blocks stay eight.** The block system is the feature: user reorder, hide,
  and per-block reason lines answer different questions («майже дочитали»,
  «поверніться», «далі в серії») and merging them would destroy the reorder
  mechanics.
- **No duplicates on screen.** The hero book is excluded from the other
  blocks of the same screen (the «no duplicates» rule already declared for
  the catalog) — this was the real defect the reviews observed, not the block
  count.
- **Visual hierarchy instead of fewer blocks.** The hero is the single accent
  block; the other rows render muted per the design tokens, so the landing
  answers «що слухати зараз» first without removing any block.
- The «Щось коротке» block (from the personalized-listen prototype, not the
  spec) stays — documented in the product-surfaces spec.
