# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — **does not exist yet**; proceed silently. The `/domain-modeling` skill creates it lazily when terms get resolved.
- **`docs/wayfinder/`** — contains ADR-001 (Compose snapshot infra) and ADR-002 (emulator audio scenario), plus the coverage map mirror.
- **`docs/CODEMAPS/`** — per-module codemaps (audio-player, book-library, ui-system, webview-bridge, app-entry, build-config, tests, migration-artefacts). These are the de-facto module reference until CONTEXT.md exists.

## File structure

Single-context repo: one Android app module (`app/`), no monorepo split.

## Glossary (current vocabulary, from codemaps + code)

- **Книга (book)** — `AudiobookEntity`: catalog metadata (id, title, author, narrator, cover, genre, sourceUrl, download state, rating).
- **Глава (chapter)** — `ChapterEntity`: per-book chapter with `streamUrl`, duration, download state.
- **Закладка (bookmark)** — `BookmarkEntity`: user bookmark at (chapterIndex, timestampSeconds).
- **Прогрес (progress)** — `PlaybackProgressEntity`: last listened position per book.
- **Статистика (listening stats)** — `ListeningStatEntity`: daily listened-seconds rollup.
- **Каталог (catalog)** — the 4read.org book list fetched via HTML parsing (`fetchCatalogFrom4Read`, `searchAudiobooksOn4Read`).
- **Офлайн (offline download)** — per-chapter audio files under `filesDir/audiobooks`.
- **Плеєр (player manager)** — `AudioPlayerManager`, app-scoped, wraps one long-lived ExoPlayer + MediaSession.
- **Бібліотека (library)** — the personal shelf screen (downloaded / favorites / bookmarks / stats sub-tabs).

## Flag ADR conflicts

If your output contradicts an existing ADR (in `docs/wayfinder/`), surface it explicitly rather than silently overriding.
