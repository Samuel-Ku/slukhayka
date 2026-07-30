# WebView Bridge Module

<!-- Generated: 2026-07-30 | Files scanned: 4 | Token estimate: ~600 -->

## Purpose

Loads 4read.org pages in an Android WebView, lets users paste URLs, imports audiobooks from HTML by parsing the page server-side and feeding results into the regular playback pipeline. The "4read Web" tab is the user-facing entry point.

## Entry Points

- Bottom nav → `SelectedTab.FOUR_READ_WEB` → `FourReadWebScreen`
- `MainViewModel.importAndPlay4ReadUrl(urlOrSlug)` (called from URL submit handler)
- `MainViewModel.importAndPlay4ReadHtml(url, html)` (called when WebView content is intercepted)

## Key Files

```
app/src/main/java/com/example/ui/screens/FourReadWebScreen.kt   452 lines
playerjs6.js                                                    292 KB  (bundled 3rd-party player)
p.js                                                            690 bytes
evaluate_playerjs.js                                           1044 bytes
```

## Architecture

```
[FourReadWebScreen]
  ├─ WebView (AndroidView wrapper)
  │    ├─ loadUrl("https://4read.org/...") with JS enabled
  │    ├─ addJavascriptInterface("Android", WebAppInterface)
  │    └─ shouldOverrideUrlLoading → intercept .html / audio URLs
  └─ URL input field → "Import" button
       └─ MainViewModel.importAndPlay4ReadUrl(url)
            └─ AudiobookRepository.importAudiobookFrom4ReadUrl(url)
                 ├─ fetch4ReadPageDetails → cover URL + audio URLs (regex)
                 ├─ insert book + chapters to Room
                 └─ playAudiobook(importedBook, chapterIndex=0, autoPlay=true)
```

## Public Surface

```kotlin
// In MainViewModel
fun importAndPlay4ReadHtml(url: String, html: String)
fun importAndPlay4ReadUrl(urlOrSlug: String)
```

```kotlin
// In AudiobookRepository
suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity
suspend fun importAudiobookFrom4ReadUrl(urlOrSlug: String): AudiobookEntity
```

## WebView Configuration (FourReadWebScreen)

- `settings.javaScriptEnabled = true` (required for 4read pages)
- `settings.domStorageEnabled = true`
- `settings.databaseEnabled = true` ⚠️ deprecated in current Android (Phase 2 finding)
- Custom `WebViewClient.shouldOverrideUrlLoading` to route audio URLs to player
- Custom `WebChromeClient` for progress / console

## playerjs6.js

- 292 KB bundled JavaScript player framework (3rd-party)
- Vendored in `app/src/main/assets/` (verify path)
- Used by 4read.org pages to embed playlists
- `evaluate_playerjs.js` and `p.js` are dev/test helpers, not shipped in release

## Dependencies

- **Inbound:** `MainActivity` (renders when `selectedTab == FOUR_READ_WEB`)
- **Outbound:** `AudiobookRepository` (import → play), Android `WebView` + `WebSettings`
- **External:** `https://4read.org/` (page fetch + audio extraction)

## Common Tasks

| Task | Touch |
|---|---|
| Change URL import flow | `FourReadWebScreen.kt` (URL input + button) |
| Add new import source | new method in `AudiobookRepository` |
| Update HTML parsing | `AudiobookRepository.extractAudioFromHtml` (regex-based, fragile) |
| Change WebView config | `FourReadWebScreen.kt` AndroidView block |

## Known Issues (Phase 2 candidates)

- `FourReadWebScreen.kt:122,133,209,420` — deprecated `Icons.Filled.ArrowBack/ArrowForward/OpenInNew` (use AutoMirrored)
- `FourReadWebScreen.kt:290` — `WebSettings.databaseEnabled` deprecated
- HTML parsing is regex-based — fragile, can break silently when 4read changes their markup
- No cleartext traffic policy check needed if 4read serves HTTPS only
- `playerjs6.js` is 292 KB bundled — consider lazy-load or exclude from debug builds
- JavaScript interface exposes `Android` namespace — review what methods it exposes (security audit)
