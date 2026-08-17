# Spec-32: Спільний кеш повних профілів книг — менше запитів до джерел

> **Status:** ready-for-agent. Синтезовано з автономної сесії `/grill-me`
> 2026-08-17 (Q1–Q10, усі за рекомендаціями). Один новий шов (профіль у наявному
> `SharedBookMetaStore`); решта — на наявних швах.

## Problem Statement

Щоразу, коли слухач відкриває книгу, якої ще немає на його пристрої, застосунок
тягне HTML-сторінку джерела, щоб отримати опис, розділи і посилання на аудіо.
Це повільно, навантажує сайти джерел і вразливе: якщо сайт закриє сторінки
захистом Cloudflare/анти-ботом, новий користувач взагалі не отримає профіль
книги. Водночас цей самий профіль уже хтось колись резолвив — але результат не
зберігається спільно, і кожен наступний пристрій повторює ту саму роботу з нуля.

## Solution

Зберігати **повний резолвлений профіль книги** в спільному кеші (Firestore), з
ключем «джерело × начитка». Профіль пишеться одразу після успішного резолву
сторінки (імпорт/відкриття/гідрація) — повільно й колаборативно, силами
користувачів. Коли новий пристрій відкриває книгу, якої нема в його локальній
базі, а в кеші є свіжий профіль — сторінку джерела не тягнуть, профіль беруть з
кешу. Це зменшує кількість запитів до сайтів і дає стійкість, якщо HTML-сторінки
джерел підуть за захист.

## User Stories

1. As a listener, I want the first open of a book on my device to use a shared cached profile instead of fetching the source page, so that it loads faster.
2. As a listener, I want my device to contribute the resolved profile after I open a book, so that the shared cache grows for everyone.
3. As a listener, I want the description, chapter list, narrator, series, genres, rating and cover all present from the cached profile, so that the book page is complete without the source.
4. As a listener, I want chapter titles and stream links cached, so that I can start listening without re-resolving the page.
5. As a listener, I want a stale cached stream link to self-heal (re-fetch the page and retry), so that a moved file does not leave the book broken.
6. As a listener, I want the app to fall back to the cached profile if the source page fails to load, so that a blocked page still shows the book.
7. As a listener, I want my own metadata corrections to never be overwritten by the shared profile, so that my fixes stick.
8. As a listener, I want a profile that has never been resolved to still resolve from the source as today, so that nothing regresses for new books.
9. As a privacy-conscious listener, I want only anonymous book facts (never my listening history) to leave my device, so that the no-tracking promise holds.
10. As the maintainer, I want one profile unit per source and narration, so that the same book on different sources keeps its own stream links.
11. As the maintainer, I want the profile written back on resolution (not on playback), so that even books opened but not played get cached.
12. As the maintainer, I want writes protected by App Check and the security rules already in place, so that the shared base cannot be poisoned.
13. As the maintainer, I want sane limits (URL scheme, field length, chapter count) on written profiles, so that a bad client cannot bloat the base.
14. As the maintainer, I want a freshness window (~90 days) with fail-open on refresh failure, so that stale data is preferred over no data.
15. As the maintainer, I want the free Firestore tier to stay the operating boundary, so that the app remains free as the base grows.
16. As the maintainer, I want this to reuse the existing shared-metadata seam and App Check setup, so that it extends spec-30 instead of building a parallel store.

## Implementation Decisions

- **One profile unit per source × narration.** The cached document is the full
  resolved profile: description, ordered chapters (title + stream URL +
  duration), narrator, series, genres, rating, cover URL. Keyed by
  `sourceId + editionId`, because stream URLs are per-source while the narration
  is per-Edition.
- **Reuses the spec-30 seam.** The existing shared-metadata store grows
  `getProfile`/`putProfile` plus a pure-JVM codec for the profile shape; App
  Check and the Firestore security rules already landed under spec-30 are reused
  as-is.
- **Write on resolution.** The profile is written back, best-effort, immediately
  after a successful page resolution (import, detail open, hydration) — not
  after playback. A failing write contributes nothing and never blocks the local
  result.
- **Read skips the page when fresh.** When a device lacks the book locally and a
  fresh profile exists in the shared base, the source page is not fetched; the
  profile is mirrored into the local database and works offline afterwards.
- **Freshness ~90 days, fail-open.** A profile is considered fresh for ~90 days.
  After that the page is re-fetched and the profile refreshed; if the refresh
  fails (source down/blocked), the stale profile is still served rather than
  nothing.
- **Read precedence unchanged.** Listener's Metadata Override → local database →
  shared profile → source. The shared profile replaces the page fetch only when
  the book is not already known locally and the profile is fresh.
- **Self-healing stream URLs.** A 404/403 during streaming triggers a background
  re-fetch of the page, a profile refresh, and one retry with the fresh URL; a
  persistent failure surfaces an honest "book unavailable" state.
- **Sane write limits.** Stream URLs must be `http(s)`; field lengths and the
  chapter count are bounded; provenance (source, resolvedAt) rides with every
  write.
- **All sources cached.** Caching references (links), not audio copies; playback
  still hits the source CDN with the correct per-source headers. lihtar's
  restrictive ToS is noted but adds negligible risk since no content is copied.
- **Spec-30 relationship.** This is an extension, not a replacement: the seam,
  App Check and rules are reused; the not-yet-closed spec-30 tickets (cover
  seed, duration write-back) are absorbed into this spec's slices.

## Testing Decisions

- **What makes a good test:** assert external behaviour — a profile round-trips,
  a fresh profile suppresses the page fetch, a stale one does not, a failed
  refresh still serves stale data, and a stream 404/403 self-heals — not the
  Firestore internals.
- **Profile codec (pure JVM).** Encode/decode pinned by fixture tests; prior art:
  the shared-resolution codec tests.
- **Store seam (pure JVM).** get/put, freshness window and fail-open tested over
  a fake store; prior art: the universe store fixture tests.
- **Write-back and read-skip (Robolectric).** Resolution writes the profile;
  a fresh cached profile suppresses the source fetch; a listener override is
  never overwritten.
- **Self-healing policy (pure JVM + device smoke).** The 404/403 → refetch →
  retry decision is a pure function tested in isolation; the end-to-end retry is
  verified by a device smoke test.
- **Limits.** URL-scheme and chapter-count bounds tested at the codec boundary.

## Out of Scope

- Storing or serving the audio content itself (playback stays on the source CDN
  or local offline downloads).
- Independent offline playback — a cached link still needs the source's audio
  CDN to be reachable.
- Per-user listening history, progress or cross-device sync (needs identity and
  consent; contradicts the no-tracking promise).
- Bypassing source hotlink protection (per-source Referer/User-Agent rules stay).
- Any paid Firebase tier or separate object storage.

## Further Notes

- Local Room already caches the profile on-device, so this shared cache benefits
  a *different* user/device, not a repeat open on the same device.
- The Cloudflare motivation is served for the HTML pages (metadata survives), but
  not for the audio bytes — that is explicitly out of scope.
- The `sourceId + editionId` key reuses the deterministic Edition identity so the
  same narration resolves the same key across devices.
- This spec absorbs spec-30's remaining cover/duration work into its own slices.
- The local duration passes (`DurationEnrichment`, `ChapterDurationProbe`) stay
  as the **first-resolver fallback** for books not yet in the shared cache: the
  duration they derive/probe is exactly what gets written back, so the cache
  grows from them — the cache does not replace them, it propagates their work.
