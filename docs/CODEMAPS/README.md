# Codemaps Index

<!-- Generated: 2026-07-30 | Modules: 8 | Token estimate: ~400 -->

Token-lean architecture documentation for the `4read-audiobooks-player` Android app.
Each file covers one bounded area of the codebase. Read them in this order for a complete mental model.

## Modules

| # | File | What | Lines |
|---|---|---|---|
| 1 | [app-entry.md](app-entry.md) | MainActivity + MainViewModel + AndroidManifest; the wiring layer | ~60 |
| 2 | [ui-system.md](ui-system.md) | Color.kt, Theme.kt, Type.kt, BookmarkDialog, BookCoverImage | ~320 |
| 3 | [audio-player.md](audio-player.md) | AudioPlayerManager (ExoPlayer), PlayerScreen, MiniPlayerBar, PlayerDebugOverlay, SleepTimerSheet | ~1590 |
| 4 | [book-library.md](book-library.md) | AudiobookRepository, Database/Dao/Entities, Home/Library/BookDetail screens | ~3380 |
| 5 | [webview-bridge.md](webview-bridge.md) | FourReadWebScreen, WebView config, HTML import pipeline, vendored playerjs6.js | ~452 + 3 JS files |
| 6 | [build-config.md](build-config.md) | Gradle scripts, version catalog, manifest, ProGuard, security/persistence XML | ~200 |
| 7 | [tests.md](tests.md) | Unit + instrumented tests, Robolectric, Roborazzi setup | ~260 |
| 8 | [migration-artefacts.md](migration-artefacts.md) | Root-level scripts and JS files left over from the ExoPlayer migration | ~50 files |

## Total Source Size

- **Kotlin source:** ~5,700 lines across 23 files in `app/src/main/`
- **DB:** 5 Room entities
- **Vendored JS:** ~293 KB (`playerjs6.js` 292 KB + 2 small helpers)

## How to Read

Start with **`app-entry.md`** for the top-down mental model.
Then read **`book-library.md`** (largest module, all screens + data layer depend on it).
Then read **`audio-player.md`** (orthogonal — same Repository dependency, but separate UI surface).
**`webview-bridge.md`** is its own subsystem, only relevant if you need to debug 4read HTML import.
**`ui-system.md`** is pure design tokens + 2 small components, read for theming work.
**`build-config.md`** is for build/toolchain questions.
**`tests.md`** is the audit target for Phase 2 (currently almost zero coverage).
**`migration-artefacts.md`** is the cleanup-candidate list.

## Freshness

- Generated: 2026-07-30
- Source commit scanned: `ef90563 feat(audio): migrate to ExoPlayer and add HTML import` (current `main`)
- Next refresh: after Phase 2 audit changes land, regenerate from new HEAD

## Tools & Targets

- Build: Gradle 9.3.1 + AGP 9.1.1
- Min/target SDK: 24 / 36
- Min JVM (build): JDK 21
