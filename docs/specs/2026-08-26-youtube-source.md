# [Spec] YouTube-embed fallback for audio-less 4read pages — 2026-08-26

> **Status:** Proposed — спільний hotfix+feature release v1.3.6. Root bug
> («грає попередня книга») fixed separately in #375 / `d2d8773`.
> **Source:** device session 2026-08-26: book «Звички невдах» (4read 4355,
> narrated by «Корисні книги») has no playerjs audio — the page embeds a
> single YouTube video (`ozaZXk5Qcwc`). Import honestly left a chapterless
> shell; the listener asked for the book to play FROM the YouTube source,
> streaming and offline.

## Problem Statement

A growing share of 4read pages carry no playerjs audio at all — the
narration lives on YouTube, embedded in the page. Today those imports
produce a chapterless shell the player refuses (correctly, since #375,
with the honest «Книга недоступна» card). The book is available — its
audio is on YouTube — but the app cannot reach it.

## Solution

When a 4read page yields no direct audio streams but embeds YouTube
video(s), the parser resolves the book's chapters FROM the embed: one
video = one chapter, and the chapter's track URL is the YouTube watch
URL. At play and download time the watch URL is resolved to a concrete
progressive audio stream URL through NewPipeExtractor, transported over
the ONE shared OkHttp client (`TransportClients.okHttp` — DoH and the
privacy route preserved; the extractor adds no second transport).

## User Stories

1. As a listener, I can play a book whose 4read page only embeds YouTube,
   so «Книга недоступна» stays reserved for books that truly have no audio.
2. As a listener, I can download such a book for offline listening.
3. As a listener, I see honest data: an unresolved YouTube chapter shows
   «Тривалість невідома», never a fabricated duration (ADR-0014), and the
   real duration back-fills once the engine reports READY.
4. As a maintainer, all YouTube knowledge (URL shapes, stream selection,
   extractor wiring) lives in one module behind one seam.

## Implementation Decisions

- **One source, one provenance.** No new SourceEntity type: the Source
  stays the 4read page (`type = "4read"`); YouTube-ness is a property of
  the track URL host. Per-source Referer rules, healing and catalog
  refresh all keep working unchanged.
- **The watch URL is persisted; the signed stream URL never is.** YouTube
  stream URLs expire (~6h) — every prepare/download re-resolves through
  the `StreamUrlResolver` seam: `(String) -> String?`, identity for
  non-YouTube URLs, extraction for YouTube ones.
- **Progressive audio only.** The resolver prefers M4A progressive
  streams (highest bitrate), then any direct audio URL — DASH/HLS
  manifests are not this concept, ExoPlayer gets a plain URI.
- **Heal is automatic.** An expired signature surfaces as a 403 on the
  resolved URL → the existing `StreamHealPolicy` path re-prepares →
  `prepareChapter` re-resolves fresh. An exhausted heal budget surfaces
  the existing honest «Книга недоступна» state (ADR-0019) and lands in
  `playback_failures` like any other prepare failure.
- **Extraction failures are honest.** Resolver returning null (video
  gone, extraction broken) → prepare reports the failure through the
  existing `reportPlaybackFailure` path; download counts the chapter as
  failed. No fabricated audio.
- **Downloader = the shared client.** NewPipe's `Downloader` interface is
  implemented over `TransportClients.okHttp` so YouTube requests ride the
  same pool, identity, DoH and privacy route as every other request.

## Testing Decisions

- **Parser (pure JVM fixture):** a trimmed real 4read page fixture with a
  YouTube embed and no playerjs yields one chapter whose stream URL is
  the watch URL; a page WITH playerjs audio never gains YouTube chapters
  (no false positives); multiple embeds → multiple chapters in document
  order, deduplicated.
- **Stream selection (pure JVM):** given a list of candidate streams, the
  resolver picks the highest-bitrate M4A progressive URL, then any direct
  audio, never a manifest.
- **Player seam (JVM, FakePlayerEngine):** a YouTube track triggers the
  injected resolver and prepares the engine with the RESOLVED url; a
  null resolution reports the failure and never fabricates audio.
- **Device check** (`docs/phone-test/PLAN.md`): open «Звички невдах» —
  playback starts from the YouTube audio; «Завантажити» completes; the
  downloaded book plays offline (airplane mode).

## Out of Scope

- YouTube as a browseable catalog source (search/feeds) — only the
  page-embed fallback.
- Playlists/multi-part videos as multiple chapters — one embed = one
  chapter (a multi-part book would need the playlist expansion concept
  revisited).
- Background audio while the app shows the YouTube page — audio-only
  streams through the existing engine.

## Further Notes

- Chapter duration is unknown until the engine reports READY; the
  existing `persistRealDurationIfKnown` back-fills the chapter row on the
  first READY — no new persistence path.
- NewPipeExtractor v0.26.5 (JitPack) requires `org.mozilla.javascript.*`
  keep rules under minification.
