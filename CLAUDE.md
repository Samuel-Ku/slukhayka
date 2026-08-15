# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

«Слухайка» (Slukhayka) — a personal Android audiobook library and player, rebranded from "4Read". Single-module Kotlin + Jetpack Compose app. Package namespace is still `com.example`; `applicationId` is `com.slukhayka.app`.

## Domain model (read first)

The ubiquitous language lives in `CONTEXT.md`: **Work**, **Edition**, **Source**, **Source Binding**, **Source Catalog**, **Chapter**, **Series**, **Library Entry**, **Listening State**, **Metadata Assertion**, **Metadata Override**, **Smart Rewind**, **Tombstone**. Use those terms and the explicit "Avoid" lists (e.g. do not call a Work a "book"). `docs/adr/` holds accepted architectural decisions; ADR-0001 (separate Work/Edition/Source/listener state) is the identity backbone.

If your output contradicts an ADR, surface the conflict explicitly rather than silently overriding.

## Architecture

- Single `:app` module, single `MainActivity`, Compose UI. Navigation is **state-driven**, not `Navigation-Compose` routes: `MainActivity` renders `AudiobookApp`, which switches on `MainViewModel` state and a 3-tab bottom bar (Слухати / Огляд / Медіатека) plus overlay destinations and a full-screen player.
- `App.kt` is the `Application` subclass and manual DI root. It holds app-scoped `AudiobookRepository` and `AudioPlayerManager` so playback survives activity recreation.
- `ui/MainViewModel.kt` is the single activity-scoped `AndroidViewModel`. It composes flows, owns the hand-rolled navigation/overlay state, and exposes command functions for the screens.
- `player/AudioPlayerManager.kt` wraps Media3 `ExoPlayer` and exposes a `StateFlow<PlayerState>`. `player/PlaybackService.kt` is the Media3 `MediaSessionService` that exposes the player to the system notification and the Glance widget.
- `widget/` is a Glance app widget that talks **only** to the MediaSession (`SessionAccess.kt`), never to the ViewModel or repository directly.

### Data layer

- Room database `read4_audiobook_database` (currently v11) in `data/db/`. `Entities.kt`, `AudiobookDatabase.kt`, `AudiobookDao.kt`. Schemas are exported to `app/schemas/`.
- `data/repository/AudiobookRepository.kt` is currently one large repository. ADR-0002 (accepted) calls for splitting it into five modules — `SourceCatalog`, `LibraryImport`, `OfflineDownloads`, `ListeningStateStore`, `LibraryEntries` — but that split is **not yet implemented**; the single repository still exists.
- Source seam lives in `data/source/SourceAdapter.kt` with concrete adapters (`FourReadAdapter`, `SluhayAdapter`, `SluhayuaAdapter`, `AudiobookMp3Adapter`, `LihtarAdapter`, `SoundBooksAdapter`). All HTTP goes through `HttpFetcher`; `DownloadPolicy` is the pure home for per-source URL/header/stream-only rules; WebView-captured-HTML import rides this same seam.
- `data/imports/` is the SAF folder import/rescan pipeline (`LocalFolderScanner`, `FolderRescan`, `ImportGrantStore`, `Hashing`).

### ADRs that are accepted but not yet in code

ADR-0002 (repository split), ADR-0007 (Editions own chapters, Sources own tracks), and ADR-0008 (thin `MainViewModel`) describe the intended target state. Verify against current code before assuming they are implemented.

## Build and test

Prerequisites: JDK 21, Android SDK API 36 + build-tools 36.0.0. Gradle wrapper is pinned to 9.3.1.

```bash
# Build
./gradlew :app:assembleDebug

# All JVM unit tests (JUnit4 + Robolectric + Roborazzi snapshots)
./gradlew :app:testDebugUnitTest

# One test class / test method
./gradlew :app:testDebugUnitTest --tests "com.example.player.SmartRewindTest"
./gradlew :app:testDebugUnitTest --tests "com.example.player.SmartRewindTest.someTestName"

# Coverage reports
./gradlew :app:koverXmlReport :app:koverHtmlReport

# Coverage gate (CI uses the relaxed baseline below; build default is 80/70)
./gradlew :app:koverVerify -Pkover.instructionThreshold=15 -Pkover.branchThreshold=9

# Instrumented Espresso tests (requires an emulator)
./gradlew :app:connectedAndroidTest
```

Notes:

- JVM tests run with `isIncludeAndroidResources = true` (Robolectric). Room repository tests use in-memory Room; adapters/parsers/policies are fixture-pinned JVM tests.
- Roborazzi snapshot tests write PNGs under `app/src/test/snapshots/`.
- The Secrets Gradle plugin reads `.env` / `.env.example`. `google-services.json` is optional (`WARN`/passthrough).
- Release signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` from the environment (fallback keystore `${rootDir}/my-upload-key.jks`).

## Agent-specific instructions

- Issues live as GitHub issues in `Samuel-Ku/slukhayka`; use the `gh` CLI. See `docs/agents/issue-tracker.md` for exact commands and the `/wayfinder` map/sub-issue conventions. External PRs are not a triage surface.
- Triage roles map to the label strings in `docs/agents/triage-labels.md`.
- Primary domain reference is `CONTEXT.md` + `docs/adr/` (see `docs/agents/domain.md`).
- `docs/CODEMAPS/` is stale (generated 2026-07-30, reports ~23 Kotlin files; the repo now has ~79 in `app/src/main`). Treat it as directional only.
