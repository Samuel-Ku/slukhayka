# App Entry Module

<!-- Generated: 2026-07-30 | Files scanned: 3 | Token estimate: ~700 -->

## Purpose

App startup, ViewModel wiring, navigation graph (4 tabs + BookDetail overlay + PlayerScreen overlay), back-button handling, and the root Composable that holds everything together.

## Key Files

```
app/src/main/java/com/example/MainActivity.kt              218 lines
app/src/main/java/com/example/ui/MainViewModel.kt          286 lines
app/src/main/AndroidManifest.xml                          ~50 lines
```

## Architecture

```
MainActivity.onCreate
  └─ enableEdgeToEdge()
  └─ setContent { AudiobookTheme { AudiobookApp() } }

AudiobookApp(viewModel = viewModel())
  ├─ selectedTab: StateFlow<SelectedTab>            ← NavigationBar
  ├─ selectedBookId: StateFlow<String?>              ← BookDetailScreen overlay
  ├─ showFullPlayer: StateFlow<Boolean>              ← PlayerScreen overlay
  ├─ playerState: StateFlow<PlayerState>             ← MiniPlayerBar
  └─ BackHandler (closes overlay before nav back)
```

## Tabs (NavigationBar, 4 items)

| Tab | Screen | Icon |
|---|---|---|
| `EXPLORE` | `HomeScreen` | `Icons.Default.Explore` |
| `FOUR_READ_WEB` | `FourReadWebScreen` | `Icons.Default.Language` |
| `LIBRARY` | `LibraryScreen` | `Icons.Default.LibraryMusic` |
| `BOOKMARKS` | `LibraryScreen` (reused with bookmarks filter) | `Icons.Default.Bookmark` |

Note: `SelectedTab` enum also includes `SETTINGS` but no nav item uses it.

## MainViewModel Responsibilities

```kotlin
// Holds
val repository: AudiobookRepository
val playerManager: AudioPlayerManager
val playerState: StateFlow<PlayerState>

// StateFlows (consumed by UI)
val allBooks, downloadedBooks, favoriteBooks
val allBookmarks, recentProgress, listeningStats
val selectedTab, searchQuery, selectedGenreFilter
val showFullPlayer, selectedBook
val selectedBookChapters, selectedBookBookmarks
val cacheSizeFormatted

// Actions
fun selectTab(tab) / updateSearchQuery(query) / selectGenreFilter(genre)
fun selectBook(bookId) / setShowFullPlayer(show)
fun playAudiobook(book, chapterIndex?, autoPlay = true)
fun toggleFavorite, addBookmarkAtCurrentPosition, deleteBookmark, jumpToBookmark
fun downloadBookOffline, removeOfflineDownload
fun clearAllAudioCache, refreshCacheSize
fun importAndPlay4ReadHtml / importAndPlay4ReadUrl
```

## AndroidManifest (key declarations)

```xml
<application android:label="..." android:icon="@mipmap/ic_launcher"
             android:theme="@style/Theme.4read"
             android:networkSecurityConfig="@xml/network_security_config"
             android:dataExtractionRules="@xml/data_extraction_rules"
             android:fullBackupContent="@xml/backup_rules">

  <activity android:name=".MainActivity" android:exported="true">
    <intent-filter>
      <action android:name="android.intent.action.MAIN"/>
      <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
  </activity>
</application>
```

## Dependencies

- **Inbound:** Android framework (`Activity`, `Lifecycle`)
- **Outbound:** `com.example.ui.{theme, screens, components}.*`, `com.example.player.AudioPlayerManager`, `com.example.data.repository.AudiobookRepository`, `com.example.data.db.AudiobookDatabase`

## Common Tasks

| Task | Touch |
|---|---|
| Add new tab | `MainActivity.kt` (NavigationBarItem + when branch), `MainViewModel` (no change needed if data already exposed) |
| Change back behavior | `MainActivity.kt` BackHandler block |
| Add new action to ViewModel | `MainViewModel.kt` |
| Modify manifest permissions/activity | `AndroidManifest.xml` |

## Known Issues (Phase 2 candidates)

- `SelectedTab.SETTINGS` is defined but unused — dead code
- `BOOKMARKS` tab reuses `LibraryScreen` (same composable, different filter) — works but confusing; consider dedicated bookmarks screen
- No deep linking / app links configuration despite 4read.org being a known source
- Activity theme is `@style/Theme.4read` (XML) — verify it matches Compose `AudiobookTheme`
- No process lifecycle handling beyond `onCleared()` releasing player
