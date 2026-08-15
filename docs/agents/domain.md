# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the domain's ubiquitous language (Work, Edition, Source, Chapter, Series, Metadata Assertion/Override, Library Entry, Listening State, Tombstone) with explicit _Avoid_ terms. The primary domain reference.
- **`docs/adr/`** — past architectural decisions (ADR-0001: separate Work / Edition / Source / listener state, the repo's identity backbone).
- **`docs/CODEMAPS/`** — per-module codemaps (audio-player, book-library, ui-system, webview-bridge, app-entry, build-config, tests, migration-artefacts). Module-level reference for where code lives.

## File structure

Single-context repo: one Android app module (`app/`), no monorepo split. One `CONTEXT.md` + `docs/adr/` at the repo root.

## Glossary (current vocabulary, from CONTEXT.md + code)

- **Work** — `AudiobookEntity` family's abstract identity: the authored book, independent of narration/source. Owns bibliographic identity and series membership, not progress.
- **Edition** — one rendition of a Work (language, narrator, chapter topology); `EditionEntity`.
- **Source** — a provenance-bearing origin/copy through which an Edition is played (local folder, M4B, 4read stream, downloaded copy); `SourceEntity`, `sourceId` like `4read`.
- **Source Binding** — a device's locator/permission/availability for a Source; `SourceBindingEntity`.
- **Chapter** — an ordered logical subdivision of an Edition; `ChapterEntity` (streamUrl, duration, download state).
- **Series** — a named bibliographic sequence; `SeriesEntity`.
- **Listening State** — listener progress/bookmarks/completion for one Edition; `PlaybackProgressEntity`, `BookmarkEntity`, `ListeningStatEntity`.
- **Tombstone** — durable removal record preventing silent re-imports.
- **Каталог (catalog)** — multi-source aggregation via the `SourceAdapter` seam (`SourceBook`, `SourceBookDetail`) — one parser per source, repository persists.

## Flag ADR conflicts

If your output contradicts an existing ADR (in `docs/adr/`), surface it explicitly rather than silently overriding.
