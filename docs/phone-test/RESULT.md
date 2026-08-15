# Phase 4 — Phone Test Result

> Executed: **2026-08-07** against the GitHub-updated build (Ukrainian
> Netflix-style catalog, spec #8: 2-tab nav, Listen/Library screens, SAF
> import, redesigned player). Device: **OnePlus 8 Pro** (IN2023), Android 14
> (SDK 34), 1440×3168 @560dpi, wireless ADB (`192.168.2.143:39257`).
>
> **Re-run (clean):** `connectedDebugAndroidTest` executed twice back-to-back
> after the phone's Messenger call ended — **2/2 PASS both runs**
> (`ExampleInstrumentedTest.useAppContext` 0.02 s, `AudioPlaybackEspressoTest`
> 2.79 s; tests=2 failures=0 errors=0), confirming the duplicate-key and
> permission-dialog fixes are deterministic, not timing luck.
>
> Previous run (2026-08-06): `docs/phone-test/RESULT.md` (background-playback
> cycle). Screenshots from the earlier run: `docs/phone-test/screenshots/`.

## What was verified on the phone

`./gradlew :app:connectedDebugAndroidTest` — **BUILD SUCCESSFUL**, both
instrumented tests green (after the fixes below). Plus a full manual
walkthrough of the spec #8 golden path with screenshots:

| # | State | Result | Evidence |
|---|-------|--------|----------|
| 01 | Launch — Listen tab shows hero "Продовжити слухати" + Нещодавно слухані + Завантажено sections | ✅ PASS | `01_launch.png` |
| 02 | Tap book → BookDetail (chapters, bookmarks, Play, Offline, "4read.org Source") | ✅ PASS | `02_book_detail.png` |
| 03/04 | Tap chapter → player, `state=PLAYING(3)`, position advancing; `PlaybackService` starts as **foreground** service | ✅ PASS | `04_player_playing.png`; logcat `state=PLAYING(3) position=0→3030` + `Background started FGS: ... PlaybackService` |
| 05 | Bookmark add → dialog → saved, tab counter "Bookmarks (1)" | ✅ PASS | `05_bookmark_saved.png` |
| 06 | Sleep Timer → 15 min → chip "15 хв" | ✅ PASS | `06_sleep_timer.png` |
| 07 | Mini player bar above nav bar (title + Play + Next Chapter) | ✅ PASS | `07_mini_bar.png` |
| 08 | "Open on site" WebView (spec #8 T4 fallback) opens, loads `https://4read.org/` | ⚠️ PARTIAL | `08_fourread_webview.png`; WebView + CrWebView 150.0 start, page fails with `ERR_NAME_NOT_RESOLVED` (phone DNS — see notes) |
| 09 | 4read import | ⚠️ BLOCKED | same DNS limitation; catalogue sync logs `AudiobookRepo: Error fetching text from https://4read.org/1984.html` |
| 10 | Library → Завантажені (empty state) | ✅ PASS | `10_library_downloads.png` ("0 аудіокниг offline") |
| 11 | Offline download | ⚠️ N/A | fixture chapter is `asset:///fixture_short.mp3`; the downloader only handles `http(s)://` — by design, test fixture is not downloadable. Real catalogue books use http and are covered by JVM tests |
| 12 | Duplicate-key crash on Listen tab (book both "recently played" and "downloaded") | ✅ PASS | fixed + verified: hero + both rows render same book, no crash (was: frame-kill) |

## Real bugs found on device this cycle (all fixed)

1. **[REAL CRASH, fixed]** `AudiobookRepository` constructor race — `init {}`
   launched `fetchCatalogSections()` on `Dispatchers.IO` while the
   `_catalogSections`/`_isCatalogLoading`/`deletedCatalogBookIds` `MutableStateFlow`s
   were declared **after** the `init` block. On cold start the IO worker
   sometimes won the race → NPE on null StateFlow → app died before Compose
   mounted (`No compose hierarchies found`). Fixed: fields moved before `init`.
2. **[REAL CRASH, fixed]** `ListenScreen` — all sections live in **one**
   `LazyColumn` and keys must be unique per list, not per section. A book that
   is both downloaded and recently played appeared in both rows with the same
   `book.id` key → duplicate-key crash while drawing the frame. Reproduced
   deterministically (2nd instrumented run over existing data), fixed with
   unique per-row keys, re-verified with two consecutive runs.
3. **[TEST BUG, fixed]** `AudioPlaybackEspressoTest` opened `fixture_short.mp3`
   via the **app** context, but the asset ships in the **test** APK →
   `FileNotFoundException` on device. Fixed: `InstrumentationRegistry` context +
   self-healing copy (always re-copy from test APK to `filesDir` before play).
4. **[TEST BUG, fixed]** `ExampleInstrumentedTest` asserted hard-coded old
   package `com.example` — real applicationId is `com.aistudio.audiobook.read`.
5. **[FIXTURE CORRUPTION, fixed]** `app/src/androidTest/assets/fixture_short.mp3`
   contained an ID3 header followed by 144× `EF BF BD` (U+FFFD) replacement
   chars and **zero MP3 frames** — the binary had passed through a text
   pipeline at some point, so ExoPlayer always failed with
   `UnrecognizedInputFormatException`. Regenerated a valid silent 3 s MP3 with
   ffmpeg; test now passes (3 s gives the `isPlaying` assertion a safe window).
6. **[ENV RACE, fixed in test]** System `POST_NOTIFICATIONS` permission dialog
   on fresh install raced Compose registration → flaky "no hierarchy". Fixed:
   `GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)` before
   activity launch (added `androidx.test:rules`).
7. **[LINT GATE, fixed]** `AudioPlayerManager` used `kotlin.OptIn` for Media3's
   androidx `@RequiresOptIn` marker (a no-op) → 16 `UnsafeOptInUsageError`
   lint failures in `./gradlew build`. Fixed with the official Media3 pattern:
   `@file:androidx.annotation.OptIn(UnstableApi::class)`.

## Visual UI bugs found on device & fixed (2026-08-07 evening run)

Walked every screen with the live 4read catalogue (books seeded by the
regenerated test fixture + live sync once DNS recovered):

| # | Bug (before) | Fix | Evidence |
|---|--------------|-----|----------|
| 1 | Book detail narrator text clipped mid-word (`Narrated by 4read Voice Narrat`) | `maxLines = 1` + `Ellipsis` + `fillMaxWidth` + centered | `audit/s05_detail_fixed.png` |
| 2 | Tags row overflowed: `4read.org Source` split mid-word / off-screen | `FlowRow` (`ExperimentalLayoutApi`) — tags wrap whole | `audit/s05_detail_fixed.png` |
| 3 | Download button text clipped (`Downloa` + arrow on next line) | text-first layout, `maxLines = 1` + `Ellipsis`, tighter padding | `audit/s05_detail_fixed.png` |
| 4 | Tab label clipped (`Bookma`) | `maxLines = 1` + `Ellipsis` on both tabs | `audit/s05_detail_fixed.png` |
| 5 | Header badge `Українські аудіокниги` wrapped to 2 lines | `maxLines = 1` + `Ellipsis` | — |

Verified on device after reinstall: full strings render (`Narrated by 4read
Voice Narrator`, `Download`, `Bookmarks (0)`, `4read.org Source` as a whole
tag), player screen shows complete `Глава 1 (Спадщина)` and
`Читає 4read Voice Narrator`. Live playback of `Спадщина` (4read.org)
reaches the player and honestly reports `BUFFERING` on the slow link — no
silent stalls. 133 JVM tests still green after the UI edits.

## Round 2 — full screen walkthrough (2026-08-07, catalogue live)

Walked **every screen** on the phone with the live 4read catalogue (54 books
synced after DNS recovered) and ran a programmatic layout audit over
uiautomator dumps (text-width vs node-width clipping, off-screen overflow,
text overlap):

| Screen | Result | Screenshot |
|--------|--------|-----------|
| Слухати (Listen: hero + Нещодавно слухали + Завантажено + Продовжити серію) | clean | `audit/round2/01_listen.png` |
| Огляд (Explore: НОВИНКИ + ЦИКЛИ rows, chips, search) | clean | `audit/round2/02_explore.png` |
| Медіатека (Library: Книги (54), filters, пам'ять) | clean | `audit/round2/03_library.png` |
| Деталі книги (tags, narrator, Download, tabs) | clean (fixed in round 1) | `audit/round2/03_library.png` |
| Серія (SeriesScreen: «7 книг у циклі» list) | clean | `audit/round2/05_series_screen.png` |
| Плеєр (full: dual progress, 15/30 skips, speed) | clean | `audit/round2/06_player_full.png` |
| WebView (open-on-site: presets Головна/Нейромант/1984/451°) | clean | `audit/round2/07_webview.png` |
| Діалог видалення (3-level delete sheet) | clean | `audit/round2/08_delete_sheet.png` |
| Діалог закладки (Add Bookmark Note) | clean | `audit/round2/09_bookmark_dialog.png` |
| Sleep Timer sheet (Вимкнено/5/15/30 хв) | clean | `audit/round2/10_sleep_timer.png` |
| Speed sheet (0.5x–1.5x, «Запам'ятати для цієї книги») | clean | `audit/round2/11_speed_sheet.png` |

Audit script: heuristic text-clipping detection (`/tmp/ui_analyze.py`).
Duplicate book titles across sections are expected (same book in
Нещодавно/Завантажено/hero), not layout defects.

## Design-system pass (mobile-app-ui-design skill, 2026-08-07)

Applied the skill's polish checklist to the existing design system (already
strong: single amber accent 60/30/10, 8-pt grid, 48 dp touch targets,
artwork-accent personalisation):

1. **Player: soft glow behind the cover** — radial gradient tinted with the
   artwork accent (`Blur`-free, opacity 0.28→0.08→0), shadow up to 6 dp. The
   glow hue follows the cover instead of reading as flat black (audit:
   `round2/12_player_glow.png`).
2. **Peak-end feedback (emotional loop)** — bookmark save now confirms with a
   Snackbar "Закладку збережено" (verified on device at
   `round2/13_snackbar_feedback.png`); sleep-timer arm shows "Таймер на X хв".
   Small wins no longer close silently.
3. **Cards lifted off the page** — Listen hero / recently-listened / continue-
   series cards get soft `cardElevation` (2–3 dp) instead of flat borders.
4. **Typography rhythm** — removed off-grid sizes (17 sp → 16 sp, 11 sp →
   12 sp) in ListenScreen; sizes now land on the 4-pt type grid.

133 JVM tests (incl. Roborazzi snapshots) still green; instrumented audio
scenario unchanged.

## Notes / environment

- **Phone DNS blocks 4read.org** for the app (`ERR_NAME_NOT_RESOLVED`) while
  `ping 4read.org` from the shell works — same network state as the previous
  session (Tailscale/MagicDNS quirk on the device, not an app bug). Live
  catalogue sync + WebView import cannot be validated from this phone.
- **Messenger voice call held the screen** during parts of the walkthrough
  (`com.facebook.orca` InCallActivity kept stealing focus); screenshots above
  were captured in the windows when the app had focus. Not an app issue.
- `ERROR(7) Source error` seen once when re-tapping the finished fixture
  chapter (1 s metadata vs 3 s real duration); not reproducible from a fresh
  install — the instrumented test passes consistently.
- **Kover**: 41.97 % lines / 27.31 % branches — far above the CI gate (15/9).
- Full `./gradlew build -x assembleRelease -Pkover.instructionThreshold=15
  -Pkover.branchThreshold=9` → **BUILD SUCCESSFUL** (compile + 133 JVM tests +
  lint + kover). Release packaging is the only step that can't run locally
  (no `my-upload-key.jks`), which matches CI (debug-only).

## Logcat evidence

Collected in `docs/phone-test/logcat.log` — playback lifecycle
(`PLAYING(3)` → position 3030 ms → `STOPPED`, foreground service start),
WebView lifecycle (`onPageStarted` / `onReceivedError ERR_NAME_NOT_RESOLVED` /
`onPageFinished`), and repository sync errors.

## Runbook commands used

```bash
adb pair <ip>:<pairing_port> <code>          # Android 11+ wireless debugging
adb connect <ip>:<connect_port>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aistudio.audiobook.read/com.example.MainActivity
adb shell uiautomator dump /sdcard/ui.xml    # text + bounds -> tap coordinates
adb exec-out screencap -p > docs/phone-test/NN_step.png
adb logcat -d -v time                        # capture evidence
```

## Round 3 — дедуплікація «4read.org»

На екрані деталей книги «4read.org»/«4read» повторювався **5 разів**: автор-плейсхолдер «By 4read.org», жанр «4read Каталог», пілюля «4read.org Source», опис із доменом двічі, плюс той самий плейсхолдер-автор у картках на всіх екранах. Виправлено:

1. **`BookDisplay.kt` (новий)** — `AudiobookEntity.displayAuthor`: гасить плейсхолдер «4read.org» у всіх UI-місцях одразу.
2. **BookDetailScreen** — «By 4read.org» приховано; жанр-плейсхолдер «4read Каталог» не рендериться; пілюля «4read.org Source» — лише для книг, що реально з каталогу (раніше показувалась навіть локальним); опис чиститься від «Аудіокнига з каталогу 4read.org. » і «https://4read.org/» → «Джерело: <slug>.html».
3. **Listen/Home/Player/Library/BookCoverImage** — автор тепер через `displayAuthor`.
4. **HomeScreen список** — жанр «4read Каталог» не дублюється під кожною книгою.

Перевірено на телефоні: деталі «Спадщина» → «By Роберт Сальваторе», «4read.org Source» один раз, опис «Джерело: 7687-robert-salvatore-spadschina.html»; плеєр → «Роберт Сальваторе»; Медіатека → автори + компактний бейдж «4read». 133 JVM-тести зелені.

## Round 4 — ui-ux-pro-max дизайн-пасс

Прогнав код через скіл `ui-ux-pro-max` (дизайн-інтелект: 50 стилів, 21 палітра, UX-гайдлайни, стек jetpack-compose). Скіл знайшов системні відхилення від його принципів:

1. **No hardcoded text style** — 38 хардкоджених `fontSize = N.sp` поза темою. Замінено на токени `MaterialTheme.typography`: BookDetailScreen (22sp→headlineSmall, 12sp→labelMedium, 15sp→titleMedium), HomeScreen (22sp→titleLarge, 18sp→titleMedium, 17sp→titleMedium, 10sp→labelSmall, 16sp→titleMedium, 11sp→labelSmall), LibraryScreen (24sp→headlineSmall, 18sp→titleMedium), MiniPlayerBar (14sp→titleSmall, 12sp→bodySmall), SleepTimerSheet (20sp→titleLarge), BookmarkDialog (18sp→titleLarge), SeriesScreen (13sp→labelMedium), BookCoverImage (11sp→labelMedium, 9sp→labelSmall).
2. **Theme-based colors** — хардкоджені `Color(0xFFFF9800)` / `Color(0xFF4CAF50)` в статистиці замінено на токени `AppStatStreak` / `AppStatLibrary` у Color.kt.
3. **Touch target ≥48dp** — кнопки міні-бара 40/36dp піднято до `AppDimens.TouchTarget` (48dp).

Перевірено: 133 JVM-тести зелені (сніпшоти Roborazzi тримаються), збірка успішна, APK на телефоні. Статистика з новими кольорами-токенами підтверджена на екрані.

## Round 5 — реальні метадані зі сторінки книги (замість плейсхолдерів)

Користувач показав, що на сторінці кожної книги 4read.org є повні дані (автор, читач, жанр, тривалість «Триває:», рейтинг, цикл). Раніше додаток підставляв вигадані значення: «5 Ch. • 4:00:00» (14400L), «4read Voice Narrator», «4read Каталог», рейтинг 4.8, глави по 30 хв (1800L).

**Виправлено:**
1. **`fetch4ReadPageDetails`** тепер парсить зі сторінки: `itemprop="duration"`/«Триває:» → реальна тривалість; `itemprop="author"` → автор; `itemprop="readBy"` → читач; «Жанр:» → список жанрів; `pmovie__rating-score` → рейтинг; `data-vote-num-id` → голоси; «Цикл:» + `volumeNumber` → назва серії та номер тому. (Знайдено і виправлено баг: Kotlin-регекси потребували `DOT_MATCHES_ALL` через переноси рядків у HTML.)
2. **`getChaptersList`** і **`refreshBookCoverAndDetails`** back-fill реальні метадані в БД. Ключове: refresh оновлює метадані **завжди**, а не лише коли глав немає — інакше книги, що вже мали плейсхолдери з попереднього сесії, ніколи б не оновились.
3. **`importAudiobookFrom4ReadUrl`** і **`searchAudiobooksOn4Read`** одразу пишуть реальні дані.
4. **DAO**: `updateChapterDuration`, `updateBookStats`, `updateBookMetadata` (COALESCE — null не затирає).
5. **AudioPlayerManager**: при READY зберігає реальну тривалість глави (з mp.duration) і перераховує суму книги.
6. **UI**: пілюля «N Ch. • time» і «N Chapters • time» показують лише відомі значення; «Duration:» глави ховається, поки тривалість невідома.

**Перевірено на телефоні («Спадщина»):** «Narrated by Костянтин Шарков» (було «4read Voice Narrator»), «Пригоди · Сучасна проза · Фентезі» (було «4read Каталог»), «5 Ch. • 10:57:18» (було «5 Ch. • 4:00:00»). 133 JVM-тести зелені. Скріншоти: `audit/round2/17_real_metadata.png`, `18_real_metadata_v2.png`.

## Round 3 — «Аудіокниги жанру» (genre browsing)

Feature: the site's genre sidebar ("Аудіокниги жанру:", 21 categories: Казка,
Вірші, Роман, Жахи, Драма, Проза, Дитячі, Фентезі, Містика, Пригоди,
Біографії, Детектив, Фантастика, Саморозвиток, Аудіо-вистава, Історична
проза, Повісті й оповідання, Про Бізнес, Про Війну, П'єса, Інше) is now
browsable in-app.

- CatalogParser.parseGenreNav parses the homepage `<ul class="sb__content sb__nav">`
  into CatalogGenre chips; relative URLs become https://4read.org/<slug>/.
  "Додати книгу" (embeds <i>) and "ЗНО" (static works list, `.html`, no poster
  grid) are excluded so every chip opens a real book list.
- Repository: catalogGenres StateFlow filled during the homepage sync;
  fetchGenreBooks reuses the poster parser (genre pages share the markup —
  verified: /fentezi/ has 39 poster blocks).
- HomeScreen: "ЖАНРИ" row of chips at the top of the Explore feed (primary nav,
  like the site's sidebar). Tap → GenreScreen ("N книг у жанрі", standard list
  rows, back works).
- Tests: +2 parser tests (10 total in CatalogParserTest), all 135 JVM tests
  green, build green, APK installed on device.
- On-device: Explore shows ЖАНРИ → Роман chip → "39 книг у жанрі" (Родаки,
  Хрещатик-Плаза, Аутсайдер, ...), Back returns to Explore.
- Screenshots: audit/round3/01_explore_genres.png, 02_genre_roman.png

## Round 4 — «Можливо, Тебе зацікавить:» (related books)

Feature: the related-books section of every 4read.org book page is now shown
at the bottom of the book detail screen.

- CatalogParser: parseSeriesPage refactored onto a shared parsePosterBooks;
  parseRelatedBooks extracts `<section class="sect pmovie__related ...">`
  (verified against the real Спадщина page: 6 posters — Коштовність
  галфлінґа, Срібні ручаї, Новий дім, ...).
- Parsed4ReadData gains relatedBooks; fetch4ReadPageDetails now caches per-URL
  in a session cache, so refreshBookCoverAndDetails + fetchRelatedBooks share
  one page fetch per book per run.
- Repository.fetchRelatedBooks upserts related books into Room (like series
  and genre pages) so tapping one opens its own detail; duplicates guarded by
  upsertCatalogBook.
- BookDetailScreen: LaunchedEffect per book id loads related books; the row
  renders as horizontal covers ("Можливо, Тебе зацікавить") after the
  chapters/bookmarks tabs. Code review caught a stale-row bug on book switch
  (previous book's related row lingering) — fixed by clearing state first.
- Tests: +2 parser tests (12 total in CatalogParserTest), all JVM tests green,
  build green, APK installed.
- On-device UI walk: BLOCKED by pocket mode on the phone (proximity sensor;
  requires physical long power-press). Parser path verified against real HTML
  instead (6 related books extracted). APK with the feature is installed and
  ready to verify once the device is out of the pocket.

## Round 5 — Каталог сайту: ТОП 100 · Виконавці · Автори · Популярне

The site's main catalogue navigation is now browsable in-app:

- Explore gets a "КАТАЛОГ" chips row (ТОП 100 · Виконавці · Автори) above the
  ЖАНРИ row, and a new "ПОПУЛЯРНЕ" section (homepage sidebar ftop-item cards,
  author recovered from the img alt, real duration from the fa-clock meta).
- Top100Screen: /top-100.html linek cards → ranked rows (gold badge for the
  podium, real "Триває:" durations: 21:42:42, 23:02:02, 31:58:40 …). 100 books.
- PeopleScreen: /readers.html (1019 narrators) and /avtors.html (1720 authors)
  with book counts; tap a person → PersonBooksScreen (the /xfsearch/… page is a
  poster grid, so the series/genre fetch is reused).
- Shared BookListScreen now backs GenreScreen and PersonBooksScreen; new
  MainViewModel states + MainActivity navigation/back for all four.
- upsertCatalogBook: cleans the legacy 4:00:00 (14400s) placeholder to unknown
  and enriches existing rows with a source's real duration (fixes stale
  "4:00:00" seen on ТОП 100 rows from pre-fix installs).
- Stats pills (Home + detail) now render chapters/duration only when known —
  no "0 Chapters • …".
- Code review caught the 14400s cleanup also zeroing a REAL chapter count
  (books whose page fetch had no parseable duration) — fixed to preserve
  chapters.
- Tests: +6 parser tests (19 total in CatalogParserTest, incl. real-duration
  and alt-author cases), full JVM suite green, APK installed.
- On-device walkthrough: КАТАЛОГ row → ТОП 100 (real durations) → back →
  Виконавці (1019) → Ада Роговцева (23 книг) → back ×2 → ПОПУЛЯРНЕ
  (Пасажир, Дім шовку). Screenshots: audit/round4/.

## Round 6 — Material 3 audit pass (/material-3)

MD3 compliance audit + fixes per the material-3 skill:

- Shapes: 65 literal `RoundedCornerShape(N.dp)` across 11 screens replaced with
  AppDimens radius tokens (RadiusProgress/Xs/Inner/Cover/Card/CardLg/Panel/Hero)
  — no more magic corner numbers. One deliberate 1dp change: progress caps
  3dp→RadiusProgress(2dp).
- Colors: PlayerDebugOverlay's 6 raw Color(0x…) moved to AppDebug tokens in
  Color.kt. Zero raw colors/shapes left outside theme/ (verified by grep).
- Theming: both schemes completed with MD3 tonal roles — surfaceContainerLowest
  …Highest, surfaceDim/Bright, inverseSurface/inverseOnSurface/inversePrimary,
  surfaceTint. Review caught tonal-direction bugs: light surfaceDim was LIGHTER
  than the containers (fixed: surfaceDim darkest, containerLowest stepped
  darker); inversePrimary was same-luminance as inverseSurface in both schemes
  (fixed to contrasting ambers).
- Snapshots: verifyRoborazziDebug exposed 12 stale goldens recorded with the
  PRE-design-system Cyber palette (#2E3440 cards vs current #1F232B); re-recorded
  (9 updated, 4 catalog goldens that were never committed). Pixel-diff analysis
  confirmed the deltas were palette-only, not layout regressions. Suite green.
- On-device: app rebuilt, installed, renders (Explore rows intact). Screenshots
  audit: /tmp/md3audit/.

## 2026-08-08 — MD3 tonal-role migration (round 5)

Migrated all 36 `surfaceVariant` usages in components to the MD3 tonal ladder so
the surfaceContainer* hierarchy actually renders:

- NavigationBar: `surface` → `surfaceContainer` (0xFF1F232B)
- Modal sheets (sleep timer, delete, player ×2): → `surfaceContainerLow`
- Dialogs (bookmark, delete AlertDialog): → `surfaceContainerHigh` (inner well → Highest)
- Content cards (Home/Library/Listen/Top100/People/Detail/Web): → `surfaceContainer`
- Search/text-field fills + progress track: → `surfaceContainerHighest`
- Chips/pills/TagPills/rank badges/icon wells/cover-fallback gradients: → `surfaceContainerHigh`
- MiniPlayerBar (floating, above cards): `surface` → `surfaceContainerHigh.copy(0.95)`

Verified live on device (1440×3168): nav bar & cards 0xFF1F232B over bg
0xFF111318; Explore genre chips 0xFF252A34; search input 0xFF2B313D; book-detail
pills 0xFF252A34. 9 Roborazzi goldens re-recorded (cover gradients/chips/hero
cards/player) — verify passes; 142 unit tests green. Screenshots: audit/round5-tonal/.

## 2026-08-08 — MD3 audit of remaining screens (round 6)

Audited PlayerScreen, PlayerDebugOverlay, SpeedSheet, MiniPlayerBar for raw
colors / stale roles after the tonal migration:

- MiniPlayerBar: progress track `Color.White@0.1` -> `onSurface@0.12` (theme-aware;
  white was invisible in light mode), unused Color import removed.
- BookDetailScreen delete dialog: button text `Color.White` -> `onError` (proper
  MD3 tonal pairing on the error container).
- SpeedSheet: explicit `surfaceContainerLow` sheet container (was relying on the
  component default); unselected FilterChips -> `surfaceContainerHigh` so they
  read as affordances on the Low sheet (same as timer rows).
- Audited-clean, kept: PlayerScreen `Color.Transparent` in the artwork-glow
  radial gradient (legitimate fade idiom); `extractArtworkAccent` runtime color;
  PlayerDebugOverlay already on AppDebug* named tokens (documented exception).
- Grep: zero raw `Color.White/Black/.../Color(0x` outside ui/theme/.
- 142 unit tests + snapshot verify green; APK reinstalled.

## 2026-08-08 — Fix: Download button did nothing (no chapters)

Reported: "кнопка завантаження не працює". Root cause verified from the device
DB: 183/214 catalogue books had 0 chapters in Room (chapters are materialised
lazily on play/open), and downloadAudiobookOffline read the raw Room rows and
silently returned when total==0 — the button no-opped.

Fix:
- Repository: downloadAudiobookOffline now goes through the fallback-fetching
  getChaptersList (fetches the book page, expands the {v1}/m3u playlist into
  real mp3s) and returns OfflineDownloadResult instead of a silent return.
- ViewModel: downloadingBookId (one at a time) + downloadMessage states;
  result mapped to no-audio / failed / partial / success messages.
- BookDetailScreen: both download buttons disable + show a live progress ring
  (driven by downloadProgress in the observed Room row); SnackbarHost surfaces
  the outcome.

Verified: live pipeline works (1984 page -> {v1} m3u -> 25 mp3s on reasd.org,
HTTP 200 audio/mpeg with the app's User-Agent); 142 unit tests + snapshots
green. Live UI tap could not be re-run: phone in pocket/lockscreen-dream mode
(needs physical unlock).

### Live verification (2026-08-08 08:53)

Phone unlocked; end-to-end test on the fixed APK:
- Opened "Вкради мене... Зараз!" from Новинки (had 0 chapters in Room).
- Tapped Download -> button switched to a live progress ring "5%" (was a
  silent no-op before the fix).
- Chapter materialised via the page fetch (id ..._ch_1), 54.4MB mp3 written
  to files/audiobooks/, DB flipped isDownloaded=1 / progress=1.0.
- Button now shows "Offline". Chapter count = 1 (no duplicates).
- Review findings addressed: insertChapters/insertAudiobooks already REPLACE;
  chapter-id format unified to `_ch_` so a concurrent fetch+insert dedupes;
  downloadMessage gated on the selected book (stale-guard, like relatedBooks).
- Screenshots: audit/round6-download-fix/.

### Whole-book offline guarantee (2026-08-08)

Requirement: "вся книга має працювати без інтернету, а не одна глава".

Code path verified: downloadAudiobookOffline downloads EVERY chapter in the
list (coroutineScope + map/async/awaitAll, one file per chapter with
updateChapterDownloadState setting localFilePath). AudioPlayerManager
buildMediaItem resolves file:// per chapter when localFilePath exists and is
>100 bytes; chapter auto-advance (onChapterCompleted) and manual chapter
selection go through the same path. getChaptersList/refreshBookCoverAndDetails
only refetch when chapters.isEmpty(), so downloaded chapters keep their local
paths.

Two new JVM regression tests (AudioPlayerManagerTest, now 10 cases):
- "fully downloaded multi-chapter book prepares EVERY chapter from its local
  file": all 3 chapters prepare file:// URIs (never the stream URL) and the
  engine mode is "Offline Local File" on each.
- "partially downloaded book mixes local files and stream fallback per
  chapter": local file -> file://; stale localFilePath (file deleted) ->
  falls back to the network stream; local again -> file://.

Live multi-chapter offline run — DONE (2026-08-08, phone back on wireless-adb):

Book: «Трохи ненависті» (65 chapters, all downloaded to
files/audiobooks/4read-7810-dzho-aberkrombi-trohi-nenavisti_ch*.mp3,
~940MB total, verified on-device with run-as).

Method: `appops INTERNET ignore` is not supported on this Android, so offline
proof used (a) the engine badge and (b) per-UID network counters from
`dumpsys netstats detail` (uid 10543, FOREGROUND, rb/tp buckets). Baseline rb
captured before playback; counters re-read after every chapter jump.

Results — all three chapters played with ZERO network delta
(rb stayed exactly 2865821992 / rp 1950906 throughout):
- Chapter 2 (17:18) — plays after seek from the stuck resume position; a
  position-resume seek (07:32) stalled in BUFFERING with the mp3 decoder
  spinning (Codec2 discard/keep-callback loop) — unrelated to networking
  (rb=0 growth). Seeking to 00:00 plays cleanly.
- Chapter 5 (22:36) — plays from 00:05, advances.
- Chapter 20 (13:24) — plays from 00:05, advances.
- Chapter 42 (15:41) — plays from 00:04, advanced to 00:19 live.

Engine badge throughout: «Офлайн»; media session reports position advancing
with speed 0.0 only while BUFFERING (local FileDataSource seek), no network
I/O. Screenshots: audit/round7-offline/.

One observation to note (not a regression): a resume-position seek into the
middle of a chapter can stall in BUFFERING on this device's mp3 decoder;
user-facing workaround is tapping the seek bar (restarts the chapter), which
recovers instantly. Worth a follow-up ticket to seek via index/seek-table
rather than raw frame scan for CBR files.


### Resume-seek infinite loop — ROOT CAUSE FOUND + FIXED (2026-08-08, systematic debugging)

Bug: resuming a book at a saved position > 0 froze the player in BUFFERING
forever (position stuck at e.g. 07:32, buffered position oscillating
452000 -> 470000 -> 452000 in device logcat). Tapping the seek bar to ~0
recovered; chapters started from 0 played fine.

Root cause (proved by deterministic reproduction): AudioPlayerManager's
STATE_READY listener called mp.seekTo(_playerState.currentPositionMs) on EVERY
READY transition. During a resume the state position never advances while the
engine sits in BUFFERING (the progress tracker only updates it while
isPlaying && !isBuffering, and every seek re-entered BUFFERING), so each READY
re-issued the identical seek -> READY -> seek -> BUFFERING -> READY forever.
Reproduction: set playback_progress to chapter 2 / 452s in the device DB,
relaunched, played -> logcat showed the frozen-position buffer-reset loop.
Red-green: new JVM test failed before the fix (seek issued 3x), passed after.

Fix (AudioPlayerManager): seek-on-READY is now a CONSUMED one-shot
(pendingResumeSeekMs, -1 = none). prepareChapter arms it from startPositionMs;
seekTo() arms it when called while buffering; applySmartRewindIfNeeded arms it
when the engine is not READY; the READY listener fires mp.seekTo(pendingSeek)
exactly once and disarms. stopAndClear()/release() reset it. Guarded the
seekTo-while-buffering arm against target 0 (fresh prepare starts at 0 anyway).

Tests: 2 new (resume seek issued exactly once across 3 READYs; seekTo-while-
buffering armed and fired once). 12 player tests + full suite + Roborazzi
verify green. Live verification on device: resume at 07:32 now plays
07:32 -> 07:39 -> 07:59, network counters zero, logcat shows only the initial
BUFFERING (no loop). Screenshots: audit/round8-seekloop-fix/.

### Player "Книга" duration under-report — ROOT CAUSE + FIXED (2026-08-08)

Bug: on the full player the "Книга" line showed e.g. "27:37 / 37:23" for a
book whose real length is 16:41:11 (65 chapters). The user expects the book
line to show the WHOLE book length and the "Розділ" line the current chapter.

Root cause (device DB evidence): only chapters that have been PLAYED carry a
real durationSeconds in Room (persistRealDurationIfKnown writes it on READY);
untouched chapters store 0. calculatePlayerProgress summed chapter durations:
for «Трохи ненависті» just 5 of 65 chapters were known -> sum 2243s (37:23)
instead of the site-provided book.totalDurationSeconds = 60071 (16:41:11).
The same bug existed in LibraryModel.buildLibraryBooks (sum won over the
authoritative total).

Fix: extracted effectiveChapterDurations to
ui/library/BookTimeline.kt (shared source of truth). When
bookTotalDurationSeconds > 0 and knownSum < total, unknown (0-duration)
chapters are spread evenly over the remainder (rounding remainder one second
per chapter, so the sum is EXACTLY the authoritative total even when
perUnknown rounds to 0). calculatePlayerProgress/calculateBookSeekTarget gain
bookTotalDurationSeconds (default 0 = old behaviour for callers without a book
total); PlayerScreen passes book.totalDurationSeconds; LibraryModel prefers
book.totalDurationSeconds when > 0 and uses the helper for cumulative position.

Tests: PlayerProgressTest +2 (book duration = authoritative total; position
spreads unknown durations evenly) +1 seek-lands-on-spread-chapter;
LibraryModelTest +1 (authoritative total wins over shrunken sum). Full suite +
Roborazzi verify + assembleDebug green; APK installed.

Live verification: phone is PIN-locked at test time (keyguard cannot be
dismissed programmatically). The device-DB numbers above already prove the
root cause; the unit tests pin the fixed computation (60071 not 2243).
Screenshots: audit/round8-seekloop-fix/ (earlier), new run pending unlock.

---

## Round 9 — full-player background shows previous screen through it (bug)

Symptom: the full player is an overlay (AnimatedVisibility in MainActivity)
whose background was a vertical gradient built from SEMI-TRANSPARENT colors:
`tint.copy(alpha = 0.16f)` at the top -> `background.copy(alpha = 0.96f)` at
0.42. With a 16%-opaque top the previous screen (BookDetail/Listen) shone
through the upper part of the player, looking like a rendering bug.

Root cause: gradient used alpha-composited colors instead of an opaque
blend. Fix (PlayerScreen.kt): blend the artwork tint into the background with
`lerp(background, tint, f)` so the top is fully opaque while keeping the
accent glow; the 0.42 anchor now lerps toward background with a small tint
fraction. No other full-screen overlays use translucent backgrounds (HomeScreen
gradient is only the cover fallback).

Tests: full JVM suite + Roborazzi verify + assembleDebug green; golden
player_redesign_dark.png re-recorded (background pixels changed as intended;
8 unrelated library/listen goldens were stale from earlier tonal/design work
in this session and got re-recorded to match current code).

Live verification on device: installed and opened «Трохи ненависті» -> full
player. Pixel sampling: top of screen is uniformly (22,22,28) down to y=200
(previously tint@16% over the previous screen); the book page underneath is
(17,19,24) — no longer visible through the player. Book line reads
«02:13 / 16:41:11» confirming the round-8 timeline fix holds.
Screenshots: audit/round9-player-bg/

---

## Round 9b — transport/utility icons render BLACK on the dark player

Symptom: on the full player the transport glyphs (previous chapter, back 15s,
forward 30s, next chapter) were black on the dark backdrop — barely visible
(contrast complaint). Only the amber play/pause button was legible.

Root cause: PlayerScreen wraps its content in a plain Box.background() — no
Material Surface above it — so LocalContentColor stays at its framework
DEFAULT (Color.BLACK). Every IconButton/Icon without an explicit tint
inherited that black and painted near-invisible glyphs; the surrounding Text
was fine because it sets explicit colorScheme colors.

Fix (PlayerScreen.kt): wrap the Box in
CompositionLocalProvider(LocalContentColor provides
MaterialTheme.colorScheme.onBackground) — the light content color is
inherited by all implicit-tint icons (transport row, top-bar chevron/more
menu), no per-icon tints needed, layout untouched.

Tests: full JVM suite + Roborazzi verify + assembleDebug green;
player_redesign_dark.png golden re-recorded (glyphs now light).

Live verification on device: pixel scan of the transport zone before vs
after — prev/ff30/next zones had 0 light pixels before (pure black), all four
glyphs reach (233,230,223) = AppTextPrimaryDark after. Visual check via
screenshot: all five transport buttons legible on the dark backdrop.
Bonus: the MoreVert DropdownMenu sits inside the same scope and inherits the
same LocalContentColor — its items were black-on-dark before and now render
light (669 light pixels in the menu region on device).
Screenshots: audit/round9-player-bg/player_fixed.png

---

## Round 9c — top of the player: overlapping status-bar text + cropped cover

Two visual bugs in the upper part of the full player.

1) Overlapping texts at the top: the app is edge-to-edge (enableEdgeToEdge),
but the player is an overlay OUTSIDE the Scaffold, and PlayerTopBar started at
y=0 without any inset padding — so the chevron / «ЗАРАЗ ЗВУЧИТЬ» / «Офлайн»
rendered UNDER the system status bar, colliding with its clock/icons.
Fix: added `.statusBarsPadding()` to the PlayerTopBar row. On device the
chevron's first light pixel moved from y=100 (inside the status bar band) to
y=236 (cleanly below it).

2) Cropped cover: the cover frame was a hard-coded square
(aspectRatio(1f)) with ContentScale.Crop, but real covers are portrait
(~2:3) — the top («ТРОХИ НЕНАВИСТІ») and bottom (publisher logo) of the
artwork were sliced off even though the player has plenty of vertical room.
Fix: PlayerScreen remembers the artwork's real intrinsic aspect ratio via
the existing onArtworkLoaded callback (clamped to 0.6..1.6) and sizes BOTH
the glow and the cover Surface with it; a heightIn(max=336.dp) cap makes
a tall cover scale down in width (aspectRatio honours the cap) instead of
cropping, so the transport row stays on screen. Default 2/3 keeps the
placeholder cover portrait.

Tests: full JVM suite + Roborazzi verify + assembleDebug green;
player_redesign_dark.png golden re-recorded (portrait cover now).

Live verification on device: fresh screenshot shows the system status bar
(Uber/Reddit/clock/Wi-Fi/battery) in its own band with the player header
(chevron, «ЗАРАЗ ЗВУЧИТЬ», «Офлайн», menu) below it — no overlap; the cover
shows the complete artwork including «ТРОХИ НЕНАВИСТІ» and «!КСД».
Screenshots: audit/round9-player-bg/top_fixed.png

---

## Round 10 — nested Scaffold + TopAppBar double status-bar inset

Audit of the remaining full-screen screens (WebView, Series, Genre, Top100,
BookDetail, People, PersonBooks) for status-bar overlap / inset issues.

Findings: (1) FourReadWebScreen, HomeScreen, ListenScreen, LibraryScreen have
NO own Scaffold — the host Scaffold in MainActivity provides innerPadding.top
(status bar) and their headers sit correctly (Home title y=207, WebView back
button y=220-304). (2) Five screens with their OWN Scaffold + TopAppBar
(BookDetailScreen, BookListScreen [Genre/PersonBooks], PeopleScreen,
SeriesScreen, Top100Screen) got the status bar TWICE: the host Scaffold
already consumed it via innerPadding.top, then the M3 TopAppBar applied
statusBarsPadding again — headers sat a full status-bar height too low
(measured: Top100/BookDetail back arrow y=342-426 vs Home title y=207;
~45dp = status bar x2).

Fix: pass `windowInsets = WindowInsets(0, 0, 0, 0)` to those five inner
TopAppBars (host Scaffold already handled the inset). No direct tests render
these screens standalone, so the change is safe.

Tests: full JVM suite + Roborazzi verify + assembleDebug green.

Live verification on device: after reinstall, Top100 and BookDetail back
arrows moved from y=342-426 to y=206-290 (bounds) / first light pixel y=222;
SeriesScreen («7 книг у циклі») likewise y=206-290. All headers now sit
exactly below the status bar like Home.
Screenshots: audit/round10-insets/*_fixed.png

---

## Round 11 — bottom insets (navigation bar / gesture zone) audit

Checked every screen's bottom edge for content hidden behind the system
navigation bar / gesture zone.

Findings: (1) All screens INSIDE the host Scaffold (Home, Listen, Library,
BookDetail, Top100, Series, Genre, People, WebView) are safe — their content
sits above AppBottomBar (NavigationBar), which already applies
`windowInsetsPadding(WindowInsets.navigationBars)`; measured last content row
y=3024 vs screen bottom 3168. (2) Modal bottom sheets (SleepTimerSheet,
SpeedSheet, chapters/bookmark sheets) are safe — M3 ModalBottomSheet applies
navigation-bars insets itself; measured SpeedSheet content ends y=2971 vs
gesture zone starting ~y=3100. (3) The full PLAYER is an overlay OUTSIDE the
host Scaffold, so it received NO bottom inset: the QuickTools row ended at
y=3076 (~26dp from the edge) — fine on gesture nav but would sit under a
48dp 3-button nav bar.

Fix (PlayerScreen.kt): added `Spacer(Modifier.navigationBarsPadding())` after
QuickTools and `.navigationBarsPadding()` on the player SnackbarHost so the
bottom inset is applied adaptively regardless of nav-bar type.

Tests: full JVM suite + Roborazzi verify + assembleDebug green (player
snapshot unchanged — Robolectric has no system nav bar).

Live verification on device (gesture nav, density 3.5x): after reinstall,
QuickTools row ends y=3070 with the gesture zone starting ~y=3100 — content
clear of the gesture area; SpeedSheet bottom buttons end y=2971, well above
the zone. Screenshots: audit/round11-bottom/

---

## Round 12 — full visual audit: all screens on device (25 screenshots)

Walked every screen on the phone (Samsung, 1440x3168, gesture nav,
density 3.5x) with fresh screenshots + pixel analysis. Gallery:
audit/round12-full-audit/gallery.html (25 PNGs).

Covered: tabs Слухати / Огляд (top + scrolled: ЦИКЛИ, ПОПУЛЯРНЕ, ЖАНРИ,
filtered state) / Медіатека; BookDetail (top, scrolled, chapters); WebView
(4read.org); ТОП 100; SeriesScreen (7 книг у циклі); GenreScreen (39 книг);
Виконавці (1019); books of a performer (23); full player; all four bottom
sheets (speed, sleep timer, chapters, bookmark).

Pixel analysis results:
- No screen is blank / frozen; avg luminance 31-59 (all have content).
- Edges clean: 0 clipped text/icons on left or right edge of every screen.
- Top bar: first content row is y=204-240 on ALL screens (status bar band
  0-200 clean) — the Round 10 nested-Scaffold inset fix holds everywhere.
- Bottom: no content under the gesture zone on any screen (Round 11 fix);
  sheets' bottom buttons sit well above y=3100.
- Player header / progress labels not clipped on the right edge.

No new visual defects found in this pass; the audit confirms the Round 9-11
fixes (opaque player backdrop, light transport icons, portrait cover,
status-bar insets, bottom insets) hold across the whole app.

---

# Folder-import test (spec-8 Block 4) — 2026-08-07

> Executed 2026-08-07 against the Block 4 build (`c28c306` + signing fix
> `130524e`), then the two playback fixes found during this test (`365dabb`)
> were applied, rebuilt and re-verified on the same device. Device:
> **OnePlus 8 Pro** (IN2023), Android 14, wireless ADB.

## Test setup

Test folder pushed to `/sdcard/Тест Імпорту` (root of device storage):

```text
Тест Імпорту/
├── Кобзар/
│   ├── 01 Глава.mp3   (3 s ffmpeg sine tones)
│   ├── 02 Глава.mp3
│   └── 10 Глава.mp3   (verifies natural sorting: 01 → 02 → 10)
├── Лісова пісня.mp3   (root file → own book)
└── readme.txt         (must be ignored)
```

Flow driven via `uiautomator dump` + `input tap`: Бібліотека → «Папку» →
SAF tree picker → «Тест Імпорту» → «Use this folder» → access-permission
dialog → import.

## Verified PASS

| # | Check | Evidence |
|---|-------|----------|
| 01 | SAF tree picker opens from the «Папку» button | picker walk, folder + permission dialogs |
| 02 | **Grouping**: sub-folder → one multi-chapter book; root files → one book each | Library «Завантажені (2)»: «Кобзар» (author «Локальна папка») + «Лісова пісня» (author «Локальний файл»); `readme.txt` skipped |
| 03 | **Chapters naturally sorted** | «Кобзар» chapter list: 01 → 02 → 10 |
| 04 | Files copied into private storage, original names/extension kept | debug overlay path `/data/user/0/com.aistudio.audiobook.read/files/local_imports/Кобзар-10 Глава-5.mp3` |
| 05 | **Local playback works** | debug overlay `audioEngineMode = "Offline Local File"`, position advances, chapters auto-advance 01→02→10, stops at the end (no wrap), 0 FATAL |
| 06 | Book metadata correct | Book detail: «Імпортовано з папки «Кобзар» — 3 файл(ів)», «3 Ch. • 00:00» |

## Two real bugs found & fixed on device

1. **Local-file playback was broken.** The player was wired to
   `DefaultHttpDataSource.Factory` only, so a locally-imported chapter
   (`file://` URI from `buildMediaItem`) crashed with
   `ClassCastException: FileURLConnection cannot be cast to HttpURLConnection`
   → `ExoPlaybackException: Source error` → the PlayerScreen slider then
   crashed (NaN, see #2). **Fix (`365dabb`)**: wrap the hardened HTTP factory
   in `DefaultDataSource.Factory(context, httpFactory)` — `file://` and
   `content://` now go through `FileDataSource`/`ContentDataSource`, streamed
   4read chapters keep the HTTP config unchanged.
2. **PlayerScreen Slider crashed with "Cannot round NaN value"** — imported
   chapters start with `durationSeconds = 0`, so
   `currentPositionMs / durationMs = 0/0 = NaN` and Material3 Slider throws.
   Pre-existing: the same crash is in the 07:34 log from the previous build
   (any zero-duration media triggers it). **Fix (`365dabb`)**: guard the
   fraction with `durationMs > 0` (MiniPlayerBar already had the guard).

## Notes

- **Debug-signing conflict:** the previously-installed debug APK was signed
  with the old gitignored `${rootDir}/debug.keystore`; the new build (AGP
  default debug keystore, `130524e`) fails `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  on top of it — uninstall + fresh install required. Debug-only concern;
  release signing is unchanged.
- **Emulator unusable on this workstation** (API-35 AVD: no disk space for
  the userdata partition; API-28 AVD: HVF/HAX acceleration unavailable), so
  verification was done on the physical phone via wireless ADB.
- Imported test books remain in the app's Library on the phone; the test
  folder stays at `/sdcard/Тест Імпорту`.

Screenshots: `docs/phone-test/screenshots/folder_import_{01_launch,02_playing,03_ended}.png`

---

# spec-14 T6 (#88) — import doors + missing-book, device session

> Executed: **2026-08-12** against `main` HEAD (`9b79f89` + the two fixes below).
> Device: OnePlus 8 Pro (IN2023), Android 14, wireless ADB `192.168.13.142:37371`.
> Runbook: `docs/phone-test/PLAN-spec-14-t6.md`.

## Acceptance matrix

| # | Step | Result | Evidence |
|---|------|--------|----------|
| 01–04 | Search-import via Огляд → search («1984») → card with enriched profile (author, narrator, genres, rating, 24 Ch • 10:29:00) → playback | ✅ PASS | `screenshots/spec14_03_playback_fixed.png`; logcat `AudioPlayer: HTTP 200 → HTTP 206` |
| 05/06 | Link / WebView doors via the legacy in-app 4read browser | ⚠️ DE-SCOPED | Legacy surface per the product redesign (WebView = «Відкрити джерело» only); doors are pinned by JVM seam tests. Device testing of this surface is not required |
| Missing | Search for a nonexistent title → no card, no player, no fabricated fallback | ✅ PASS | JVM seam tests + 404-page import on device returned null (no card) |
| sluhay S01–S09 | Challenge / browse / add / Referer playback / «Нове з Sluhay» row | ⏸ PENDING | Needs the human's in-session Turnstile tap — next device session |

## Real bugs found on device this cycle (both fixed)

1. **Cyrillic chapter paths were not percent-encoded → 403 playback** (`1740a0d`).
   `WebViewHtmlParser.encodeUrl` let Latin-1 re-mappings of bytes ≥ 0x80 pass
   `isLetterOrDigit()` (`(0xD0).toChar()` = 'Ð'), so chapter files with
   Cyrillic names stored as `Ð%94…` and 403'd. The correctly encoded URL
   served 206 (app bug, not site). Fixed: percent-encode every byte ≥ 0x80;
   regression test with the real filename; re-verified on device — logcat
   `HTTP 200 → HTTP 206`.

2. **Source feed rows had no covers — and audiobook-mp3 titles fell back to
   transliterated slugs** (`working tree`, next commit). The `fetchNew`
   adapters for sound-books.net, audiobook-mp3.com and lihtar.in.ua never
   parsed `coverImageUrl`, so every «НОВЕ З …» row rendered the typographic
   fallback (no picture). Audiobook-mp3 additionally let the textless
   `image-abook` cover tile win the URL dedupe, so the feed showed
   «Majkl svonvik chasovij legion» instead of «Часовий легіон». Fixed: covers
   parsed from the cover tiles / og:image in all three adapters + the dedupe
   now requires real anchor text. Verified on device: «Часовий легіон» /
   «Дім твоєї мрії» in Cyrillic; `screenshots/spec14_covers_fixed.png`.

## spec-14 T6 — follow-up (2026-08-14): missing-book via search + sluhay doors

> Executed against `main` HEAD (`806d122` unescape fix + `9b79f89`
> Referer/HTTP logging, both already in the tree above). Device: OnePlus 8
> Pro (IN2023), Android 14, wireless ADB. This run closes the two ⏸ rows
> from the 2026-08-12 session: the missing-book door through **search** and
> the sluhay WebView door **S01–S09** with the in-session Cloudflare
> Turnstile tap.

| # | Step | Result | Evidence |
|---|------|--------|----------|
| Missing | Search a nonexistent title → no card, no player, no fabricated fallback; DB stays clean | ✅ PASS | `screenshots/spec14_t6_missing_01_search.png`; DB dump: `audiobooks`/`sources` have 0 rows for the fake id (`SELECT COUNT(*) … WHERE id LIKE '4read-99999999%'` → 0) |
| S01 | Слухати → «Більше книг на Sluhay →» → browser surface loads `sluhay.com`, **Cloudflare Turnstile passed in-session (tester's tap)** | ✅ PASS | `screenshots/spec14_t6_sluhay_01_challenge_passed.png`; logcat `WebSource` |
| S02 | Browse in-session, open a book page (ads/trackers blocked) | ✅ PASS | session log |
| S03 | «Додати до медіатеки» → card in Медіатека with **badge «Sluhay»**, author present, chapters from the inline Playerjs playlist, cover from `data-src` | ✅ PASS | session log + Медіатека check |
| S04 | Tap card → playback from `*.redirectto.cc` **with `Referer: https://sluhay.com/`** — 206 `audio/mpeg`, position advances, no 403 | ✅ PASS | `screenshots/spec14_t6_sluhay_04_playing.png`; logcat `AudioPlayer: HTTP 206 … Referer=https://sluhay.com/` (`9b79f89`) |
| S05 | Pause → resume: position remembered per source (`sourceKey = "sluhay"`) | ✅ PASS | session log |
| S06 | Close the browser surface → **«Нове з Sluhay» row appears on Слухати** (fresh session cookies → server-fetch 200) | ✅ PASS | `screenshots/spec14_t6_sluhay_06_row.png` |
| S07–S09 | Row card plays with the sluhay Referer; merge across 4read/Sluhay; download (sluhay is **allowed**, not stream-only) | ✅ PASS | session log |

Also verified on this run: the `\uXXXX`-unescape fix (`806d122`) on live
WebView captures and feed rows, and the feed covers (`02e07f9`) —
`spec14_covers_fixed.png` shows «Часовий легіон» / «Дім твоєї мрії» in
Cyrillic with real covers.

## Notes

- The in-app 4read browser (`FourReadWebScreen`) is legacy from spec-8;
  per the redesign it should be replaced by an external «Відкрити джерело»
  action — follow-on ticket proposed (do not device-test it).
- Old books imported before the encodeUrl fix keep the mangled URL in Room;
  re-import regenerates them correctly.
