# [Spec] Ukrainian Netflix-style Catalog & Library — 2026-08-06

> **Status:** Fully implemented. Blocks 1–3 landed in commit `afd6db3` (tickets T1–T8, GitHub issues #9–#16, closed); Block 4 (local folder import) is implemented in the working tree (commit pending). One commit per block, single branch `main`.
> **Source:** Synthesized from grilling session on 2026-08-06 (10 resolved decisions).
> **Tracker:** GitHub issue — this document is the canonical spec.

## Problem Statement

The maintainer wants the app to be "an aradia-like audiobook player, but with Ukrainian books". Today the app falls short in three ways:

1. **The catalog is mostly fake.** Explore shows 9 hardcoded Russian-language seed books («Нейромант»/«Уильям Гибсон», «1984»/«Джордж Оруэлл», «Дюна», «Солярис», «Кибер Дистопия 2077», …) whose `archive.org` stream URLs do not play on the maintainer's network, plus duplicated cover art. These books cannot be deleted.
2. **The real Ukrainian catalog is hidden behind a WebView browser tab.** 4read.org (a Ukrainian audiobook site, `lang="uk"`, «Аудіокниги українською онлайн») has a large catalog — series («Відьма на вимогу», «Відьми», «Епоха божевілля», «Максим Темний», «Сага про Дріззта», «Цей химерний світ», «Чаклунський світ») and individual books (Гаррі Поттер, Знахар, …). The only way to browse it is the 4read Web tab (a WebView). The maintainer's goal is a native, Netflix-like experience without the browser.
3. **There is no way to import local audio files**, so the maintainer cannot add Ukrainian audiobooks they already own as mp3s.

The maintainer will keep maintaining this app alone, and wants a clean, native, Ukrainian-first experience they can grow.

## Solution

A three-iteration program that turns the app into a native Ukrainian audiobook player:

- **Block 1 — Cleanup (this spec's immediate scope):** remove the fake seed books from auto-start, add cascade book deletion (with confirmation dialog), stop playback when a playing book is deleted, and collapse navigation from four tabs to two (Explore · Library; Bookmarks becomes a Library sub-tab). No WebView tab.
- **Block 2 — Native catalog:** parse the 4read.org main page into Netflix-style rows (series + latest), rework Explore into those rows, add loading spinner → catalog-or-empty-state-with-CTA flow, add SAF single-file import, keep a hidden "open on site" WebView fallback on the book detail page.
- **Block 3 — Genre pages:** tapping a series row opens a full-screen list of that series' books (parses the series page). Same parser architecture, different source page.
- **Block 4 — Local folder import (implemented):** recursive SAF tree picker (`OpenDocumentTree`) that scans the picked folder for audio files and turns them into local books — one book per sub-folder, one book per loose root file (see Implementation Decisions).

Iteration split chosen by the maintainer: Block 1 → Block 2+3 → Block 4; all blocks are now implemented.

## User Stories

1. As a listener, I want the Explore tab to show only real, playable Ukrainian books, so that I am never misled by fake catalog entries.
2. As a listener, I want to be able to delete a book from my library, so that my library reflects only what I actually care about.
3. As a listener, I want a confirmation dialog before a book is deleted, so that I don't lose a book by accident.
4. As a listener, I want deleting a book to also remove its chapters, bookmarks, playback progress, and downloaded audio files, so that no orphaned data or disk waste is left behind.
5. As a listener, I want deleting the book that is currently playing to stop playback and clear the player, so that the app never shows a ghost of a deleted book.
6. As a listener, I want the app to start with an empty catalog instead of fake books, so that everything I see comes from a real source.
7. As a listener, I want to see a loading indicator while the catalog is fetched, so that I understand the app is working.
8. As a listener, I want to see a friendly empty state with a search CTA and an import CTA when the catalog fetch returns nothing, so that I always have a next action.
9. As a listener, I want the 4read Web browser tab removed, so that the app feels native rather than a browser wrapper.
10. As a listener, I want a two-tab navigation (Explore · Library) with bookmarks inside Library, so that navigation is simple and Netflix-like.
11. As a listener, I want to import a local audio file (mp3/m4a/ogg) via the system file picker, so that I can listen to Ukrainian audiobooks I already own offline.
12. As a listener, I want an imported local file to appear as a book titled after its file name, so that it is findable in my library.
13. As a listener, I want to see my recently-played books in a "Continue listening" area, so that I can resume where I left off.
14. As a listener, I want Explore to show rows of book covers grouped by 4read series, so that I can browse like Netflix.
15. As a listener, I want to open a series row and see all books of that series, so that I can discover complete cycles.
16. As a listener, I want the book detail page to offer an "open on site" fallback, so that if native parsing misses something I can still reach the source.
17. As a maintainer, I want each iteration to end with a green build + green unit tests + a device check, so that regressions are caught early.
18. As a listener, I want to import a whole folder of local audiobooks at once, so that I can add many mp3/m4a/ogg files I already own without picking them one by one.

## Implementation Decisions

### Block 1 — Cleanup

- **Remove seed books from auto-start:** `seedInitialDataIfEmpty()` no longer inserts the 9 hardcoded Russian-language books/chapters/bookmarks/progress. First run starts with an empty catalog; the catalog fills from `fetchCatalogFrom4Read()` (network) and user imports. Existing databases keep their rows (no destructive migration) — seed removal only affects fresh installs / empty DBs. `fetchCatalogFrom4Read()` keeps running on init as today.
- **Cascade delete:** new DAO queries `DELETE FROM chapters WHERE bookId = :bookId`, `DELETE FROM bookmarks WHERE bookId = :bookId`, `DELETE FROM playback_progress WHERE bookId = :bookId`, plus `DELETE FROM audiobooks WHERE id = :bookId`. Repository gains `suspend fun deleteBook(bookId)` that: deletes downloaded audio files for the book's chapters (files under the offline-audio dir), then runs the four deletes (order: chapters → bookmarks → progress → book). No schema version bump needed (queries only).
- **Delete confirmation dialog:** Compose `AlertDialog` on the book detail screen (and long-press on Library cards): «Видалити книгу? Це видалить закладки та завантажені файли» with Підтвердити/Скасувати.
- **Stop playback on delete:** `MainViewModel.deleteBook(bookId)` calls `playerManager` to stop and clear state when the deleted book is the currently-playing one (reset `PlayerState` to default, release nothing — the shared player is reused), then calls `repository.deleteBook(bookId)` and navigates back to the catalog.
- **Two-tab navigation:** bottom bar becomes Explore · Library. `SelectedTab.FOUR_READ_WEB` is removed; `SelectedTab.BOOKMARKS` is removed as a top-level tab (bookmarks already exist as a Library sub-tab). `FourReadWebScreen` is no longer a tab destination.
- **Hidden WebView fallback:** a non-tab entry point on the book detail screen (e.g. top-bar icon or menu item «Відкрити на сайті») launches the existing `FourReadWebScreen`/WebView in a pushed destination for the book's `sourceUrl`.

### Block 2 — Native catalog (next iteration, summarized)

- **Section parsing:** parse `https://4read.org/` (already fetched by `fetchCatalogFrom4Read`) into sections: series rows (from `/xfsearch/cikl/...` links with decoded Ukrainian titles) + a «Новинки» row (latest book detail links). Reuse the existing HTML-fetch + regex utilities in the repository; introduce a small data class `CatalogSection(title, books)` returned alongside `fetchCatalogFrom4Read`.
- **Explore rework:** Netflix-style rows of `BookCoverImage` cards; «Continue listening» from `recentProgress`; search stays at top (existing `searchAudiobooksOn4Read`).
- **Loading / empty states:** catalog loading flag on the repository/ViewModel; spinner while fetching; empty state with «Шукати» + «Імпортувати файл» CTAs.
- **SAF import:** Activity-Result `OpenDocument` (audio/*) → copy picked file into `filesDir/local_imports` → insert one `AudiobookEntity` (title = file name without extension, genre «Локальні», `isDownloaded=true`) + one `ChapterEntity` pointing at the copied file.

### Block 3 — Genre pages (later iteration, summarized)

- Tap a series row → parse `/xfsearch/cikl/<slug>/` → list of books in that series, rendered as a full-screen list. Same `fetch4ReadPageDetails`-style parsing; new repository method `fetchSeriesBooks(slug)`.

### Block 4 — Local folder import (implemented)

- **SAF tree picker:** `LibraryScreen` gains a «Папку» button (`LocalFolderImportButton`, outlined, next to «Додати аудіо») launching `ActivityResultContracts.OpenDocumentTree()`. No permission needed — SAF grants temporary read access to the picked tree; files are copied into private storage immediately.
- **Recursive scan:** new `LocalFolderScanner` (`app/src/main/java/com/example/data/imports/LocalFolderScanner.kt`) walks the tree depth-first via `androidx.documentfile:documentfile:1.0.1` (`DocumentFile.fromTreeUri`) and collects audio files by extension: mp3, m4a, ogg (+ m4b, aac). Each discovered file becomes a `LocalAudioEntry(fileName, parentFolder, openStream)` whose stream is opened lazily, so the grouping core never touches a ContentResolver.
- **Grouping core:** `AudiobookRepository.importAudioEntries(entries)` — pure Kotlin over the DAO. Rule: files at the root of the picked tree become one single-chapter book each (same as the single-file import); every sub-folder becomes one multi-chapter book whose chapters are its audio files sorted *naturally* by file name (`track1` → `track2` → … → `track10`). The grouping key is the **full relative path** (`SeriesA/Кобзар`), so two same-named folders in different branches never merge; the book title is the last path segment.
- **File handling:** each file is copied into `filesDir/local_imports` **preserving its original extension** (so ExoPlayer detects the container — no silent `.mp3` renaming), with a counter-based unique suffix. Books are `genre = «Локальні»`, `isDownloaded = true`, one chapter per file pointing at the copied path. Unreadable files are skipped without failing the import; the outcome (`LocalImportResult(booksImported, filesImported, skippedFiles)`) is surfaced via a Snackbar in Library («Імпортовано N книг (M файлів) · K пропущено»).
- **Shared code:** the single-file import (`importLocalAudioStream`, T7) was refactored onto the same helpers (`sanitizeLocalBaseName`, `copyLocalAudioStream`, `insertLocalBook`), so both flows behave identically.

### Cross-cutting

- **Language:** UI copy is Ukrainian (existing convention). Seed/catalog metadata may stay as-sourced from 4read (Ukrainian).
- **No schema migration** in Block 1 (queries only). SAF import needs no schema change (reuses existing `localFilePath`/`isDownloaded` columns).
- **ADR check:** no existing ADR is contradicted (ADR-001/002 concern snapshots and emulator audio).

## Testing Decisions

- **Good test = external behavior, not implementation.** Tests assert on repository state and player state transitions (what the user observes), not on internal call counts.
- **In-memory Room** (`Room.inMemoryDatabaseBuilder`) is the seam for catalog/cascade tests — chosen by the maintainer over the existing `FakeAudiobookDao`, because cascade deletes are relational (chapters/bookmarks/progress per book) and should be exercised against real SQL. The fake DAO remains for player tests where only the persistence boundary is needed.
- **Player stop-on-delete** is tested through `AudioPlayerManager` with the existing `FakePlayerEngine` (JVM, no ExoPlayer): load book → `playerState` shows book → delete → `playerState` is default and `isPlaying=false`.
- **Navigation (two tabs)** is covered by the existing Robolectric setup (`ButtonTesting` style) and/or Compose snapshot tests (prior art: `LibraryComponentsSnapshotTest`), asserting the WebView tab is gone and Library shows bookmarks sub-tab.
- **Parsing** (Block 2) is tested JVM-only against a saved HTML fixture (prior art: `AudioParsingTest`): fixture → sections/books extracted.
- **No network in tests.** Catalog-fetch tests use fixtures or empty responses; import tests copy a local file.
- **Folder import (Block 4) is tested at two levels.** The grouping/insertion core (`importAudioEntries`) is exercised against in-memory Room in `AudiobookRepositoryRoomTest` (grouping by relative path, natural chapter order, skipped unreadable files, extension preservation, same-named folders staying distinct). The recursive walk itself is covered by `LocalFolderScannerTest`, which drives `LocalFolderScanner.scan(root, resolver)` with an in-memory `FakeDocumentFile` tree (the fake lives in package `androidx.documentfile.provider` because `DocumentFile`'s constructor is package-private); streams are never opened during a scan.

## Out of Scope

- YouTube import (aradia feature) — not planned.
- Chromecast — not planned.
- Cloud/backend catalog — out; 4read.org HTML remains the source.
- Schema version bump / data migration for existing fake books — existing databases keep rows; only fresh installs start empty. (A future cleanup migration is possible but out of scope.)
- Splitting the 1000+ line `AudiobookRepository` (separate tracked effort per wayfinder MAP).

## Further Notes

- 4read.org verified Ukrainian (`lang="uk"`, «Аудіокниги українською онлайн»); LibriVox has 0 Ukrainian items, so archive.org is not a catalog source for this app.
- Network reality (verified on device): archive.org unreachable from the maintainer's home network; `reasd.org` (4read CDN) works. Seed-book archive.org URLs were effectively unplayable — another reason to remove them.
- Each iteration ends with: build (`./gradlew assembleDebug testDebugUnitTest`), phone check on OnePlus 8 Pro (wireless ADB), and a commit.
