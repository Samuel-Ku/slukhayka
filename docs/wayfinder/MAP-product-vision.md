---
name: mature-audiobook-library-vision
label: wayfinder:map
created: 2026-08-07
status: active
tracker: github-issues
map_issue: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/22
---

# Wayfinder Mirror — `mature-audiobook-library-vision`

> **Canonical artifact lives on the GitHub issue tracker.**
> This file is a local pointer so a checkout without network access
> still shows the destination and current frontier.
>
> Map issue: [#22](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/22)

## Destination

A calm, unified Ukrainian audiobook library — not "Netflix for audiobooks". Open the app and resume in one tap, find new books, tidy a single library (local files + 4read online), and listen for hours without touching the screen. The way there is the maintainer's four-stage rework: (1) UX framework, (2) smart library, (3) native 4read, (4) system integrations. The map is done when every remaining decision across the four stages is resolved and the product vision is implementable stage by stage.

## Notes

Domain: Android (Kotlin 2.2.10, Compose Material 3, Media3 1.3.1, Room 5-entity). Source of truth: the maintainer's product-vision document (grilled 2026-08-07) and `docs/specs/2026-08-07-listen-first-ia.md`.

Skills each session should consult: wayfinder (this map), /grilling and /domain-modeling for HITL tickets, research for AFK tickets, /prototype for rough artifacts.

Standing preferences:

- Decisions, not deliverables: implementation happens later per stage via /implement + /to-tickets.
- One ticket per session, claimed by assigning before any work.
- Stage order from the maintainer's document: 1 → 2 → 3 → 4.
- External-data rule (2026-08-07 grilling): blocks/collections ship only when the data can come from 4read or a verified external source.

## Decisions so far

- [Listen-first IA (spec-9)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/17) — 3 tabs Слухати/Огляд/Медіатека, landing on Слухати, series metadata from 4read posters, continue-the-series block, empty-state CTAs.
- [Ukrainian Netflix-style Catalog & Library (spec-8)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/8) — native Explore rows, series pages, SAF import (file + folder), WebView only as "open on site", local-file playback.
- [Empty states audit](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/24) — house standard: icon + title + explanation + 1–2 CTAs in two sizes; ListenScreen is the reference; Медіатека sub-tabs, search-empty in Огляд, SeriesScreen retry are the gaps. Becomes composables under the Design-system ticket.
- [4read catalog data audit](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/31) — book page carries rating+votes, multi-genre, narrator, total duration, full per-series list; poster only title/author/cover/series. Category/author landing URLs unconfirmed. Duration/narrator browsing needs per-book enrichment. Feeds Browse tab expansion.
- [Enrichment data spike](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/30) — ~65–75 % of the catalog externally matchable; source order Google Books API (free key) → OpenLibrary (keyless) → Wikipedia (disambiguation needed) → Goodreads (tags). Smart collections GO with per-book fallback hiding non-matches. Feeds Smart collections design.
- [Android Auto: MediaLibraryService](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/32) — GO: migrate MediaSessionService → MediaLibraryService (adds browse tree), maps onto Room books/chapters, no auto-start paths. Stage-4 work.
- [Cast feasibility](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/33) — GO but last: media3-cast CastPlayer + Default Media Receiver (no fee/registration); custom receiver only for TV chapter UI. Back of stage 4.
- [Backup & sync approach](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/35) — Auto Backup (dataExtractionRules) covers the Room DB free; manual SAF export/import for a full backup; audio excluded (over the ~25 MB cap); re-pick + re-scan after reinstall. Cloud sync ruled OUT of scope.
- [Adaptive two-pane layout](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/36) — GO: stable material3-adaptive `ListDetailPaneScaffold` for Медіатека; `selectedBookId` maps onto the scaffold content key; gated on Library rework.
- [Design system](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/23) — graphite-navy dark (primary) + warm-paper light schemes, one amber accent, editorial type scale, spacing/radius/≥48 dp touch tokens, cards 10–14 dp with minimal shadows, no card-in-card, animation budget decided; primitives `AppSectionHeader`/`EmptyState`/`EmptyStateRow` (empty-states house standard); Слухати migrated as the reference; legacy `Cyber*` aliases remain for other screens. Unblocks themes/player/library/book-page/accessibility.

## Tickets (children of map #22)

- #23 — Design system `wayfinder:grilling` — ✅ resolved 2026-08-07.
- #24 — Empty states audit `wayfinder:task` — ✅ resolved 2026-08-07.
- #25 — Smart rewind & position history `wayfinder:grilling` — frontier.
- #26 — Speed: per-book memory & defaults `wayfinder:grilling` — frontier.
- #27 — Sleep timer & bookmark upgrades `wayfinder:grilling` — frontier.
- #28 — Three-level deletion `wayfinder:grilling` — frontier.
- #29 — Import preview & corrections flow `wayfinder:prototype` — frontier.
- #30 — Enrichment data spike `wayfinder:research` — ✅ resolved 2026-08-07.
- #31 — 4read catalog data audit `wayfinder:research` — ✅ resolved 2026-08-07.
- #32 — Android Auto: MediaLibraryService `wayfinder:research` — ✅ resolved 2026-08-07.
- #33 — Cast feasibility `wayfinder:research` — ✅ resolved 2026-08-07.
- #34 — Home-screen widget `wayfinder:grilling` — frontier.
- #35 — Backup & sync approach `wayfinder:research` — ✅ resolved 2026-08-07.
- #36 — Adaptive two-pane layout `wayfinder:research` — ✅ resolved 2026-08-07.
- #37 — Light and dark themes `wayfinder:grilling` — **blocked by #23**.
- #38 — Player redesign `wayfinder:prototype` — **blocked by #23**.
- #39 — Library filters, sorting & book card `wayfinder:grilling` — **blocked by #23**.
- #40 — Book page completeness `wayfinder:grilling` — **blocked by #23**.
- #41 — Accessibility pass `wayfinder:grilling` — **blocked by #23**.
- #42 — Re-scan, duplicates & missing files `wayfinder:grilling` — **blocked by #29**.
- #43 — Smart collections design `wayfinder:grilling` — **blocked by #30**.
- #44 — Browse tab expansion `wayfinder:grilling` — **blocked by #31**.

Fog lives in the GitHub map body (#22). The frontier: any open, unblocked, unclaimed ticket above.

## Not yet specified

- Offline download of 4read streams (licensing) — gates the offline line of stage 3.
- Smart-import heuristics (covers/authors for local files, track-title normalization) — the enrichment spike settled external sources (Google Books/OpenLibrary); the title+author normalization design is still open, graduates with the import-preview ticket.
- Category/author landing URL scheme on 4read — surfaced by the catalog audit; needed before Browse can offer genre/author landing pages.
- Player visual language (cover-colour gradient extraction) — inside the player redesign ticket.

## Out of scope

- Social features, comments, profiles, achievements/streaks.
- Custom complex equalizer.
- AI recommendations; transcripts and auto-recap — late experiments.
- Mandatory registration.
- Deeper WebView dependency — capped by spec-8.
- Cloud sync backend (accounts, server ops) — resolved out of scope by the Backup & sync approach; "sync" means local backup.
