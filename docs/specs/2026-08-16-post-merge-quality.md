# [Spec-24] Пост-merge якість: SEO-заголовки, тривалість у каталозі, % прослуханого, плеєр в один екран

> **Status:** Draft — synthesized from the 2026-08-16 grilling; decisions locked with the user.
> **Tracker:** filed as issue (labels `spec-24`, `ready-for-agent`).

## Problem Statement

After the current version was rebuilt from the merged tree (upstream Works/Editions refactor + ported local features) and reinstalled on the phone, several quality gaps surfaced:

1. Some sources put SEO suffixes in book titles («аудіокнига слухати онлайн», «слухати онлайн»…) — they render everywhere: catalog cards, book page, player.
2. The catalog (Огляд) gives no at-a-glance book duration. To learn whether a book is a short novella or a 16-hour novel the user must open the book page.
3. The «ПРОДОВЖИТИ СЛУХАТИ» hero card shows 0 % progress for anyone early in a long chapter — it divides the in-chapter position by the book total.
4. The full player scrolls — the content does not fit one screen.
5. The player rendered the fabricated «4read Voice Narrator» placeholder instead of the real narrator for seeded catalog books (already partially addressed; kept here as a locked decision).

## Solution

1. **Title claims are normalized at the write path** (the one metadata-assertions seam, ADR-0004): a curated list of Ukrainian SEO phrases is stripped from the END of any claimed title. A one-time, idempotent SQL pass at app start scrubs the titles already stored (audiobooks + works). New imports and catalog hydration are clean by construction.
2. **Catalog cards show the full book duration** under the author (endless feed rows) and under the title (cover cards) in `Ч:ММ:СС`, only when the duration is known (> 0) — honest-data doctrine, never «0:00».
3. **The hero card uses the cumulative book position** (chapters before the current one + in-chapter offset) for percent and remaining time — the same `effectiveChapterDurations` rule the library cards already use.
4. **The player fits one screen**: the scroll is removed and the cover becomes flexible (shrinks when vertical space is tight), so every control is always visible.
5. **The player hides fabricated narrators**: the «Читає …» line renders only for real names (display-side scrub, mirrors the book page).

## User Stories

1. As a listener, I want book titles without marketing suffixes, so that the catalog reads as a library, not an SEO feed.
2. As a listener, I want the same clean titles after an update, so that books I imported earlier do not keep their suffixes forever.
3. As a listener browsing Огляд, I want to see each book's duration on its card, so that I can tell a novella from a long novel without opening the page.
4. As a listener, I want the duration only when it is really known, so that no card ever shows a fabricated «0:00».
5. As a listener resuming a book, I want the hero card to show my real progress (percent + remaining), so that «ПРОДОВЖИТИ СЛУХАТИ» is honest even in the first minutes of a chapter.
6. As a listener, I want the full player to fit one screen, so that I never scroll for the controls.
7. As a listener, I want the player to name the real narrator or stay silent, so that a fabricated «4read Voice Narrator» never appears.
8. As a maintainer, I want the title rules pinned by pure JVM tests and the one-time cleanup pinned by a Room test, so that the scrub never regresses.
9. As a maintainer, I want the catalog-card duration covered by snapshot tests and the feed join by a paging test, so that the new SQL stays correct under the Works/Editions split.

## Implementation Decisions

- **Title normalization lives in the metadata-assertions module** as a pure title-claim rule: strip a curated list of Ukrainian SEO phrases from the end of the title, case-insensitive, across separators (` - `, ` — `, ` (`, `, `, `|`); trim; if nothing is left after the cut, keep the original title (never blank).
  - Phrases: «аудіокнига слухати онлайн», «слухати онлайн», «аудіокнига онлайн», «слухати онлайн безкоштовно», «аудіокнига українською».
- **The rule applies on every write path** that persists a claimed title: source-page imports, captured-page imports, catalog listing upserts, WebView-session hydration, and the Work row — same assertion, one place.
- **One-time startup cleanup**: an idempotent SQL pass (no schema change) rewrites stored titles with the same rules for existing rows; a second run matches nothing.
- **Feed duration**: the endless-feed query joins the Edition duration per Work (the Edition owns listening totals, ADR-0010), exposing it on the feed row; null/zero renders nothing.
- **Catalog cards**: the feed card renders `Ч:ММ:СС` under the author; the cover card renders it under the title (the cover card shows no author today). Format is the shared time formatter (always with seconds, e.g. «16:41:11» / «0:42:28»). Both hide the line when the duration is unknown.
- **Hero progress**: the hero card receives the cumulative position computed by the library model (the single source of truth already used by library cards and the player); it never recomputes from the in-chapter offset.
- **Player layout**: the scroll wrapper is removed; the cover block becomes the flexible element (weight-based, aspect preserved, capped so it cannot blow up on tablets), so the whole column always fits.
- **Narrator scrub**: the player's «Читає …» line renders through the existing real-names-only helper — same rule as the book page.

## Testing Decisions

- **What makes a good test:** external behaviour — a title with each separator variant gets scrubbed, a clean title stays, the pass is idempotent; a feed row carries its Work's duration; a card renders the duration line only when known; the hero shows cumulative percent; the player renders without scroll and without the fabricated narrator line.
- **Modules tested:** the pure title rule (JVM, prior art MetadataAssertions tests); the one-time cleanup (Room, prior art DAO tests); the feed-duration join (prior art WorkFeedPagingTest); card rendering (Roborazzi snapshot seams — feed card, cover card, hero card, player screen); the existing full suite stays green.
- **Prior art:** `ui/snapshots/` Robolectric snapshots (Pixel8, sdk 36), pure-function metadata tests, Room module tests over in-memory DB, CI gate (assembleDebug + testDebugUnitTest + Kover).

## Out of Scope

- Per-chapter durations on catalog cards (durations are filled by playback; the book total is enough for the at-a-glance decision).
- Moving the duration elsewhere on the book page (its stats pill already exists).
- Re-importing existing books after the title scrub (the startup pass covers stored rows).
- Changing the mini player bar or any progress surface besides the hero card.
- Android Auto, recommendation engine, or any catalog structure beyond the duration join.

## Further Notes

- The narrator scrub in the player and the hero-card cumulative fix are already drafted in the working tree; this spec pins them as decisions.
- Device verification: OnePlus 8 Pro via wireless ADB after the change set lands.
