package com.slukhayka.audiobooks.audio

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end audio playback smoke test for an Android emulator.
 *
 * Ticket: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/7
 *
 * What this exercises:
 *  1. The Library tab renders an `OfflineBookItem` whose audio chapter we
 *     deterministically seed in this test's `@Before`.
 *  2. Tapping that book navigates to `BookDetailScreen`.
 *  3. Tapping a chapter row triggers `playAudiobook(...)` and shows the
 *     full-screen `PlayerScreen` overlay.
 *  4. `AudioPlayerManager.playerState.isPlaying` flips to `true` within a
 *     ~3 second budget once the chapter has been prepared.
 *  5. A golden-record screenshot of the Player screen is captured to the
 *     test app's filesDir under `golden/player_screen.png` for CI to fetch.
 *
 * Why this is an instrumented Espresso test, not a Robolectric test:
 *  - `AudioPlayerManager` builds a real ExoPlayer (`Player` interface) and
 *    drives it via Media3's `Player.Listener`. Media3 audio decoding is
 *    implemented by platform code on the device; a JVM unit test cannot
 *    actually emit samples or read the playback state through the same
 *    pathways.
 *  - The fixture MP3 ships at `androidTest/assets/fixture_short.mp3` so the
 *    test never depends on the network or on 4read.org reachability.
 *
 * Why the deterministic seed in `@Before` matters:
 *  - `MainViewModel`'s composition root performs an explicit catalogue sync on
 *    `init` that syncs the catalogue from 4read.org and writes
 *    hardcoded rows pointing at archive.org MP3s. Reusing those rows would
 *    (a) couple the test to network reachability, (b) fail the
 *    `assert playerState.isPlaying == true within 3 s` assertion once the
 *    archive.org fetch stalls. The fixture row shipped here sidesteps both.
 *  - We copy the asset MP3 into the test app's `filesDir` and point the
 *    seeded Source TRACK's `localFilePath` at that file (ADR-0007: the
 *    physical playback data lives on the tracks). `AudioPlayerManager` then
 *    picks the local-file branch in `prepareChapter` and never makes a
 *    network call.
 *
 * Test tags this test depends on:
 *  - `tab_library`              — Library NavigationBarItem (MainActivity)
 *  - `library_book_item_<id>`   — added by ticket #7 in LibraryScreen
 *                                 `OfflineBookItem` (file:line noted in PR)
 *  - `book_detail_chapter_<id>` — added by ticket #7 in BookDetailScreen
 *                                 `ChapterRowItem`
 *  - `full_player_screen`       — PlayerScreen scaffold column
 *  - `player_play_pause_button` — PlayerScreen play/pause IconButton
 *
 * Local execution: requires an Android emulator (no emulator is installed on
 * this workstation). CI invocation is documented in
 * `docs/wayfinder/ADR-002-emulator-audio-scenario.md`.
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackEspressoTest {

    // Declared BEFORE the compose rule so the grant happens before
    // MainActivity launches. A fresh install shows the Android 13+
    // POST_NOTIFICATIONS dialog on first launch, and the system window races
    // the Compose root registration — observed on-device as an intermittent
    // "No compose hierarchies found" failure on a cold start.
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val fixtureBookId: String = "espresso-fixture-book"
    private val fixtureChapterId: String = "espresso-fixture-chapter-1"
    private val fixtureBookTitle: String = "Emulator Fixture Audiobook"
    private val goldenRelPath: String = "golden/player_screen.png"

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Copy the bundled silent MP3 into `filesDir` (ExoPlayer cannot read
     * directly from `assets/` for arbitrary content URIs without an
     * `AssetDataSource` registered, but reading a `file://` URI is always
     * supported). Then deterministically seed the Room database with one
     * downloaded book + one chapter pointing at the local fixture file.
     */
    @Before
    fun seedDeterministicLibrary() {
        val target = File(app.filesDir, "fixture_short.mp3")
        // Always re-copy the fixture from the test APK's assets: the asset is
        // the source of truth, and a stale on-device copy (e.g. from a build
        // whose fixture was corrupted) must not survive an upgrade. The asset
        // lives in the *test* APK, not the app APK, so it must be read through
        // the instrumentation context's AssetManager
        // (`ApplicationProvider.getApplicationContext()` returns the target
        // app, whose assets do NOT include test-only files).
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("fixture_short.mp3")
            .use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }

        runBlocking {
            val dao = AudiobookDatabase.getDatabase(app).audiobookDao()
            // Re-runs on a device that already played the fixture would leave a
            // playback-progress row behind, which turns the Listen tab's
            // "Нещодавно слухали" section on for the fixture book and changes
            // the UI the test asserts against. Clean the fixture's session data
            // so every run starts from the same state.
            dao.deletePlaybackProgressForBook(fixtureBookId)
            val book = AudiobookEntity(
                id = fixtureBookId,
                title = fixtureBookTitle,
                author = "4read Test Harness",
                narrator = "Emulator Test Narrator",
                description = "Deterministic fixture row seeded by " +
                    "AudioPlaybackEspressoTest; not user content.",
                coverDrawableRes = 0,
                coverImageUrl = null,
                genre = "Test",
                sourceUrl = "https://fixtures.4read.invalid/$fixtureBookId.html",
                isDownloaded = true,
                totalDurationSeconds = 1L,
                totalChapters = 1,
                rating = 5.0f
            ).also {
                // ADR-0009: downloadProgress/isFavorite are projections read
                // from library_entries, not constructor columns.
                it.downloadProgress = 1.0f
                it.isFavorite = true
            }
            // ADR-0007: one domain Edition owns the logical chapter list; the
            // 4read Source gets its physical tracks (1:1 by index). The local
            // copy and download flag live on the TRACK row, never the chapter.
            val editionId = com.slukhayka.audiobooks.data.EditionId.forBook("", fixtureBookId)
            val chapter = ChapterEntity(
                id = fixtureChapterId,
                bookId = fixtureBookId,
                editionId = editionId,
                chapterIndex = 0,
                title = "Глава 01: Silent Fixture",
                durationSeconds = 1L
            )
            val source = com.slukhayka.audiobooks.data.db.SourceEntity(
                id = "4read-$editionId",
                bookId = fixtureBookId,
                editionId = editionId,
                type = "4read",
                url = "https://fixtures.4read.invalid/$fixtureBookId.html",
                streamOnly = false
            )
            val track = com.slukhayka.audiobooks.data.db.SourceTrackEntity(
                id = "4read-$editionId-tr-1",
                sourceId = source.id,
                trackIndex = 0,
                // Keeping `url` to a fixed, deterministic value so that a
                // future regression where `localFilePath` is dropped falls
                // back to this placeholder instead of leaking the production
                // archive.org URL into the test.
                url = "asset:///fixture_short.mp3",
                localFilePath = target.absolutePath,
                isDownloaded = true
            )
            dao.insertAudiobooks(listOf(book))
            dao.insertEdition(
                com.slukhayka.audiobooks.data.db.EditionEntity(
                    id = editionId,
                    workId = fixtureBookId,
                    narrator = book.narrator,
                    totalChapters = 1,
                    totalDurationSeconds = 1L
                )
            )
            dao.insertChapters(listOf(chapter))
            dao.insertSources(listOf(source))
            dao.insertTracks(listOf(track))
        }
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun taps_library_to_player_and_asserts_isPlaying_within_three_seconds() {
        // 1. Switch to the Library tab (default landing tab is Explore).
        // A fresh cold start on a physical device can outpace Compose's first
        // frame registration ("No compose hierarchies found"), so wait for
        // each node explicitly instead of assuming it exists after idle.
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("tab_library"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule.onNodeWithTag("tab_library").performClick()
        composeTestRule.waitForIdle()

        // 2. Tap the seeded fixture book; navigates into BookDetailScreen.
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("library_book_item_$fixtureBookId"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule
            .onNodeWithTag("library_book_item_$fixtureBookId")
            .performClick()
        composeTestRule.waitForIdle()

        // 3. Tap the chapter row; this fires `playAudiobook(...)` AND
        //    `setShowFullPlayer(true)` in `BookDetailScreen.kt`, which opens
        //    the PlayerScreen overlay (animated).
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("book_detail_chapter_$fixtureChapterId"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule
            .onNodeWithTag("book_detail_chapter_$fixtureChapterId")
            .performClick()
        composeTestRule.waitForIdle()

        // 4. Sanity: the Player scaffold is on screen.
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("full_player_screen"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule
            .onNodeWithTag("full_player_screen")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("player_play_pause_button")
            .assertIsDisplayed()

        // 5. Golden record: capture the Player root BEFORE the assertion so
        //    that the screenshot always shows the screen, regardless of
        //    whether the assertion succeeds. Encoding to PNG is ~10–20 ms for
        //    a single screen on a modern emulator and never blocks the audio
        //    pipeline.
        captureGoldenScreenshot()

        // 6. The hard assertion: AudioPlayerManager.playerState.isPlaying
        //    must be `true` within ~3 seconds of the chapter tap.
        composeTestRule.waitUntil(timeoutMillis = PLAY_TIMEOUT_MS) {
            currentIsPlaying()
        }
    }

    /**
     * Reads [MainViewModel.playerState.value.isPlaying] from the activity's
     * ViewModelStore. `StateFlow.value` is non-blocking and thread-safe; we
     * only need to surface it onto the test thread for the [waitUntil] guard.
     */
    private fun currentIsPlaying(): Boolean {
        val activity = composeTestRule.activity
        val viewModel = ViewModelProvider(activity)
            .get(MainViewModel::class.java)
        return viewModel.playerState.value.isPlaying
    }

    /**
     * Captures the root PlayerScreen semantics tree via the Compose-test API
     * and writes it to `filesDir/golden/player_screen.png`. CI fetches this
     * artifact (e.g. via `adb pull`) and treats it as the golden record; an
     * out-of-band diff step compares it against a checked-in baseline.
     *
     * Failures here never cause the test to fail: `captureToImage()` and
     * bitmap compression are best-effort, the audio assertion is the real
     * gate.
     */
    private fun captureGoldenScreenshot() {
        runCatching {
            val image = composeTestRule
                .onAllNodesWithTag("full_player_screen")
                .onFirst()
                .captureToImage()
                .asAndroidBitmap()
            val target = File(app.filesDir, goldenRelPath)
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    companion object {
        /** Budget for `playerState.isPlaying == true` after chapter tap. */
        private const val PLAY_TIMEOUT_MS: Long = 3_000L

        /** Budget for navigation nodes to appear after launch/navigation. */
        private const val NAV_TIMEOUT_MS: Long = 15_000L
    }
}
