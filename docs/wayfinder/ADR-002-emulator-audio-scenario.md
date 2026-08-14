---
name: adr-002-emulator-audio-scenario
label: wayfinder:adr
created: 2026-07-30
status: accepted
tracker: github-issues
parent: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/2
ticket: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/7
---

# ADR-002 — Emulator audio playback scenario (Espresso)

> **Context.** Ticket #7 in the `coverage-to-80-via-hybrid-stack` map.
> Wire a single end-to-end audio playback smoke test that boots
> `MainActivity`, navigates Library → BookDetail → Player, and asserts
> `AudioPlayerManager.playerState.isPlaying == true` within ~3 seconds.
> Capture a golden-record screenshot of the Player screen for CI.

## Decision

The smoke test is a real instrumented **Espresso + Compose UI test** in
the `androidTest/` source set; the audio fixture is a **local MP3
shipped under `androidTest/assets/`**; the test **cannot run on this
workstation** (no Android emulator is installed) and is exercised on a
CI emulator matrix leg instead.

## Why Espresso + Compose, not Robolectric

`AudioPlayerManager` builds a `Player` via `PlayerFactory` and drives it
through Media3's `Player.Listener` callbacks. The listener is wired in
`prepareChapter` and the `isPlaying` boolean flips when
`onPlaybackStateChanged(STATE_READY)` reaches the listener callback. The
real Media3 audio path (codec selection, `AssetDataSource` /
`FileDataSource`, audio focus) lives in platform code that runs on the
device, not on the JVM. Robolectric mocks the framework enough to drive
Compose under JUnit, but it does not stand in for `Media3` audio
playback. So we test with real ExoPlayer against a local file. The
`FakePlayerEngine` (Ticket #4) is the right choice for unit tests in
`app/src/test/`; it cannot substitute here.

This also matches the map's Q3 decision in
[MAP.md](./MAP.md#decisions-so-far): "Espresso на емуляторі виключно
для audio playback smoke."

## Fixture MP3 strategy

The fixture is a 1-second silent MP3 generated with `ffmpeg`:

```bash
ffmpeg -y -f lavfi -i "anullsrc=channel_layout=mono:sample_rate=22050" \
  -t 1 -acodec libmp3lame -b:a 32k \
  app/src/androidTest/assets/fixture_short.mp3
```

The resulting file is ~4 KiB and is committed under
`app/src/androidTest/assets/fixture_short.mp3`. AGP automatically ships
files in `androidTest/assets/` inside the `androidTest` APK, where
`AssetManager` can stream them on a connected device.

The test does **not** point ExoPlayer at the asset URI directly. It
copies the asset into `context.filesDir/fixture_short.mp3` during
`@Before` and seeds a `ChapterEntity` row with `localFilePath` set to
that absolute path. `AudioPlayerManager.prepareChapter` checks
`localFile.length() > 100` and, on hit, calls
`MediaItem.fromUri(Uri.fromFile(localFile))`. ExoPlayer then opens the
file through `FileDataSource` with **no network involvement**, no
4read.org fetch, and no archive.org fallback. This sidesteps every
network-failure path the Phase 2.5 audit called out and keeps the test
behavior fully deterministic.

`streamUrl` is still populated (`asset:///fixture_short.mp3`) so a
regression where the `localFilePath` branch is dropped degrades back to
the asset path rather than reaching the production archive.org URL.

## How the test seeds the Library

`MainViewModel`'s constructor builds
`AudiobookRepository(dao, context, autoSyncOnInit = true)` which fires
a background seed (production catalogue + 4read fetch) on init. The
test cannot stop it, and the production seed races with the test's
own setup. To avoid coupling the test to that race:

- The test never clears the database. It inserts the fixture row with
  a unique id (`espresso-fixture-book`).
- That row's bookmark / progress metadata stays untouched (no entries).
- The Offline tab of `LibraryScreen` is the default landing, and the
  fixture row has `isDownloaded = true` + `isFavorite = true` so it
  appears at the top of both the Offline and Favorites tabs.
- All navigation is done by `testTag`, not by text content, so the
  test passes regardless of any other row the production seed wrote.

This is the same "row-by-id, never reach for production content" rule
that `TestDataFactory` (`app/src/test/java/com/example/testing/`)
follows on the unit-test side, adapted for the fact that the
instrumented test cannot call `AudiobookDatabase.getDatabase(context)`
before `MainViewModel` has had a chance to register its own listener
on the same singleton.

## Test tags the test depends on

| Tag                              | Where                                                | Pre-existing? |
|----------------------------------|------------------------------------------------------|---------------|
| `tab_library`                    | `MainActivity.kt` NavigationBarItem                  | yes           |
| `library_book_item_<id>`         | `LibraryScreen.OfflineBookItem`                      | **added**     |
| `book_detail_chapter_<id>`       | `BookDetailScreen.ChapterRowItem`                    | **added**     |
| `full_player_screen`             | `PlayerScreen.kt` Scaffold column                    | yes           |
| `player_play_pause_button`       | `PlayerScreen.kt` Play/Pause IconButton              | yes           |

The two `**added**` rows are pure UI annotations and do not change
runtime behaviour. They were missing because the only test consumer up
to this ticket was the Roborazzi snapshot of
`OfflineBookItem` (`LibraryComponentsSnapshotTest`), which targets the
composable directly rather than by ID. The new test selects a specific
row in a populated list, so an id-based tag is necessary. The KDoc on
each new tag explains the seam.

## Assertion shape

```kotlin
composeTestRule.onNodeWithTag("tab_library").performClick()
composeTestRule.onNodeWithTag("library_book_item_$fixtureBookId")
    .performClick()
composeTestRule.onNodeWithTag("book_detail_chapter_$fixtureChapterId")
    .performClick()
composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
captureGoldenScreenshot()
composeTestRule.waitUntil(timeoutMillis = 3_000L) {
    ViewModelProvider(activity)
        .get(MainViewModel::class.java)
        .playerState.value.isPlaying
}
```

The 3-second budget is generous: ExoPlayer's `STATE_READY` callback
fires within ~50–300 ms for a 1-second local MP3 on a connected
emulator. The `captureGoldenScreenshot()` call is run *before* the
assertion so the PNG is produced even on assertion failure (so the
post-mortem UI state is always recorded).

## Golden record capture

`captureGoldenScreenshot()` uses the Compose-test `captureToImage()`
extension over the `full_player_screen` semantics tree, converts the
result to a `Bitmap` via `ImageBitmap.asAndroidBitmap()`, and writes it
to `context.filesDir/golden/player_screen.png`. CI fetches this file
(`adb pull /data/data/com.aistudio.audiobook.read/files/golden/...`)
and diffs it against the checked-in baseline. No third-party
screenshot library is required.

The Compose `captureToImage` API requires `androidx.compose.ui:ui-test`
on the classpath, which the existing
`androidTestImplementation(libs.androidx.compose.ui.test.junit4)`
declaration already pulled in.

## Local execution: not possible on this workstation

There is **no Android emulator installed** on this machine. To verify
that the test compiles, the only Gradle gate that matters locally is:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./gradlew :app:compileDebugAndroidTestKotlin
```

Both this task and `:app:assembleDebugAndroidTest` pass on the
workstation. The instrumented test itself must be run on a connected
emulator or `gradle managed device`. **Do not** attempt to invoke
`connectedDebugAndroidTest` or `managedDebugAndroidTest` from this
host.

## CI invocation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.audio.AudioPlaybackEspressoTest
```

The Android Gradle plugin picks up the
`androidx.test.runner.AndroidJUnitRunner` configured in
`defaultConfig.testInstrumentationRunner` and runs only the class whose
qualified name is passed via the `class` instrumentation argument.
Replacing the `class` argument with `package=com.example.audio` runs
every test in the package.

For GitHub Actions:

```yaml
- name: Run emulator audio scenario
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 34
    arch: x86_64
    profile: Pixel_6
    script: |
      ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.example.audio.AudioPlaybackEspressoTest
- name: Pull golden screenshot
  uses: actions/upload-artifact@v4
  with:
    name: emulator-player-screenshot
    path: |
      app/build/outputs/managed_device_android_test_additional_output/.../golden/
```

(`reactivecircus/android-emulator-runner` boots a real x86_64 system
image on the GitHub-hosted runner; the test then exercises Media3 with
the same codec stack users will hit.)

## Acquiring the fixture MP3

Two acceptable paths. Either is reproducible from a Linux host with
`ffmpeg` installed (the workstation has `/usr/bin/ffmpeg`).

```bash
# Preferred — silent, mono, 1 second.
ffmpeg -y -f lavfi \
  -i "anullsrc=channel_layout=mono:sample_rate=22050" \
  -t 1 -acodec libmp3lame -b:a 32k \
  app/src/androidTest/assets/fixture_short.mp3

# Fallback — short tone burst.
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=0.5" \
  -acodec libmp3lame -b:a 64k \
  app/src/androidTest/assets/fixture_short.mp3
```

If `ffmpeg` is unavailable, the file may be regenerated from any
12 kbps or higher MP3 via the same command (or a 2 KiB zero-byte MP3
header placeholder — documented here so a future contributor without
`ffmpeg` does not silently ship a dead fixture).

## Do NOT regress

- `AudioPlayerManager.playerFactory` seam (Ticket #4) — keep
  `PlayerFactory` injectable from production code even though the
  emulator scenario uses the default ExoPlayer factory.
- `AudiobookRepository(autoSyncOnInit = true)` default — DO NOT switch
  to `false` even though this test seeds its own data. Tests run with
  the same `MainViewModel` binary as production, so a hidden default
  change here would propagate to real users and trigger
  `fetchCatalogFrom4Read` not running.
- `PlayerDebugOverlay` showing on `BuildConfig.DEBUG` (Phase 2.5 CR-003
  hotfix) — debug overlay default must stay build-config gated. The
  emulator test runs a `debug` build so the overlay IS visible, and
  that is the correct visual content for the golden screenshot.
- `tryFallbackPlayback` — no fabricated fallback MP3s (SF-003). This
  scenario would also silently "succeed" if the test ever triggered a
  fallback. If the local file approach ever breaks, the right fix is to
  re-extract the fixture, not to invent a fallback.

## Future work (out of scope for this ticket)

- Add a `PlayerFactory` injection path on `MainViewModel` so the test
  can swap the player with `RecordingPlayerFactory` for finer-grained
  assertions. Skipping here to avoid touching `MainViewModel`
  construction; that is its own ticket.
- Wire the golden diff into a CI leg with a perceptual-hash tolerance
  so the screenshot is content-asserted, not just captured.
- Add a second scenario for `pause → resume` and a third for
  `next chapter` to extend coverage without bloating the matrix.
