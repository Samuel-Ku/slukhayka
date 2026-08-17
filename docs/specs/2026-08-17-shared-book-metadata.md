# Spec-30: Спільні метадані книг у Firestore — миттєві тривалості й канонічні обкладинки

> **Status:** ready-for-agent. Синтезовано з сесії `/grill-me` 2026-08-17: 12 питань,
> усі відповіді за рекомендаціями. Один новий шов (`SharedBookMetaStore`);
> решта — на наявних швах.

## Problem Statement

Слухач відкриває пошук чи каталог і бачить картки книг, але «приємного»
перегляду немає: **тривалість** часто відсутня (сторінка джерела її не дає, а
локальне виведення повільне — батчами по 5 книг раз на 6 годин, локальні
імпорти взагалі пропускаються), а **обкладинки** нестабільні (одна книга на
трьох джерелах показує три різні URL, частина з яких мертві). Хочеться, щоб
пошук одразу показував обкладинку + повну тривалість + начитку, не змушуючи
користувача чекати і не ламаючи принцип «чесних даних». У Слухайки вже є
безкоштовний Firebase, який нині використовується вузько — лише для спільного
кешу серій/всесвітів.

## Solution

Розширити наявний Firebase-шар на **анонімні спільні метадані про книги**:
спільний кеш, де **тривалість** зберігається за ідентичністю начитки (Edition),
а **канонічний URL обкладинки** — за ідентичністю твору (Work). Читання —
client-first із пріоритетом «виправлення користувача → локальне відоме →
Firestore (заповнити прогалину) → джерело», з дзеркаленням хітів у локальну базу
для офлайн-роботи. Запис — гібридний: тривалості пишуть назад пристрої
(низька шкода, самовиправляються), обкладинки спочатку куруються. Доступ
захищено AppCheck + правилами Firestore, з межею безкоштовного тарифу.

## User Stories

1. As a listener, I want to see a book's full duration in search results immediately, so that I can choose a book without waiting for the app to derive it.
2. As a listener, I want a stable, canonical cover next to each search result, so that the list looks tidy and I can recognise books at a glance.
3. As a listener, I want the narrator shown next to each result, so that I know who reads before opening the book.
4. As a listener, I want richer metadata to appear without any action from me, so that the app feels faster and more pleasant.
5. As a listener, I want previously-seen metadata to work offline, so that I am not dependent on the network after the first view.
6. As a listener, I want my own metadata corrections to never be overwritten, so that my manual fixes survive refreshes and shared-cache reads.
7. As a listener, I want a wrong crowd-written duration to eventually self-correct, so that a bad value does not stick forever.
8. As a listener, I want the feature to degrade silently (no duration / no cover) rather than crash or show a fabricated number, so that the honesty of the data is preserved.
9. As a listener, I want covers to keep displaying when the source URL dies, so that the library stays presentable.
10. As a listener, I want this to stay free forever, so that the app's promise holds as the user base grows.
11. As a privacy-conscious listener, I want only anonymous book metadata to leave my device, so that no personal listening data is collected.
12. As the maintainer, I want durations to be crowd-written back with provenance, so that the catalog enriches itself without manual curation of thousands of books.
13. As the maintainer, I want covers seeded by curation first, so that highly visible metadata cannot be poisoned by untrusted writes.
14. As the maintainer, I want AppCheck enforced and Firestore security rules written, so that the existing unauthenticated write path is not widened.
15. As the maintainer, I want batch reads limited to the books actually on screen, so that the free Firestore quota is not exhausted.
16. As the maintainer, I want a ~180-day freshness floor, so that stale values can heal without daily re-reads.
17. As the maintainer, I want Firestore access behind a pure-JVM seam with degrade-never semantics, so that a Firebase failure never breaks playback or the catalog.
18. As the maintainer, I want the document shape pinned by fixture tests, so that codec changes are safe without a live Firebase.
19. As the maintainer, I want this feature to land in v1.1 without hard-blocking the release, so that the 10-user migration is not held hostage.

## Implementation Decisions

- **Anonymous shared metadata only.** Stored values are book-level facts
  (duration, cover URL), keyed by stable domain identities; no personal data,
  no listening progress, no device sync.
- **Two key spaces.** Duration lives on a document keyed by the **Edition id**
  (per rendition — two narrations of one Work never share duration). Cover URL
  lives on a document keyed by the **Work mergeKey** (one cover per Work, shared
  across narrations). Narrator is **not** cached — sources already provide it
  and search shows it directly.
- **Covers are URL-only.** The shared document holds a canonical cover URL; the
  image itself is not rehosted. Rehosting into object storage (Cloudflare R2
  behind a worker) is a separate later slice, not this one.
- **New pure-JVM seam.** A `SharedBookMetaStore` interface exposes
  `getDuration(editionId)` / `getCover(mergeKey)` / `putDuration` /
  `putCover`, mirroring the existing `SharedUniverseStore` shape: miss or
  failure yields null, never a throw. A pure-JVM codec pins the document shape;
  the Firestore implementation is thin glue with no logic.
- **Client-first read precedence.** Resolution order from highest to lowest:
  listener's Metadata Override → locally known value in the local database →
  Firestore (only to fill a gap) → the source. A Firestore hit is mirrored into
  the local database and thereafter works offline; the cache never overwrites a
  listener's override.
- **Freshness floor.** A value read from Firestore is considered fresh for
  ~180 days; after that it may re-read/re-derive, but the stale value is shown
  until a fresher one exists (no daily re-reads, no waiting on refresh).
- **Hybrid write path.** Durations are written back from devices, best-effort,
  with a sanity bound (`> 0` and below a plausible ceiling) and provenance
  (derived-at timestamp and origin). Covers are curated/seeded first; untrusted
  cover write-back is deferred until AppCheck and reporting are in place.
- **Access hardening.** AppCheck is enabled (the dependency already exists but
  is unused) and Firestore security rules are written — the current universe
  store writes unauthenticated, and this work must not widen that hole.
- **Free-tier operating boundary.** Firestore reads are batched for the books
  actually visible on screen, each hit migrates to the local database (so a
  re-view pays nothing), and quota exhaustion degrades to today's behaviour
  (no duration, no cover) rather than erroring.
- **Sequencing.** The feature lands on `main` before the `v1.1` tag if it is
  ready and green, but it does not hard-block the tag: if it slips, it ships in
  `v1.1.1` immediately after.

## Testing Decisions

- **What makes a good test:** assert external, observable behaviour — the seam's
  read/write contract, precedence, and degrade-never — not Firebase internals.
- **Codec (pure JVM).** Document encode/decode is pinned by fixture tests, using
  the `SharedResolutionCodec` tests as prior art.
- **Seam (pure JVM).** `SharedBookMetaStore` read/write, fill-the-gap
  precedence, and degrade-never behaviour are tested over a fake store, using
  the universe store fixture tests as prior art.
- **Mirror-to-local-database.** Robolectric tests verify that a Firestore hit is
  persisted locally and that a listener's override is never overwritten.
- **Sanity bound.** The duration sanity check is a pure function tested in
  isolation.
- **AppCheck + security rules.** Verified by deploying the rules and by a
  device smoke test (writes accepted from a valid app, rejected otherwise),
  not by a unit test.
- **Prior art.** The universe knowledge base (spec-26) is the direct template:
  same seam shape, same degrade-never contract, same pure-JVM codec + fixture
  strategy.

## Out of Scope

- **Personal data, listening progress, and cross-device sync** (needs identity,
  consent and a privacy policy; contradicts the "no data collection" promise).
- **Cover rehosting** into Cloudflare R2 (or any object storage) behind a
  worker — a separate later slice.
- **Caching the narrator** (already provided by sources).
- **Cloudflare Images** and any paid storage/CDN upgrade.
- **Play Store publishing** and Play In-App Updates.
- **Any paid Firebase tier.**

## Further Notes

- The existing universe store writes unauthenticated (no `firestore.rules`, the
  AppCheck dependency is present but unused). This spec explicitly requires
  hardening both before broadening the write surface.
- Honest-data (ADR-0014) and narrator-on-Edition (ADR-0010) constrain the
  design: only real values are shown, and duration is Edition-scoped.
- The free tier is a hard operating boundary, not a cost estimate to revisit.
- The feature reuses the universe pattern end-to-end rather than inventing a new
  data flow, so it fits the existing architecture without new transport or
  parsing code.
- The local duration passes (`DurationEnrichment`, `ChapterDurationProbe`) stay
  as the **first-resolver fallback** for books not yet in the shared cache: the
  duration they derive/probe is exactly what gets written back, so the cache
  grows from them — the cache does not replace them, it propagates their work.
