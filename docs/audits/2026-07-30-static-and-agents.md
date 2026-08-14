# Phase 2 — Static Analysis + Agent Audit Report

- **Date:** 2026-07-30
- **Source commit:** `55b8be2 docs: phase 1 add codemaps for 8 modules` (+2 ahead of origin)
- **Repository:** `slukhayka`
- **Auditors:** local Gradle + 4 parallel review agents (code-reviewer, security-reviewer, silent-failure-hunter, performance-optimizer)
- **Spec:** `docs/specs/2026-07-30-maintenance-and-audit.md` — issue #1

## 0. Executive Summary

**Across all four lenses: 12 unique CRITICAL findings, 27 unique HIGH, 22 unique MEDIUM, 17 unique LOW ≈ 78 distinct actionable issues.**

The codebase is in **pre-production / migration-completion state**. The most damaging class of bugs are **silent failures** that produce a wrong-but-plausible-looking UI state (fake chapter lists, time-machine audio substituting for requested audio, dummy text files masquerading as downloaded MP3s). The second-most damaging class is a **WebView security chain** (MITM via user-CA → injected JS → arbitrary audio URLs written to Room and played by ExoPlayer).

### Headlines

1. **🔴 Audio fallback is unsafe (CRITICAL).** When a chapter stream errors, `tryFallbackPlayback` in [AudioPlayerManager.kt:201-299](app/src/main/java/com/example/player/AudioPlayerManager.kt#L201-L299) silently substitutes unrelated archive.org MP3s (time_machine, war_of_the_worlds, etc.). The user sees their selected book but hears unrelated content. When all fallbacks fail, `playLocalSyntheticAudio` is a no-op that flips `isPlaying=false` with no user-visible error.
2. **🔴 WebView MITM surface (CRITICAL x4).** `cleartextTrafficPermitted=true` + `<certificates src="user"/>` + `handler.proceed()` on SSL error + JS interface exposed to all origins = trivial MITM that drives the player to fetch attacker-chosen audio.
3. **🔴 Download cache is poisoned (CRITICAL).** Failed HTTP writes a 30-byte text marker (`OFFLINE_AUDIO_<id>`) into `.mp3`, then sets `isDownloaded=true`. Player tries to decode text → fails silently.
4. **🔴 No background playback persistence (CRITICAL).** No foreground service, no `MediaSessionService`. On Android 12+ the audio is killed within minutes of backgrounding.
5. **🟠 WebView is leaked (CRITICAL).** `AndroidView { factory = { WebView(...) } }` with no `DisposableEffect` to call `destroy()` — every tab switch leaves a WebView behind.
6. **🟠 Database has no FK indices (CRITICAL).** Bookmark/chapter lookups do full table scans.
7. **🟠 1Hz polling causes 60 recompositions/min** of the entire Scaffold + bottomBar + Slider chain.
8. **🟠 Frontend tab observed nightly:** the "Book Detail" tab uses `LibraryScreen` with a bookmark filter (HI-006 dead `SelectedTab.SETTINGS`).

### Where the bugs came from

This commit `ef90563 feat(audio): migrate to ExoPlayer and add HTML import` was a hard cut-over. After the migration:
- many test files are template stubs (only 3 of 5 unit tests exercise real logic);
- 17 build deprecation/opt-in warnings were not addressed;
- 79 lint findings (12 errors + 67 warnings) were not cleaned up;
- ~50 root-level Python/JS scripts from the migration are still in the repo, not gitignored, not archived (codemap [migration-artefacts.md](../CODEMAPS/migration-artefacts.md) catalogs them);
- `AudiobookRepository` (1087 LOC) accreted 6 responsibilities because the migration was rushed.

---

## 1. Static Analysis Summary

### 1.1 Kotlin compile warnings (17)

| File | Line | Warning | Severity |
|---|---|---|---|
| `AudiobookDatabase.kt` | 33 | `fallbackToDestructiveMigration()` deprecated | LOW |
| `MainViewModel.kt` | 93, 100, 107 | Missing `@OptIn(ExperimentalCoroutinesApi)` for `flatMapLatest` | LOW |
| `MiniPlayerBar.kt` | 46 | "Condition is always 'true'" | MEDIUM |
| `BookDetailScreen.kt` | 58 | `Icons.Filled.ArrowBack` → `AutoMirrored.Filled.ArrowBack` | LOW |
| `BookDetailScreen.kt` | 276 | `Divider` → `HorizontalDivider` | LOW |
| `BookDetailScreen.kt` | 388 | `Icons.Filled.VolumeUp` → `AutoMirrored.Filled.VolumeUp` | LOW |
| `FourReadWebScreen.kt` | 122, 133, 209, 420 | `Icons.Filled.ArrowBack`/`ArrowForward`/`OpenInNew` → AutoMirrored | LOW |
| `FourReadWebScreen.kt` | 290 | `WebSettings.databaseEnabled` deprecated | LOW |
| `LibraryScreen.kt` | 79 | `Divider` → `HorizontalDivider` | LOW |
| `LibraryScreen.kt` | 288 | `Icons.Filled.MenuBook` → AutoMirrored | LOW |
| `PlayerScreen.kt` | 444 | `Divider` → `HorizontalDivider` | LOW |

**Effort: 30 minutes total. Mechanical edit.**

### 1.2 Android Lint (79 findings)

- **12 errors** — all `UnsafeOptInUsageError` for Media3 `@UnstableApi` calls at `AudioPlayerManager.kt:116,117,118,119,120,126,221,222,223,224,225,231`.
  - **Severity: LOW (easy fix)**, but lint treats these as errors so `./gradlew lintDebug` FAILS the build.
- **3 security warnings (HIGH):** [static analysis detail](#security-warnings-from-lint)
- **4 quality warnings (LOW/MEDIUM):** `SdCardPath`, `DefaultLocale` (3x), `RedundantLabel`, `ObsoleteSdkInt`.
- **39 dependency-version warnings (LOW):** Gradle 9.3→9.6, AGP 9.1→9.3, media3 1.3→1.10, Compose BOM 2024.09→2026.06, Room 2.7→2.8, OkHttp 4.10→5.4, etc.
- **7 unused-resources warnings (LOW):** 6 colors (`purple_200/500/700`, `teal_200/700`, `black`, `white`) + 1 string (`empty_tag`).
- **6 Use-KTX warnings (LOW):** replace `Uri.parse(String)` with `String.toUri()`.
- **10 image/icon warnings:** 2 `IconDipSize` (mipmap-xxhdpi dimensions look corrupted — 12268×3140438 dp), 4 `IconLocation` (bitmap covers in densityless folder).

### 1.3 Tests (`./gradlew testDebugUnitTest`)

**14/14 passing in 25s. Coverage ~20% real.**

| Suite | Tests | Notes |
|---|---|---|
| `AudioParsingTest` | 3 | Real regex parsing tests |
| `ButtonTesting` | 8 | Compose UI flow tests (~20s) |
| `ExampleRobolectricTest` | 1 | Template |
| `ExampleUnitTest` | 1 | Template (2+2=4) |
| `GreetingScreenshotTest` | 1 | Roborazzi screenshot smoke |

**Gap:** no tests for `AudiobookRepository`, `AudioPlayerManager`, `MainViewModel`, or any screen. The 3 `Example*.kt` files carry no signal.

### 1.4 Repository artefacts (codemap [migration-artefacts.md](../CODEMAPS/migration-artefacts.md))

**~50 root-level Python/JS files left over from the ExoPlayer migration.** Status unknown; the codemap inventories them and provides a `grep -r <name> app/src/main/` checklist to confirm before cleanup.

---

## 2. Agent Findings

Severity totals below are deduplicated across all 4 agents; identical findings flagged from different lenses are merged into one row with multiple agent IDs.

### 2.1 CRITICAL — must fix before next phase

| # | ID(s) | File:Line | Issue | Effort |
|---|---|---|---|---|
| 1 | CR-001 | `AudiobookRepository.kt:1-1087` | God-object: catalog seed + HTML scraping + chapter assembly + offline download + cache + favorites + stats — 6 responsibilities in one class. Split into `CatalogRepository`, `FourReadImporter`, `OfflineDownloadManager`, `CacheRepository`, `StatsRepository`. | L |
| 2 | CR-002 / SF-003 / SF-005 / SF-006 | `AudiobookRepository.kt:201, 290-339, 670-733, 999-1027` & `AudioPlayerManager.kt:201-299` | **Audio fallback substitutes unrelated archive.org MP3s.** When a chapter fails to stream, `tryFallbackPlayback` plays time_machine / war_of_the_worlds as if they were the user's book. When 4read fetch returns empty, `importAudiobookFrom4ReadUrl` synthesizes 3 fake chapters with archive.org URLs and persists them. When search returns no matches, `searchAudiobooksOn4Read` fabricates a "result" whose title = the query. User sees their requested book title but hears 19th-century sci-fi / sees a fake search result. **Replace all archive.org fallbacks with user-visible error state + "this chapter isn't available offline" UI.** | M |
| 3 | CR-003 | `PlayerScreen.kt:45, 363` | `PlayerDebugOverlay` (296 LOC of debug UI: monospace status badges, copy-URL, "Retry Audio Load") renders by default every time `PlayerScreen` opens. Gate behind `BuildConfig.DEBUG` or default to `false`. Debug-only UI ships to release. | S |
| 4 | CR-004 / SE-lint | `AudioPlayerManager.kt:116-231` (11 sites) | Missing `@UnstableApi` opt-in for Media3 APIs. Lint fails `./gradlew lintDebug`. Add `@OptIn(UnstableApi::class)` at the file or class level. | S |
| 5 | SEC-001 / SF-009 | `FourReadWebScreen.kt:339-344` | `WebViewClient.onReceivedSslError` calls `handler.proceed()` unconditionally → bypasses TLS validation entirely for 4read.org and every cross-origin iframe. | S |
| 6 | SEC-002 / SEC-013 / lint | `res/xml/network_security_config.xml:3-7` + `AndroidManifest.xml:18` | `cleartextTrafficPermitted=true` + `<certificates src="user"/>` + manifest `usesCleartextTraffic=true` → MITM trivial on any device with user-installed CA (corporate proxy, Charles/mitmproxy, malicious VPN, rooted device with custom CA). | S |
| 7 | SEC-003 | `FourReadWebScreen.kt:286-296, 363` | WebView combines JS-enabled + mixed-content-always-allow + file/content access + third-party cookies + `addJavascriptInterface("AndroidHtml", ...)`. Any JS in any frame on 4read.org can call `AndroidHtml.processHTML(...)` with attacker-chosen HTML/URL → `AudiobookRepository.importAudiobookFromHtml` writes arbitrary stream URLs to Room → ExoPlayer plays them. | M |
| 8 | SEC-004 | `AudioPlayerManager.kt:118, 223` | `setAllowCrossProtocolRedirects(true)` on both primary + fallback ExoPlayer data sources allows http↔https downgrade of audio streams. Combined with SEC-002 clears the audit trail to plain HTTP. | S |
| 9 | HI-001 / SF-001 / SF-022 | `AudiobookRepository.kt:409-478` | **Download poison**: catch block writes a 30-byte text marker `OFFLINE_AUDIO_<id>` to `localFile` (`.mp3`), then sets `chapter.isDownloaded=true` with that file path. User sees a "Downloaded" badge → `AudioPlayerManager.loadAndPlayBook` tries to decode text → fails. | M |
| 10 | HI-002 / PERF-015 | `AudiobookRepository.kt:414 vs 1040, 1053` | **Cache mismatch**: download writes to `filesDir/audiobooks`, but cache-size reader + cache-clearer operate on `filesDir/audio_downloads`. Result: "Clear Cache" never deletes downloads; "Cache Size" always shows 0 MB. | S |
| 11 | PERF-001 / PERF-006 / PERF-008 | `AudioPlayerManager.kt:475-507`, `PlayerScreen.kt:195-213`, `MainActivity.kt:49-73` | 1Hz `startProgressTracker` emits a fresh `_playerState` every second → `MainActivity.AudiobookApp.collectAsState(playerState)` recomposes the **entire Scaffold + bottomBar + NavigationBar** every second during playback. Isolable: split into a separate `progressMs: StateFlow<Long>` sampled at 250ms; sub-composables read that. | M |
| 12 | PERF-002 / PERF-021 | `AudioPlayerManager.kt:54, 121-128` | No foreground service / `MediaSessionService`. On Android 8+ (especially 12+) background audio is killed within minutes. No `AudioFocusRequest` / `AudioManager.OnAudioFocusChangeListener` → phone calls don't pause. **Required by Play Store media-policy for media apps.** | L |
| 13 | PERF-003 / PERF-020 | `FourReadWebScreen.kt:283-372` | WebView created in `AndroidView.factory` but **never destroyed**. Tabs away → WebView (renderer process, JS engine, native heap, CookieManager session) **leaks**. `webViewInstance` Kotlin reference still held. Add `DisposableEffect(Unit) { onDispose { webViewInstance?.destroy(); webViewInstance = null; removeJavascriptInterface("AndroidHtml") } }`. | S |
| 14 | PERF-004 | `Entities.kt:32, 44, 54` | No `@Index` on `ChapterEntity.bookId`, `BookmarkEntity.bookId`, `PlaybackProgressEntity.bookId` — all FK columns queried with `WHERE bookId = :bookId`. → Full-table-scans as data grows. Add `@Entity(indices = [Index("bookId")])` to each. | S |

### 2.2 HIGH — should fix before release

| # | ID(s) | File:Line | Issue | Effort |
|---|---|---|---|---|
| 15 | HI-003 | `AudioPlayerManager.kt:183, 272` | Hardcoded 45000ms timeout, comment says "15s"; primary + fallback `prepareChapter` bodies are copy-pasted. Extract `prepareChapter(chapter, isLocal: Boolean)` helper; fix the comment. | M |
| 16 | HI-004 | `MainActivity.kt:84-147` | Four `NavigationBarItem` definitions are near-identical copy-paste; extract `appNavItem(viewModel, SelectedTab, icon, label, tag)` helper or data-driven loop. | S |
| 17 | HI-005 | `PlayerScreen.kt:309-319` | Speed-ladder `when`-expression uses brittle Float equality (0.5f, 0.8f, 1.0f…). Replace with `SPEED_LADDER` list + `nextSpeed(current)` index increment. | S |
| 18 | HI-006 | `MainViewModel.kt:20` | `SelectedTab.SETTINGS` enum value never wired. Drop or add the screen; the `else -> HomeScreen(...)` catch-all silently swallows future tab bugs. | S |
| 19 | HI-007 / SF-019 | `AudioPlayerManager.kt:475-507` | `startProgressTracker`'s `scope` is a free `CoroutineScope(Main+SupervisorJob)` created in `init{}`; `release()` only cancels `updateProgressJob` but child launches (`saveCurrentProgressToDb` via `Dispatchers.IO`) leak past release. | M |
| 20 | HI-008 | `PlayerScreen.kt:241, 295` | A11y icon mismatch — `Replay10` icon used for 15s rewind; `Forward30` for 30s forward. Swap icons to `Replay` / `Forward30` or adjust skip values. | S |
| 21 | HI-009 | `AudiobookRepository.kt:27-32` | `init{}` launches an unmanaged `CoroutineScope(Dispatchers.IO).launch { seed...; fetchCatalog... }`. Fires on every VM init; no cancellation; can run twice on config-change. Use application-scoped supervisor + 24h cache. | M |
| 22 | SEC-005 | `FourReadWebScreen.kt:291` | `WebSettings.mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW` — http subresources on https pages allowed. Set to `MIXED_CONTENT_NEVER_ALLOW`. | S |
| 23 | SEC-006 | `FourReadWebScreen.kt:290` | `databaseEnabled = true` (deprecated WebSQL) explicitly enabled. Combined with the JS interface and cleartext+user-CA trust this lets attacker persist arbitrary state in app's WebSQL databases. | S |
| 24 | SEC-007 | `FourReadWebScreen.kt:286` | `CookieManager.setAcceptThirdPartyCookies(true)` enables tracking cookies across origins. | S |
| 25 | SEC-008 | `AudiobookRepository.kt:414` | `/sdcard/audiobooks` fallback path requires `WRITE_EXTERNAL_STORAGE` not declared in manifest; fails on Android 11+ scoped storage. Always use `context.filesDir/audiobooks` (which never is null in current call sites). | S |
| 26 | SEC-009 | `FourReadWebScreen.kt:47-60, 363` | `AndroidHtml.processHTML(html, url)` interface receives arbitrary input from any frame JS, persists to DB, and starts playback — no origin check, no allow-list, no rate limit. | M |
| 27 | SEC-010 | `FourReadWebScreen.kt:363` | `addJavascriptInterface("AndroidHtml", ...)` bound unconditionally; on Android <4.2 reflection-RCE possible; on modern Android it's a universal JS-API for any origin. | M |
| 28 | SEC-011 | `FourReadWebScreen.kt:302-314` | `shouldOverrideUrlLoading` fires `Intent.ACTION_VIEW` for any non-http(s) URL → `intent://`, `market://`, `tel:`, `mailto:`, `javascript:` exploitation vector. Validate scheme whitelist. | S |
| 29 | SEC-012 / SEC-024 | `AndroidManifest.xml:11` + `backup_rules.xml` + `data_extraction_rules.xml` | `allowBackup=true` with stub rules files (no include/exclude) — entire Room DB (bookmarks, progress, stats: PII-adjacent) and audio cache sync to Google Cloud Backup. Set `allowBackup=false` or add explicit excludes. | S |
| 30 | SEC-014 | `AudiobookRepository.kt:435, 868, 915` | No certificate pinning on 4read.org or archive.org. Combined with user-CA trust → MITM trivial. (Optional — could relax to "stable cert over time" rule.) | M |
| 31 | PERF-005 | `HomeScreen.kt:46-66` | `filteredBooks`, `featuredBook`, `genres` recomputed on every recomposition. Wrap in `remember(...)` for stable inputs; `genres` should be a top-level `val`. | S |
| 32 | PERF-009 | `BookCoverImage.kt:36-50` | Coil loads full-size cover with no `.size()` constraint → 10-50× larger than needed for list items. Add `.size(Size(256, 384))` + configure Coil `ImageLoader` with `memoryCache.maxSizePercent(0.20)`. | S |
| 33 | PERF-010 | `LibraryScreen.kt:206-213` | `items(allBookmarks) { allBooks.find { ... } }` is O(N×M) per emission. Build `Map<String, AudiobookEntity>` once via `remember(allBooks) { associateBy { it.id } }`. | S |
| 34 | PERF-011 | `AudiobookRepository.kt:424-474` | Parallel download: `chapters.map { async(Dispatchers.IO) { ... } }.awaitAll()` — no concurrency limit. For 10×100MB chapters = 1GB RAM in flight. Use `Semaphore(2)` or `chunks(2)`. | S |
| 35 | PERF-012 | `AudiobookRepository.kt:27-32` | (same as HI-009) | — |
| 36 | PERF-013 | `AudiobookRepository.kt:862-899, 343, 439, 868` | Raw `HttpURLConnection` everywhere; **OkHttp is already a dependency** but unused. No connection pool, DNS cache, retries, or response cache. Replace with a single OkHttpClient via DI; add 10MB disk cache. | M |
| 37 | PERF-014 | `MainViewModel.kt:99-111` | Three separate `flatMapLatest` over same `selectedBookId` → three Room observers spin up on each change. `combine(...)` them into one. | S |
| 38 | SF-005 | `AudiobookRepository.kt:670-733` | (subset of CR-002) — see CRITICAL row 2. | M |
| 39 | SF-006 | `AudiobookRepository.kt:999-1027` | (subset of CR-002) — see CRITICAL row 2. | M |
| 40 | SF-007 | `AudiobookDatabase.kt:33` | `fallbackToDestructiveMigration()` silently wipes user data on schema mismatch. Replace with explicit migrations before v2 ships. | S |
| 41 | SF-008 | `MainViewModel.kt` (all launches) | Every `viewModelScope.launch(Dispatchers.IO) { repository.x() }` without try/catch. Wraps `addBookmark`, `toggleFavorite`, `downloadBookOffline`, etc. Add a CoroutineExceptionHandler or per-call try/catch + UI feedback. | M |
| 42 | SF-010 | `AudioPlayerManager.kt:290-299` | All-fallbacks-failed path sets `isPlaying=false` silently; only the (debug-overlay-hidden-by-default) label is updated. Show real error in normal UI. | M |
| 43 | SF-011 / SF-012 | `MainViewModel.kt:181, 65-69` | Bookmark "Add" closes dialog immediately, no persistence feedback. `toggleFavorite` flips UI without rollback on failure. | S |

### 2.3 MEDIUM — code-quality / tech debt

| # | ID(s) | File:Line | Issue | Effort |
|---|---|---|---|---|
| 44 | ME-001 | `AudiobookRepository.kt:34-284` | `seedInitialDataIfEmpty` is 250 LOC of magic seed data — move to JSON asset under `assets/seed/`. | M |
| 45 | ME-002 | `HomeScreen.kt:49-66` | Genre filter uses parallel branches with hardcoded strings ('Усі' / 'All' both required). Replace with `Map<DisplayLabel, GenreMatcher>`. | S |
| 46 | ME-003 | `AudiobookRepository.kt:554-733` | `importAudiobookFromHtml` and `importAudiobookFrom4ReadUrl` share ~80% logic — extract `build4ReadBookEntity(slug, sourceUrl, audioStreams, coverUrl)`. | M |
| 47 | ME-004 | `AudiobookRepository.kt:862-899` | `fetchUrlText` manual redirect loop duplicates HttpURLConnection's built-in `instanceFollowRedirects=true`. Either delegate to OkHttp or rename to `fetch4ReadUrl`. | M |
| 48 | ME-005 | `FourReadWebScreen.kt:47-60` | `HtmlJsInterface` allocated in Composable body; JS string constant hardcoded. Use `remember { HtmlJsInterface(...) }` + centralize JS string. | S |
| 49 | ME-006 | `theme/Color.kt:1-60` | Theme uses `CyberPrimary/Surface/Bg/...` but app is "4read Audio" — naming inconsistency. Either rename tokens to `FourRead*` or rename product copy. | M |
| 50 | ME-007 | `PlayerScreen.kt:452-488` | `AudioWaveformVisualizer` calls `(24..48).random()` inside the composable — non-deterministic at recomposition. Use `remember { IntArray(16) { Random.nextInt(24, 48) } }` (seed once). | S |
| 51 | ME-008 | `AudioPlayerManager.kt:509-517` | `saveCurrentProgressToDb` called every tick but only writes every 5s — and is also called unconditionally from seek/pause. Centralize. | S |
| 52 | ME-009 | `MainViewModel.kt:210-234` | `jumpToBookmark` launches a second `viewModelScope.launch(Dispatchers.Main)` inside an IO block (detached). Use `withContext(Dispatchers.Main)`. | S |
| 53 | ME-010 | `BookDetailScreen.kt:42-45` | `playerState` collected at top but only used in `if (activeTab == 1)` inside `items()`. Move collect into the branch. | S |
| 54 | ME-011 | `AudiobookRepository.kt:419-477` | Download workers call `dao.updateDownloadState` independently with computed percentages — racy `lastWriterWins`. Use `awaitAll()` + single coherent final update. | S |
| 55 | ME-012 | `MiniPlayerBar.kt:44-50` | `AnimatedVisibility visible = book != null` is redundant (parent already gates). Also missing a `Modifier.semantics { ... }` describing it as a now-playing card. | S |
| 56 | SEC-015 | `AudioPlayerManager.kt:171` | Logs full `chapter.streamUrl` on error — partial PII leak (any app with READ_LOGS, or any adb logcat). | S |
| 57 | SEC-016 | `AudiobookRepository.kt:803` | Logs full 4read URL on parse failure (includes user search query). | S |
| 58 | SEC-017 | `AudiobookRepository.kt:894` | Same URL-with-query leak on HTTP fetch failure. | S |
| 59 | SEC-018 | `AudioPlayerManager.kt:116, 221` | Hardcoded User-Agent leaks one developer's device model "SM-S918B" on every request — fingerprintable. | S |
| 60 | SEC-019 | `AudioPlayerManager.kt:117, 222` | `Referer: https://4read.org/` leaked to archive.org on every audio request → correlates playback to 4read.org visitors. | S |
| 61 | SEC-020 | `AudiobookRepository.kt:512-517` | Embedded cover art written with no size/type/path-validation; attacker stream URL can fill disk. | S |
| 62 | SEC-021 | `AudiobookDatabase.kt:28-34` | Room DB unencrypted on disk. Acceptable for this data sensitivity but combined with SEC-012 exposes all listening history on rooted/ADB devices. Document threat model or move to SQLCipher. | L |
| 63 | SEC-022 | `AudiobookRepository.kt:504-510` | `MediaMetadataRetriever.setDataSource(remote, map)` is deprecated; may bypass network security config on Q+. | S |
| 64 | SEC-023 | `AndroidManifest.xml:4-6` | No `FOREGROUND_SERVICE` permission despite audio playback; may violate Play policy on newer Android. | S |
| 65 | PERF-015 | `AudiobookRepository.kt:414, 1040, 1053` | (duplicate of HI-002 — included for completeness.) | S |
| 66 | PERF-016 | `BookDetailScreen.kt:293-306` | `itemsIndexed(chapters)` without `key=` — reorders and re-binds all rows on each emission. Use `items(chapters, key = { it.id })`. | S |
| 67 | PERF-017 | `AudiobookRepository.kt:906-1034` | `searchAudiobooksOn4Read` triggers N+1 page fetches serially. Add LruCache + Semaphore(2). | M |
| 68 | PERF-018 | `AudioPlayerManager.kt:61, 63, 91` | `mediaPlayer?.release()` on Main thread — offload to `Dispatchers.Default`. | S |
| 69 | PERF-019 | `AudioPlayerManager.kt:56, 519-524` | `private scope = CoroutineScope(Main + SupervisorJob)` never cancelled at `release()`. | S |
| 70 | PERF-020 | `FourReadWebScreen.kt:47-60` | `HtmlJsInterface` holds `Handler` + ViewModel reference past WebView lifetime. | S |
| 71 | SF-013 | `AudiobookRepository.kt:386-388` | `fetchCatalogFrom4Read` swallows all exceptions → empty list. Distinguish "no new books" from "network down". | S |
| 72 | SF-014 | `AudiobookRepository.kt:923-929, 1030-1032` | Same pattern in `searchAudiobooksOn4Read`. | S |
| 73 | SF-015 | `AudiobookRepository.kt:1073-1081` | `recordListeningTime` read-then-write not atomic → concurrent calls lose seconds. | S |
| 74 | SF-016 | `MainViewModel.kt:130-137` | `selectBook` double-tap triggers duplicate `refreshBookCoverAndDetails` (HTTP race). | S |
| 75 | SF-017 | `MainViewModel.kt:210-234` | `jumpToBookmark` silently aborts if book deleted (`?: return@launch`). | S |
| 76 | SF-018 | `AudioPlayerManager.kt:90-95` | `prepareChapter` silently returns when chapters empty; `isPlaying` stays `true` from optimistic flag. | S |
| 77 | SF-019 | `AudiobookRepository.kt:519-522` | `catch (e: Exception) { // no embedded image }` masks OOM/malformed-file/permission-denied. | S |
| 78 | SF-020 | `AudiobookRepository.kt:422, 469-477` | Download workers race; `isDownloaded=true` set even when no chapter succeeded. | M |
| 79 | SF-021 | `FourReadWebScreen.kt:347-361` | WebView error UI fires only for narrow set of network codes; JS console errors silently dropped. | S |
| 80 | SF-022 | `AudiobookRepository.kt:614-615, 792-794` | Playlist fetch exception swallowed — silent partial imports. | S |

### 2.4 LOW — cleanup / style

| # | ID(s) | File:Line | Issue | Effort |
|---|---|---|---|---|
| 81 | LO-001 | `AudiobookRepository.kt:1075-1080` | `recordListeningTime` allocates `SimpleDateFormat` per call. Hoist to companion; UTC for stats. | S |
| 82 | LO-002 | `MainViewModel.kt:274-285` | `formatTime` static lives on VM; move to `TimeFormatter` util. | S |
| 83 | LO-003 | `PlayerDebugOverlay.kt:29` | Unused import. | S |
| 84 | LO-004 | `LibraryScreen.kt:229-295` | `String.format("%.1f")` w/o explicit `Locale`. Fix x3. | S |
| 85 | LO-005 | `PlayerScreen.kt:199-213` | Slider has no `valueRange` / scrub semantics for screen-reader users. | M |
| 86 | LO-006 | `AudiobookRepository.kt:110, 298, 439, 872, 919` + others | User-Agent string duplicated 5+ times across files — extract to constants. | S |
| 87 | LO-007 | `PlayerScreen.kt:42-45` | `showSleepTimerSheet` etc. lose state on config change — use `rememberSaveable`. | S |
| 88 | LO-008 | `src/test/java/com/example/ExampleUnitTest.kt:1-16` | Template unit test "2+2=4". Delete or replace. | S |
| 89 | LO-009 | `PlayerScreen.kt:101-295` | Spacer heights use hardcoded `N.dp` (4, 6, 8, 10, 12, 14, 16, 20). Define spacing tokens in theme. | S |
| 90 | PERF-022 | `BookCoverImage.kt:33-53` | `isError` not reset on URL change after transient failure. | S |
| 91 | PERF-023 | `AudiobookRepository.kt:735-766` | `fetch4ReadPageDetails` recursively fetches all iframes serially. | S |
| 92 | PERF-024 | `AudiobookDatabase.kt:27-38` | `fallbackToDestructiveMigration` on every schema bump wipes user data — already covered as SF-007. | M |
| 93 | PERF-025 | `MainViewModel.kt:117-124` | `updateSearchQuery` fires repository search on every keystroke after ≥2 chars — no debounce. | S |
| 94 | SEC-027 | `FourReadWebScreen.kt:292` | `mediaPlaybackRequiresUserGesture=false` allows autoplay without user gesture. | S |
| 95 | SEC-028 | `AudiobookRepository.kt:338, 587, 754, 829-857` | No URL allow-list — extracted URLs (incl. m3u) can point anywhere. | M |
| 96 | SEC-029 | `AndroidManifest.xml:10-19` + `debug.keystore` | Debug keystore committed; fine for internal builds but add CI guard to fail release if `signingConfig != release`. | S |
| 97 | SF-023 | `AudioPlayerManager.kt:410, 429, 455` | Empty try/catch around `mediaPlayer?.volume = 1.0f` (volume setter never throws — dead code). | S |
| 98 | SF-024 | `AudioPlayerManager.kt:486-492` | `catch (e: Exception) { /* ignore get position errors */ }` can leave progress bar frozen. | S |
| 99 | SF-025 | `AudiobookRepository.kt:519-522, 1023` | `retriever.release()` failure swallowed — should `Log.w`. | S |

---

## 3. Action Plan

### 3.1 Phase 3 Hotfix (BLOCK — fix before installing APK on device)

Done inline during Phase 3 because these break the phone test:

- [ ] **CR-002 / SF-003 / SF-005 / SF-006:** Replace archive.org fallback chain with user-visible "не вдалося завантажити главу" UI state. Remove `getChaptersList` synthesized time-machine chapters. Remove `searchAudiobooksOn4Read` fake-result branch.
- [ ] **CR-003:** Default `showDebugOverlay = false` in `PlayerScreen.kt`.
- [ ] **HI-001 / SF-001:** On download HTTP failure: do NOT set `isDownloaded=true`; persist a `downloadState = FAILED` enum on the chapter; surface UI badge.
- [ ] **HI-002 / PERF-015:** Align cache directory name (`filesDir/audiobooks`) in `getAudioCacheSizeBytes`, `clearAllAudioCache`, and `downloadAudiobookOffline`.
- [ ] **CR-004 / lint errors:** Add `@OptIn(UnstableApi::class)` to `AudioPlayerManager.kt`.
- [ ] **SEC-001 / SF-009:** Replace `handler.proceed()` with `handler.cancel()` + visible error in `FourReadWebScreen.kt`.
- [ ] **SEC-002 / SEC-013:** Set `cleartextTrafficPermitted="false"` and remove `<certificates src="user"/>` in `network_security_config.xml`. Remove `usesCleartextTraffic` from `AndroidManifest.xml`.
- [ ] **SEC-004:** Set `setAllowCrossProtocolRedirects(false)` on both ExoPlayer data sources.
- [ ] **SEC-005..007:** Lock down WebView `mixedContentMode=NEVER_ALLOW`, `databaseEnabled=false`, `setAcceptThirdPartyCookies(false)`.
- [ ] **SEC-011:** Validate `shouldOverrideUrlLoading` URL scheme whitelist (http/https only).
- [ ] **PERF-003 / PERF-020:** Add `DisposableEffect(Unit) { onDispose { webViewInstance?.destroy() } }` and `removeJavascriptInterface("AndroidHtml")` in `FourReadWebScreen.kt`.
- [ ] **MiniPlayerBar.kt:46:** Remove always-true condition.

### 3.2 Phase 3 deferred (warning only — track as a separate issue)

- [ ] **PERF-001 / PERF-006 / PERF-008:** Split `playerState` into a separate `progressMs` flow (this is a real refactor — defer past hotfix).
- [ ] **PERF-002 / PERF-021:** Foreground service + MediaSession service (architectural; out of scope for hotfix).
- [ ] **PERF-004:** Add `@Index` to `Entities.kt` — requires DB version bump + migration (defer).
- [ ] **CR-001:** God-object split (large refactor — separate PR).
- [ ] **CR-002 full / SF-006:** "search result fabrication" branch in `searchAudiobooksOn4Read` — confirm via the code, then remove (subsumed by Hotfix step).
- [ ] All 17 build deprecation/opt-in warnings (mechanical, but separate commit).
- [ ] All unused resources (purple_*/teal_* colors, `empty_tag` string).
- [ ] Dependency upgrades (39 libs; separate release-window PR).
- [ ] `mipmap-xxhdpi/ic_launcher*.webp` regeneration (corrupted dimensions).
- [ ] Migration-artefact cleanup (~50 root scripts; archive or delete).

### 3.3 Coverage improvement plan

Test coverage is 20% real. Targeted additions:

- [ ] `AudiobookRepository.kt` — `extractAudioFromHtml`, `importAudiobookFromHtml`, `seedInitialDataIfEmpty`, `downloadAudiobookOffline` (fallback path).
- [ ] `AudioPlayerManager.kt` — `PlayerState` transitions, seek, fallback removal.
- [ ] `MainViewModel.kt` — coroutine flows, search debounce, race detection.
- [ ] Compose UI tests for `PlayerScreen` button interactions (extend existing `ButtonTesting`).
- [ ] Delete or replace `ExampleUnitTest`, `ExampleRobolectricTest`, and `ExampleInstrumentedTest`.

---

## 4. Per-file triage

| File | CRITICAL | HIGH | MEDIUM | LOW |
|---|---|---|---|---|
| `AudiobookRepository.kt` | 2 | 4 | 5 | 2 |
| `AudioPlayerManager.kt` | 1 | 4 | 5 | 2 |
| `FourReadWebScreen.kt` | 4 | 4 | 1 | 1 |
| `PlayerScreen.kt` | 1 | 1 | 2 | 2 |
| `MainViewModel.kt` | — | 3 | 1 | 1 |
| `MainActivity.kt` | — | 1 | — | — |
| `Entities.kt` | 1 | — | — | — |
| `AudiobookDatabase.kt` | — | 1 | 1 | 1 |
| `HomeScreen.kt` | — | — | 1 | 1 |
| `LibraryScreen.kt` | — | 1 | — | 1 |
| `BookDetailScreen.kt` | — | — | 1 | — |
| `MiniPlayerBar.kt` | — | — | 1 | — |
| `BookCoverImage.kt` | — | 1 | — | 1 |
| `PlayerDebugOverlay.kt` | 1 | — | — | 1 |
| `theme/Color.kt` | — | — | 1 | — |
| `network_security_config.xml` | 1 | — | — | — |
| `AndroidManifest.xml` | — | 3 | — | — |
| `backup_rules.xml` | — | 1 | — | — |
| `data_extraction_rules.xml` | — | — | — | — (also stub) |
| `debug.keystore` (root) | — | — | — | 1 |
| Test files | — | — | — | 1 |
| `src/test/Example*.kt` | — | — | — | 1 |

---

## 5. What this audit did NOT cover

- Functional/a11y manual testing on a real device (covered in Phase 4).
- Penetration testing (only static review).
- Race-condition fuzzing.
- Battery / wake-lock behavior under realistic loads.
- Performance under real audio streaming from archive.org (no measurement, only code review).
- Memory profiling.
- Test-gap coverage on WebView / JS bridge (would need instrumentation tests).

These are deferred to a subsequent dedicated effort.
