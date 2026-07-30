# Book Library Module

<!-- Generated: 2026-07-30 | Files scanned: 7 | Token estimate: ~900 -->

## Purpose

Manages the audiobook catalog: Room DB schema, repository with online (4read.org) and offline sources, three library screens (Home, Library, BookDetail), chapter lookup, download/cache, and search.

## Entry Points

- `AudiobookRepository(db.audiobookDao(), application)` (constructed by `MainViewModel`)
- `MainViewModel` exposes `allBooks`, `downloadedBooks`, `favoriteBooks` as StateFlows

## Key Files

```
app/src/main/java/com/example/data/repository/AudiobookRepository.kt  1087 lines  (heavy)
app/src/main/java/com/example/ui/screens/HomeScreen.kt                609 lines
app/src/main/java/com/example/ui/screens/LibraryScreen.kt             500 lines
app/src/main/java/com/example/ui/screens/BookDetailScreen.kt          490 lines
app/src/main/java/com/example/data/db/AudiobookDao.kt                 93 lines
app/src/main/java/com/example/data/db/Entities.kt                     61 lines
app/src/main/java/com/example/data/db/AudiobookDatabase.kt            40 lines
```

## Database Schema (5 tables)

| Table | Entity | Purpose |
|---|---|---|
| `audiobooks` | `AudiobookEntity` | Catalog metadata |
| `chapters` | `ChapterEntity` | Per-book chapters with stream URLs |
| `bookmarks` | `BookmarkEntity` | User bookmarks (auto-generated id) |
| `playback_progress` | `PlaybackProgressEntity` | Last position per book |
| `listening_stats` | `ListeningStatEntity` | Daily listened-seconds rollup |

## Data Flow

```
[App start]
  └─ AudiobookRepository.init { seedInitialDataIfEmpty(); fetchCatalogFrom4Read() }
       ├─ seedInitialDataIfEmpty() → 9 hardcoded books + chapters + bookmarks (only if DB empty or has placeholder URLs)
       └─ fetchCatalogFrom4Read()  → HTTP fetch + regex parse of https://4read.org/ → insert new books

[selectBook(bookId)]
  └─ repository.refreshBookCoverAndDetails(bookId)
       ├─ try embedded audio picture (MediaMetadataRetriever)
       └─ fallback to book.sourceUrl HTML parsing
```

## Repository Public API (key methods)

```kotlin
// Catalog
val allBooks: Flow<List<AudiobookEntity>>
val downloadedBooks: Flow<List<AudiobookEntity>>
val allBookmarks: Flow<List<BookmarkEntity>>
val recentProgress: Flow<List<PlaybackProgressEntity>>

// Single book / chapters
fun observeBook(bookId): Flow<AudiobookEntity?>
suspend fun getBookSync(bookId): AudiobookEntity?
fun observeChapters(bookId): Flow<List<ChapterEntity>>
suspend fun getChaptersList(bookId): List<ChapterEntity>  // falls back to 4read page parsing if chapters empty

// Bookmarks / Progress
fun observeBookmarks(bookId): Flow<List<BookmarkEntity>>
suspend fun addBookmark(BookmarkEntity)
suspend fun deleteBookmark(id: Long)
suspend fun updateProgress(bookId, chapterIndex, positionSeconds)

// Offline / Cache
suspend fun downloadAudiobookOffline(bookId)        // parallel download all chapters via HttpURLConnection
suspend fun removeOfflineDownload(bookId)
fun getAudioCacheSizeBytes(): Long
suspend fun clearAllAudioCache()

// Import
suspend fun importAudiobookFromHtml(url, html): AudiobookEntity  // parse 4read HTML, extract cover + audio
suspend fun importAudiobookFrom4ReadUrl(urlOrSlug): AudiobookEntity
suspend fun searchAudiobooksOn4Read(query): List<AudiobookEntity>

// Favorites / Stats
suspend fun toggleFavorite(bookId, isFavorite)
suspend fun recordListeningTime(seconds)
```

## 4read.org HTML Parsing

`extractAudioFromHtml` (regex-based) catches:
- Direct mp3/m4a/ogg/aac/m3u8 URLs
- Relative `/uploads/...` audio paths
- PlayerJS / Uppod JS variables: `file: "..."` (with `{v1}` obfuscation decode)
- `<source src="...">` HTML5 tags
- Nested iframes (skipping facebook/vk)
- `.txt` / `.m3u` / `[{"file":"..."}]` playlist files

## Dependencies

- **Inbound:** `MainViewModel`, all screens (`HomeScreen`, `LibraryScreen`, `BookDetailScreen`), `AudioPlayerManager` (for chapter loading)
- **Outbound:** Room (`AudiobookDao`), Android filesystem (`filesDir/audiobooks` for downloads), HTTP (HttpURLConnection with custom UA to bypass 4read anti-bot), `MediaMetadataRetriever` for embedded covers
- **External:** `https://4read.org/` (catalog, search, page details)

## Common Tasks

| Task | Touch |
|---|---|
| Add new book to seed | `AudiobookRepository.seedInitialDataIfEmpty()` |
| Change search behavior | `searchAudiobooksOn4Read()` |
| Add download source | new method in `AudiobookRepository`, dispatch from `MainViewModel.downloadBookOffline` |
| Add new screen | new file in `ui/screens/`, wire in `MainActivity` `when (selectedTab)` |

## Known Issues (Phase 2 candidates)

- `AudiobookDatabase.kt:33` — `fallbackToDestructiveMigration()` deprecated (needs explicit boolean)
- `AudiobookRepository.kt` is 1087 lines — split by responsibility (catalog / import / download / cache)?
- Download fallback writes `CACHE_*` / `OFFLINE_AUDIO_*` text files as fake chapter files — `AudioPlayerManager` will fail to play these (real bug)
- HTTP requests use `HttpURLConnection` instead of OkHttp despite OkHttp being a dependency
- Auto-seed hardcodes 9 books; will be skipped if DB has any book (even placeholder) — possible stale state
