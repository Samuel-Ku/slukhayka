package com.slukhayka.audiobooks.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.slukhayka.audiobooks.ui.adaptive.rememberIsLandscapePhoneWindow
import com.slukhayka.audiobooks.BuildConfig
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookmarkDeleteConfirmation
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.player.withLiveDurationFacts
import com.slukhayka.audiobooks.player.SeekSession
import com.slukhayka.audiobooks.player.SleepTimerNotice
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.CastButton
import com.slukhayka.audiobooks.ui.components.PlayerDebugOverlay
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.components.SpeedSheet
import com.slukhayka.audiobooks.ui.components.formatSpeed
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.components.formatSpeedForSpeech
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.data.listening.effectiveChapterDurations
import com.slukhayka.audiobooks.data.listening.knownBookTotalSeconds
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkColors
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import com.slukhayka.audiobooks.ui.theme.PaperInkType
import com.slukhayka.audiobooks.ui.theme.TabularTimerStyle

/** Values shared by the visual progress treatment and its unit tests. */
data class PlayerProgressUi(
    val chapterFraction: Float,
    val bookFraction: Float,
    val bookPositionSeconds: Long,
    val bookDurationSeconds: Long,
    val chapterMarkers: List<Float>,
    val bookmarkMarkers: List<Float>
)

/**
 * F32 — the book is read as three parts on the read-only ruler; the chapter
 * slider is the ONE seek. Two dividers make the three parts.
 */
internal val BOOK_PART_DIVIDERS: List<Float> = listOf(1f / 3f, 2f / 3f)

data class BookSeekTarget(val chapterIndex: Int, val positionMs: Long)

enum class PlayerQuickTool {
    Speed,
    Timer,
    Bookmark,
    Chapters,
    Bookmarks
}



/**
 * #1187 — the CHAPTER/BOOK boundary of the player's two rulers.
 *
 * [effectiveChapterDurations] answers POSITIONS: it places every chapter on the
 * timeline, filling unmeasured ones with a share of a known book total, and the
 * playing chapter's freshly measured length overrides its row. That is
 * legitimate for the chapter ruler — where a chapter starts, where inside it
 * the listener is, where its marker sits.
 *
 * The BOOK's own length is a different question, and the player used to answer
 * it with the same sum: `effectiveDurations.sum()`. For a local book whose
 * chapters only get a duration by being played, that sum is the count of what
 * has been heard so far — three 300-second chapters with the first played and
 * the third only 60 s in made the whole book 360 seconds, and a listener 60 s
 * into that third chapter sat exactly at the fabricated "end"
 * (`bookFraction == 1f`) while two thirds of the book had never been heard.
 * [knownBookTotalSeconds] answers instead: the site total when there is one, a
 * sum only when EVERY chapter is measured, and 0 ("unknown") otherwise.
 *
 * So when the book's total is unknown this returns `null`: the book seek does
 * not pretend to know where the book's end is. In the UI this is unreachable by
 * construction — the book slider is `enabled = progress.bookDurationSeconds > 0L`
 * and [calculatePlayerProgress] reports the same unknown 0 — and it is here so
 * the rule holds at the door too, not only in the view that happens to close it.
 */
fun calculateBookSeekTarget(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    fraction: Float,
    bookTotalDurationSeconds: Long = 0L
): BookSeekTarget? {
    if (chapters.isEmpty()) return null
    // The chapter ruler: chapters are placed on the timeline by the best
    // duration known for each (spread over the site total when it exists).
    val durations = effectiveChapterDurations(chapters, currentChapterIndex, currentChapterDurationMs, bookTotalDurationSeconds)
    // The book ruler: the honest total, never the partial sum (#1187).
    val totalSeconds = knownBookTotalSeconds(chapters, bookTotalDurationSeconds)
    if (totalSeconds <= 0L) return null

    val targetSeconds = (fraction.coerceIn(0f, 1f) * totalSeconds)
        .toLong()
        .coerceIn(0L, totalSeconds)
    var elapsed = 0L
    durations.forEachIndexed { index, duration ->
        if (targetSeconds < elapsed + duration || index == durations.lastIndex) {
            return BookSeekTarget(index, (targetSeconds - elapsed).coerceIn(0L, duration) * 1_000L)
        }
        elapsed += duration
    }
    return null
}

/**
 * #1707 — the two marker lists of the book ruler.
 *
 * They are a property of the chapter layout and the bookmarks, NOT of the
 * ticking playback position: the chapter starts and bookmark fractions stay
 * put while the listener moves inside the book. The player therefore remembers
 * this result (see [rememberPlayerMarkers]) instead of rebuilding both lists
 * every second.
 */
data class PlayerMarkers(
    val chapterMarkers: List<Float>,
    val bookmarkMarkers: List<Float>
)

/**
 * #1707 — computes the marker lists from the same inputs
 * [calculatePlayerProgress] uses.
 *
 * Position is deliberately absent: the caller may cache the result for as long
 * as (chapters, current chapter, its measured duration, bookmarks, book total)
 * do not change. Bookmarks are placed with ONE prefix-sum walk over the
 * chapters, so the computation is O(chapters + bookmarks) instead of the old
 * `take(i).sum()` per bookmark (O(bookmarks × chapters)).
 */
fun calculatePlayerMarkers(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    bookmarks: List<BookmarkEntity>,
    bookTotalDurationSeconds: Long = 0L,
    // #1733 — the caller may hand in the chapter ruler it already remembered
    // (see [rememberEffectiveChapterDurations]). Null keeps the one-shot
    // contract for every other caller.
    effectiveDurations: List<Long>? = null
): PlayerMarkers {
    if (chapters.isEmpty()) return PlayerMarkers(emptyList(), emptyList())
    // A supplied list is trusted only for THIS chapter layout: a stale list of
    // another size is ignored, never used to place a different book.
    val chapterDurations = effectiveDurations
        ?.takeIf { it.size == chapters.size }
        ?: effectiveChapterDurations(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex.coerceIn(chapters.indices),
            currentChapterDurationMs = currentChapterDurationMs,
            bookTotalDurationSeconds = bookTotalDurationSeconds
        )
    val totalSeconds = knownBookTotalSeconds(chapters, bookTotalDurationSeconds)
    return markersFor(chapterDurations, bookmarks, totalSeconds)
}

/**
 * Shared marker math: [calculatePlayerProgress] calls it when the caller has
 * no remembered [PlayerMarkers], and [calculatePlayerMarkers] is the cached
 * door. [effectiveDurations] has one entry per chapter.
 */
private fun markersFor(
    effectiveDurations: List<Long>,
    bookmarks: List<BookmarkEntity>,
    totalSeconds: Long
): PlayerMarkers {
    // Markers are BOOK-level, so they divide by the book's own length: an
    // unknown total falls to the evenly-spaced shell (an honest "no scale").
    val chapterMarkers = if (totalSeconds > 0L) {
        var elapsed = 0L
        effectiveDurations.dropLast(1).map { duration ->
            elapsed += duration
            elapsed.toFloat() / totalSeconds
        }
    } else {
        (1 until effectiveDurations.size).map { it.toFloat() / effectiveDurations.size }
    }

    // Bookmark fractions need the same honest scale; without one they have no
    // place on the ruler (dropped, as before).
    if (totalSeconds <= 0L || bookmarks.isEmpty()) {
        return PlayerMarkers(chapterMarkers, emptyList())
    }
    val prefixSeconds = LongArray(effectiveDurations.size + 1)
    effectiveDurations.forEachIndexed { index, duration ->
        prefixSeconds[index + 1] = prefixSeconds[index] + duration
    }
    val bookmarkMarkers = bookmarks.mapNotNull { bookmark ->
        if (bookmark.chapterIndex !in effectiveDurations.indices) return@mapNotNull null
        val before = prefixSeconds[bookmark.chapterIndex]
        val within = bookmark.timestampSeconds.coerceIn(0L, effectiveDurations[bookmark.chapterIndex])
        ((before + within).toFloat() / totalSeconds).coerceIn(0f, 1f)
    }
    return PlayerMarkers(chapterMarkers, bookmarkMarkers)
}

/**
 * Converts chapter-relative playback and bookmarks into one book timeline.
 * Zero-duration imports degrade to evenly-spaced chapter markers rather than
 * producing NaN values (the same class of crash guarded against by the old UI).
 *
 * #1187 — two rulers, deliberately, and this is where the defect lived.
 *
 * The CHAPTER ruler ([effectiveDurations] when the caller remembered it,
 * otherwise recomputed) stays as it is: the playing chapter's measured length,
 * the position inside it, [chapterFraction], and where the chapter/bookmark
 * markers sit are all positions on that timeline. A chapter measured only by
 * being played is placed with what is known about it — that is honest, and it is
 * what keeps the current chapter's own progress working.
 *
 * `bookDurationSeconds` is the BOOK ruler, and it may NOT be the same sum. For
 * a local book (no site total) whose chapters are only measured one by one as
 * they are played, the chapter ruler's sum is the count of what has been
 * heard so far: three 300-second chapters with the first played and the third
 * only 60 s in summed to 360, and the listener 60 s into that third chapter read
 * as sitting at the end of the book (`bookFraction == 1f`) — the player said
 * "100 %" while the library card honestly said «Тривалість невідома». Two
 * screens, two answers, one book. The book ruler is [knownBookTotalSeconds]: the
 * site total when there is one, the chapters' sum only when EVERY one of them is
 * measured, and 0 ("unknown") otherwise.
 *
 * With an unknown total the book fraction stays 0f (the `else` branch) and the
 * UI shows the honest unknown state — the slider is disabled by
 * `bookDurationSeconds > 0L`, and `bookPositionSeconds` is simply not displayed
 * as a fraction of a length nothing has proven.
 */
fun calculatePlayerProgress(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentPositionMs: Long,
    currentChapterDurationMs: Long,
    bookmarks: List<BookmarkEntity>,
    bookTotalDurationSeconds: Long = 0L,
    // #1707 — the player passes the marker lists it already remembered for
    // these same inputs. Null keeps the one-shot contract for every other
    // caller; the marker math itself is [markersFor].
    markers: PlayerMarkers? = null,
    // #1733 — the chapter ruler the player already remembered. Null keeps the
    // one-shot contract for every other caller; a list of another size is
    // ignored so a stale cache cannot place this book's chapters.
    effectiveDurations: List<Long>? = null
): PlayerProgressUi {
    if (chapters.isEmpty()) {
        return PlayerProgressUi(0f, 0f, 0L, 0L, emptyList(), emptyList())
    }

    val selectedIndex = currentChapterIndex.coerceIn(chapters.indices)
    // Chapter ruler: positions only (see the KDoc above — this is the line that
    // must NOT be reused as the book's length).
    val chapterDurations = effectiveDurations
        ?.takeIf { it.size == chapters.size }
        ?: effectiveChapterDurations(chapters, selectedIndex, currentChapterDurationMs, bookTotalDurationSeconds)
    val currentDurationSeconds = chapterDurations[selectedIndex]
    // Book ruler: the honest total, 0 when no chapter sum proves one (#1187).
    val totalSeconds = knownBookTotalSeconds(chapters, bookTotalDurationSeconds)
    val positionInChapter = (currentPositionMs / 1000L).coerceIn(0L, currentDurationSeconds)
    val elapsedBefore = chapterDurations.take(selectedIndex).sum()
    val bookPosition = elapsedBefore + positionInChapter

    val chapterFraction = if (currentDurationSeconds > 0L) {
        positionInChapter.toFloat() / currentDurationSeconds
    } else 0f
    val bookFraction = if (totalSeconds > 0L) bookPosition.toFloat() / totalSeconds else 0f

    // Markers are BOOK-level, so they divide by the book's own length: an
    // unknown total falls to the evenly-spaced shell (an honest "no scale"),
    // and bookmarks have no fraction to sit at (null, dropped) — the same
    // refusal as the fraction above. The player hands its remembered lists in
    // (#1707); this call is the fallback for callers without that cache.
    val markerFractions = markers ?: markersFor(chapterDurations, bookmarks, totalSeconds)

    return PlayerProgressUi(
        chapterFraction = chapterFraction.coerceIn(0f, 1f),
        bookFraction = bookFraction.coerceIn(0f, 1f),
        bookPositionSeconds = bookPosition,
        bookDurationSeconds = totalSeconds,
        chapterMarkers = markerFractions.chapterMarkers,
        bookmarkMarkers = markerFractions.bookmarkMarkers
    )
}

/** The one predictable target for returning to the most recently created bookmark. */
fun lastCreatedBookmark(bookmarks: List<BookmarkEntity>): BookmarkEntity? =
    bookmarks.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

/**
 * #1707 — duration facts are merged from the player's chapter snapshot and the
 * live Room rows only when those inputs change.
 *
 * The old body remembered `withLiveDurationFacts` under `rawPlayerState` too,
 * so every 1 Hz position tick re-ran `associateBy` over every chapter and
 * rebuilt the mapped list, then usually threw both away (`changed == false`).
 * The merge itself does not depend on the position: this remembers it under
 * the snapshot book/chapters and the two live lists, then takes the ticking
 * playback facts from [rawPlayerState] — a copy only when the merge really
 * produced different facts.
 */
@Composable
internal fun rememberLiveDurationPlayerState(
    rawPlayerState: PlayerState,
    liveBook: AudiobookEntity?,
    liveChapters: List<ChapterEntity>
): PlayerState {
    val facts = remember(
        rawPlayerState.currentBook,
        rawPlayerState.chapters,
        liveBook,
        liveChapters
    ) {
        rawPlayerState.withLiveDurationFacts(liveBook, liveChapters)
    }
    return if (
        facts.currentBook === rawPlayerState.currentBook &&
        facts.chapters === rawPlayerState.chapters
    ) {
        rawPlayerState
    } else {
        // The live playback facts (position, duration, buffering…) ride the
        // raw state; only the merged display facts are taken from the cache.
        rawPlayerState.copy(currentBook = facts.currentBook, chapters = facts.chapters)
    }
}

/**
 * #1733 — the chapter ruler is position-independent too: [effectiveChapterDurations]
 * changes only with the chapter layout, the playing chapter's measured length
 * and the authoritative book total. Remembering it here keeps the per-tick
 * progress from rebuilding an O(chapters) list every second, and lets the
 * markers and the progress share one instance.
 */
@Composable
internal fun rememberEffectiveChapterDurations(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    bookTotalDurationSeconds: Long
): List<Long> = remember(
    chapters,
    currentChapterIndex,
    currentChapterDurationMs,
    bookTotalDurationSeconds
) {
    effectiveChapterDurations(
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        currentChapterDurationMs = currentChapterDurationMs,
        bookTotalDurationSeconds = bookTotalDurationSeconds
    )
}

/**
 * #1707 — the book-ruler markers are position-independent, so the player
 * remembers them instead of rebuilding them on every position tick.
 *
 * The current chapter and its measured duration are part of the key: the
 * chapter ruler overrides the playing chapter's length and spreads unknown
 * chapters around it, so switching a chapter may move the boundaries — that is
 * a chapter change, not a tick, and it must keep rendering the same markers as
 * before.
 *
 * #1733 — when the caller already remembered the chapter ruler
 * ([rememberEffectiveChapterDurations]), it passes it in so this stays a cache
 * fill instead of a second [effectiveChapterDurations].
 */
@Composable
internal fun rememberPlayerMarkers(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    bookmarks: List<BookmarkEntity>,
    bookTotalDurationSeconds: Long,
    // #1733 — the chapter ruler the player already remembered; null keeps the
    // one-shot contract for callers without it.
    effectiveDurations: List<Long>? = null
): PlayerMarkers = remember(
    chapters,
    currentChapterIndex,
    currentChapterDurationMs,
    bookmarks,
    bookTotalDurationSeconds,
    effectiveDurations
) {
    calculatePlayerMarkers(
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        currentChapterDurationMs = currentChapterDurationMs,
        bookmarks = bookmarks,
        bookTotalDurationSeconds = bookTotalDurationSeconds,
        effectiveDurations = effectiveDurations
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    // ADR-0008 batch 4 (#159): the screen receives the module it reads from
    // as a parameter, wired from the composition root — the injection idiom
    // settled by #154. Player state is read from the player manager directly
    // (already a public field); bookmark creation stays on the ViewModel.
    libraryEntries: LibraryEntries,
    onDismiss: () -> Unit,
    // Plan 2026-2027 — decision A: the player is ink by default. The listener
    // may turn the exception off in Settings; then the surface follows the app.
    alwaysInk: Boolean = true,
) {
    val rawPlayerState by viewModel.playerState.collectAsState()
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel.
    val allBookmarks by libraryEntries.allBookmarks.collectAsState(initial = emptyList())
    val openedBook = rawPlayerState.currentBook ?: return
    // #1647 — the player state's chapters are a SNAPSHOT installed at prepare
    // time. The background chapter-duration probe writes real durations into
    // Room while this screen stays open, so the display follows the live rows:
    // the lists (and the honest book ruler) must pick the measured values up
    // without switching a chapter or re-opening the book.
    // ADR-0008: a function-built Flow must be remembered, or every
    // recomposition (the player ticks its position ~1 Hz) would build a new
    // Flow and `collectAsState` would restart both Room subscriptions each
    // second. Same idiom as `BookDetailScreen`.
    val liveBook by remember(openedBook.id) {
        libraryEntries.observeBook(openedBook.id)
    }.collectAsState(initial = null)
    val liveChapters by remember(openedBook.id) {
        libraryEntries.observeChapters(openedBook.id)
    }.collectAsState(initial = emptyList())
    val playerState = rememberLiveDurationPlayerState(rawPlayerState, liveBook, liveChapters)
    val book = playerState.currentBook ?: return
    // #1647 — opening the player is the listener's own «measure these
    // chapters» door: fire the targeted, single-flight probe so a book whose
    // chapters were never measured does not wait for the Огляд sweep.
    LaunchedEffect(book.id) {
        runCatching { viewModel.chapterDurationProbe.probeBookNow(book.id) }
    }
    // ADR-0008: suspend module calls from user actions run on the composition
    // scope (same pattern as playerManager's call-through).
    val scope = rememberCoroutineScope()
    val bookmarks = remember(allBookmarks, book.id) { allBookmarks.filter { it.bookId == book.id } }
    val lastBookmarkTarget = remember(bookmarks) { lastCreatedBookmark(bookmarks) }
    val castReady = remember(playerState.currentStreamUrl) {
        runCatching { App.instance.castController.isCastAvailable() }.getOrDefault(false) &&
            playerState.currentStreamUrl.let { it.startsWith("https://") || it.startsWith("http://") }
    }

    // #1474 — the temporary «sum of one tap series»: built from the ACTUAL
    // position shift of each «−15 s / +30 s» tap (clamped by the chapter
    // start/end), reset by a drag or a chapter switch (a playback pause is NOT
    // a reset: a tap after the idle window simply starts a new series), and
    // announced ONCE when the series ends instead of on every tap.
    val seekSumMs = remember { mutableLongStateOf(0L) }
    val seekLastTapAt = remember { mutableLongStateOf(0L) }
    val seekLastChapter = remember { mutableIntStateOf(-1) }
    val seekSumVisible = remember { mutableStateOf(false) }
    val seekSumVersion = remember { mutableIntStateOf(0) }
    val seekSumContext = androidx.compose.ui.platform.LocalContext.current
    val seekSumView = androidx.compose.ui.platform.LocalView.current
    fun resetSeekSum() {
        seekSumVisible.value = false
        seekSumMs.longValue = 0L
        seekLastTapAt.longValue = 0L
        seekLastChapter.intValue = -1
    }

    fun recordSeekTap(deltaMs: Long) {
        val state = viewModel.playerManager.playerState.value
        val target = (state.currentPositionMs + deltaMs).coerceIn(0L, state.durationMs)
        val actual = target - state.currentPositionMs
        val now = android.os.SystemClock.elapsedRealtime()
        seekSumMs.longValue = SeekSum.accumulate(
            previousSumMs = seekSumMs.longValue,
            lastTapAtMs = seekLastTapAt.longValue,
            previousChapterIndex = seekLastChapter.intValue,
            nowMs = now,
            chapterIndex = state.currentChapterIndex,
            actualDeltaMs = actual
        )
        seekLastTapAt.longValue = now
        seekLastChapter.intValue = state.currentChapterIndex
        // A sub-second shift is not worth a «+0:00» label.
        seekSumVisible.value = kotlin.math.abs(seekSumMs.longValue) >= 1_000L
        seekSumVersion.intValue++
    }

    androidx.compose.runtime.LaunchedEffect(playerState.currentChapterIndex) { resetSeekSum() }
    // The VISUAL sum is brief. The announcement waits for the SERIES to end
    // (the idle window), so a tap in the 1.8–2.5 s gap cannot make TalkBack
    // speak two sums of one logical series.
    androidx.compose.runtime.LaunchedEffect(seekSumVersion.intValue) {
        if (!seekSumVisible.value) return@LaunchedEffect
        val version = seekSumVersion.intValue
        kotlinx.coroutines.delay(SeekSum.VISIBLE_MS)
        if (seekSumVersion.intValue == version && seekSumVisible.value) {
            seekSumVisible.value = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(seekSumVersion.intValue) {
        if (!seekSumVisible.value) return@LaunchedEffect
        val version = seekSumVersion.intValue
        val lastTapAt = seekLastTapAt.longValue
        kotlinx.coroutines.delay(SeekSum.SERIES_IDLE_MS + 50L)
        if (
            seekSumVersion.intValue == version &&
            seekLastTapAt.longValue == lastTapAt &&
            kotlin.math.abs(seekSumMs.longValue) >= 1_000L
        ) {
            val spoken = seekSumContext.getString(
                com.slukhayka.audiobooks.R.string.player_seek_sum_a11y,
                SeekSum.format(seekSumMs.longValue)
            )
            seekSumView.announceForAccessibility(spoken)
        }
    }
    val seekSumLabel = if (seekSumVisible.value) SeekSum.format(seekSumMs.longValue) else null
    var activeTool by rememberSaveable { mutableStateOf<PlayerQuickTool?>(null) }
    var showDebugOverlay by rememberSaveable { mutableStateOf(false) }
    var artworkAccent by remember(book.id) { mutableStateOf<Color?>(null) }
    // Real cover aspect ratio once the artwork loads (defaults to a portrait
    // book cover ~2:3); used to size the cover frame instead of a forced
    // square, so tall covers are NOT cropped — the player has plenty of
    // vertical room, and a cropped cover read as a rendering bug.
    var artworkAspect by remember(book.id) { mutableStateOf(2f / 3f) }

    // Peak-end feedback (design pass): one-shot snackbar confirming the
    // small wins — bookmark saved, sleep timer armed, speed changed. The
    // bottom sheets close silently otherwise.
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingFeedback by remember { mutableStateOf<String?>(null) }
    val playerContext = LocalContext.current
    val fadeWarningFeedback = stringResource(R.string.a11y_timer_fade_warning)
    // v1.4 E5: the one-shot confirmations resolved in the composable body so
    // the sheet callbacks (plain lambdas) can reference them.
    val chapterEndFeedback = stringResource(R.string.player_feedback_chapter_end)
    val timerMinutesTemplate = stringResource(R.string.player_feedback_timer_minutes)
    val bookmarkSavedTemplate = stringResource(R.string.player_feedback_bookmark_saved)
    LaunchedEffect(pendingFeedback) {
        pendingFeedback?.let { message ->
            // Wait for the bottom sheet's dismissal animation before surfacing
            // the confirmation, so the snackbar never renders beneath it.
            kotlinx.coroutines.delay(300)
            snackbarHostState.showSnackbar(message)
            pendingFeedback = null
        }
    }
    LaunchedEffect(viewModel.playerManager, fadeWarningFeedback, playerContext) {
        viewModel.playerManager.sleepTimerNotices.collect { notice ->
            pendingFeedback = when (notice) {
                SleepTimerNotice.FadeWarning -> fadeWarningFeedback
                is SleepTimerNotice.Extended -> playerContext.getString(
                    R.string.a11y_timer_extended,
                    MainViewModel.formatTime(notice.remainingSeconds.toLong())
                )
            }
        }
    }

    val currentChapter = playerState.chapters.getOrNull(playerState.currentChapterIndex)
    // v1.4 E5: the untitled-chapter fallback rides the EN/UK resource pair.
    val currentChapterTitle = currentChapter?.title
        ?: stringResource(R.string.player_chapter_fallback, playerState.currentChapterIndex + 1)
    // #1707 — chapter/bookmark markers are a property of the book ruler, not of
    // the ticking position: remember them once per chapter layout + bookmark
    // set, and let the per-tick recomposition only recompute the fractions that
    // actually move.
    // #1733 — the chapter ruler feeds both the markers and the progress, so it
    // is remembered once here instead of being rebuilt inside each of them on
    // every position tick.
    val effectiveDurations = rememberEffectiveChapterDurations(
        chapters = playerState.chapters,
        currentChapterIndex = playerState.currentChapterIndex,
        currentChapterDurationMs = playerState.durationMs,
        bookTotalDurationSeconds = book.totalDurationSeconds
    )
    val markers = rememberPlayerMarkers(
        chapters = playerState.chapters,
        currentChapterIndex = playerState.currentChapterIndex,
        currentChapterDurationMs = playerState.durationMs,
        bookmarks = bookmarks,
        bookTotalDurationSeconds = book.totalDurationSeconds,
        effectiveDurations = effectiveDurations
    )
    val progress = remember(
        playerState.chapters,
        playerState.currentChapterIndex,
        playerState.currentPositionMs,
        playerState.durationMs,
        bookmarks,
        book.totalDurationSeconds,
        markers,
        effectiveDurations
    ) {
        calculatePlayerProgress(
            chapters = playerState.chapters,
            currentChapterIndex = playerState.currentChapterIndex,
            currentPositionMs = playerState.currentPositionMs,
            currentChapterDurationMs = playerState.durationMs,
            bookmarks = bookmarks,
            bookTotalDurationSeconds = book.totalDurationSeconds,
            markers = markers,
            effectiveDurations = effectiveDurations
        )
    }

    // The player's backdrop is a plain Box.background() (no Material Surface
    // above it), so LocalContentColor would otherwise stay at its framework
    // default (BLACK) and every IconButton/Icon without an explicit tint
    // would render near-invisible black glyphs on the dark backdrop.
    PlayerInkSurface(alwaysInk = alwaysInk) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        PlayerModalUnderlay(
            activeTool = activeTool,
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerScreenContent(
            alwaysInk = alwaysInk,
            playerState = playerState,
            book = book,
            currentChapterTitle = currentChapterTitle,
            progress = progress,
            artworkAccent = artworkAccent,
            artworkAspect = artworkAspect,
            seekSumLabel = seekSumLabel,
            onArtworkLoaded = {
                artworkAccent = extractArtworkAccent(it)
                val iw = it.intrinsicWidth
                val ih = it.intrinsicHeight
                if (iw > 0 && ih > 0) {
                    // Clamp to sane bounds so a panorama or a postage-stamp
                    // cover cannot blow the layout apart.
                    artworkAspect = (iw.toFloat() / ih).coerceIn(0.6f, 1.6f)
                }
            },
            onDismiss = onDismiss,
            onToggleFavorite = { scope.launch { libraryEntries.toggleFavorite(book.id, !book.isFavorite) } },
            onToggleDebug = { showDebugOverlay = !showDebugOverlay },
            onSeek = { fraction ->
                resetSeekSum()
                viewModel.playerManager.seekTo(
                    SeekSession.commitPositionMs(fraction, playerState.durationMs)
                )
            },
            onBookSeek = { fraction ->
                resetSeekSum()
                calculateBookSeekTarget(
                    chapters = playerState.chapters,
                    currentChapterIndex = playerState.currentChapterIndex,
                    currentChapterDurationMs = playerState.durationMs,
                    fraction = fraction,
                    bookTotalDurationSeconds = book.totalDurationSeconds
                )?.let { target ->
                    if (target.chapterIndex == playerState.currentChapterIndex) {
                        viewModel.playerManager.seekTo(target.positionMs)
                    } else {
                        viewModel.playerManager.prepareChapter(
                            chapterIndex = target.chapterIndex,
                            startPositionMs = target.positionMs,
                            autoPlay = playerState.isPlaying
                        )
                    }
                }
            },
            onPreviousChapter = viewModel.playerManager::previousChapter,
            onBack = {
                recordSeekTap(-15_000L)
                viewModel.playerManager.skipBackward(15)
            },
            onPlayPause = {
                val current = viewModel.playerManager.playerState.value
                viewModel.togglePlaybackFromPlayer(
                    book.id,
                    current.currentChapterIndex,
                    current.currentPositionMs
                )
            },
            onForward = {
                recordSeekTap(30_000L)
                viewModel.playerManager.skipForward(30)
            },
            onNextChapter = viewModel.playerManager::nextChapter,
            onUndoSeek = viewModel.playerManager::undoLastSeek,
            onSpeed = { activeTool = PlayerQuickTool.Speed },
            onTimer = { activeTool = PlayerQuickTool.Timer },
            onBookmark = { activeTool = PlayerQuickTool.Bookmark },
            onChapters = { activeTool = PlayerQuickTool.Chapters },
            activeTool = activeTool,
            castReady = castReady,
            lastBookmarkTarget = lastBookmarkTarget,
            onJumpToBookmark = viewModel::jumpToBookmark,
            onShowAllBookmarks = { activeTool = PlayerQuickTool.Bookmarks },
            onRetryPlayback = {
                val current = viewModel.playerManager.playerState.value
                viewModel.togglePlaybackFromPlayer(
                    book.id,
                    current.currentChapterIndex,
                    current.currentPositionMs
                )
            },
            onFindAnotherSource = {
                // #530 — the source that just failed parks in its bounded
                // cooldown (never deleted, never a retry loop), and the action
                // ALSO asks for the ordered offer; nothing starts from it
                // without the listener's own choice below.
                val failedSource = com.slukhayka.audiobooks.data.source.sourceIdForUrl(book.sourceUrl)
                viewModel.recordSourceFailure(failedSource)
                viewModel.loadFallbackCandidates(book.id, failedSource)
                viewModel.findAnotherSource(book.title)
            },
            fallbackCandidates = viewModel.fallbackCandidates.collectAsState().value,
            onSelectFallback = { candidate ->
                // #530 — the listener CHOSE: open exactly that source's URL
                // (never a generic search), and the offer clears itself.
                viewModel.openFallbackCandidate(book.id, candidate)
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(AppDimens.SpaceLg)
                .navigationBarsPadding()
        )

        if (showDebugOverlay) {
            PlayerDebugOverlay(
                playerState = playerState,
                onClose = { showDebugOverlay = false },
                onRetryPlayback = {
                    viewModel.playerManager.prepareChapter(
                        playerState.currentChapterIndex,
                        playerState.currentPositionMs,
                        autoPlay = true
                    )
                },
                events = viewModel.playerManager.playbackEventLog.recent(6),
                metricsSummary = viewModel.playerManager.playbackMetrics.export(),
                journalExport = viewModel.playerManager.playbackEventLog.export(),
                modifier = Modifier.align(Alignment.TopCenter).padding(AppDimens.SpaceLg)
            )
        }
        }
    }
    }

    PlayerInkSurface(alwaysInk = alwaysInk) {
    when (activeTool) {
        PlayerQuickTool.Timer -> SleepTimerSheet(
            currentTimerMinutes = playerState.sleepTimerMinutes,
            isEndOfChapter = playerState.isSleepTimerEndOfChapter,
            remainingSeconds = playerState.sleepTimerRemainingSeconds,
            // Close-on-select: previously the sheet stayed open until the user
            // dismissed it; the chip already reflects the new value, so closing
            // immediately feels tighter and the Snackbar confirms the change.
            onSelectTimer = { minutes ->
                viewModel.playerManager.setSleepTimer(minutes)
                // v1.4 E5: the confirmations ride the EN/UK resource pair
                // (hoisted into the composable body — the callback is not).
                pendingFeedback = when {
                    minutes == -1 -> chapterEndFeedback
                    minutes > 0 -> String.format(timerMinutesTemplate, minutes)
                    else -> null
                }
                activeTool = null
            },
            onExtendTimer = {
                if (viewModel.playerManager.extendSleepTimerBy15Minutes() > 0) {
                    activeTool = null
                }
            },
            onDismiss = { activeTool = null }
        )
        PlayerQuickTool.Speed -> SpeedSheet(
            currentSpeed = playerState.playbackSpeed,
            onSpeedChange = viewModel.playerManager::setPlaybackSpeed,
            onSetDefault = {
                viewModel.playerManager.setDefaultSpeed(playerState.playbackSpeed)
                activeTool = null
            },
            onDismiss = { activeTool = null }
        )
        PlayerQuickTool.Bookmark -> BookmarkBottomSheet(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { activeTool = null },
            onSave = {
                viewModel.addBookmarkAtCurrentPosition(it)
                // Spec-27 (#207): the feedback names the position where the
                // bookmark landed (US-19) — «Закладку додано на 2:35:44».
                pendingFeedback = String.format(
                    bookmarkSavedTemplate,
                    MainViewModel.formatTime(playerState.currentPositionMs / 1000L)
                )
                activeTool = null
            }
        )
        PlayerQuickTool.Chapters -> ChapterBottomSheet(
            chapters = playerState.chapters,
            selectedIndex = playerState.currentChapterIndex,
            onSelect = { index ->
                viewModel.playerManager.selectChapter(index)
                activeTool = null
            },
            onDismiss = { activeTool = null },
            onFeedback = {
                activeTool = null
                viewModel.bookFeedback.open(book.id)
            }
        )
        PlayerQuickTool.Bookmarks -> BookmarksListSheet(
            workTitle = book.title,
            bookmarks = bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.timestampSeconds })),
            onSelect = { bookmark ->
                viewModel.jumpToBookmark(bookmark)
                activeTool = null
            },
            onDelete = { bookmark ->
                scope.launch { viewModel.listeningState.deleteBookmark(bookmark.id) }
            },
            onDismiss = { activeTool = null }
        )
        null -> Unit
    }
    }
}

/**
 * Owns every non-modal player surface as one accessibility background.
 * Snackbar and debug feedback must disappear from TalkBack traversal with
 * the player body while any quick-tool sheet is modal.
 */
@Composable
internal fun PlayerModalUnderlay(
    activeTool: PlayerQuickTool?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .testTag("player_modal_underlay")
            .accessibilityModalBackground(activeTool != null),
        content = content
    )
}

/**
 * The player's ONE scheme boundary: always ink (decision A), or the app's own
 * scheme when the listener turned the exception off in Settings.
 */
@Composable
private fun PlayerInkSurface(alwaysInk: Boolean, content: @Composable () -> Unit) {
    if (alwaysInk) {
        PaperInkTheme(darkTheme = true, content = content)
    } else {
        PaperInkThemedContent(content)
    }
}

/** Pure player surface, separated from the ViewModel for snapshot and interaction tests. */
/**
 * The player is the ONE always-ink surface of the product (decision A): it is
 * wrapped in [PaperInkTheme] no matter which scheme the rest of the app shows,
 * and every call site — the app, the wide pane and the tests — gets that for
 * free from this boundary.
 */
@Composable
fun PlayerScreenContent(
    playerState: PlayerState,
    book: AudiobookEntity,
    currentChapterTitle: String,
    progress: PlayerProgressUi,
    artworkAccent: Color?,
    artworkAspect: Float = 2f / 3f,
    onArtworkLoaded: (Drawable) -> Unit,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDebug: () -> Unit,
    onSeek: (Float) -> Unit,
    onBookSeek: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit,
    onUndoSeek: () -> Unit,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit,
    modifier: Modifier = Modifier,
    activeTool: PlayerQuickTool? = null,
    castReady: Boolean = false,
    lastBookmarkTarget: BookmarkEntity? = null,
    onJumpToBookmark: (BookmarkEntity) -> Unit = {},
    onShowAllBookmarks: () -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onFindAnotherSource: () -> Unit = {},
    // #530 — the ordered fallback offer of #519's action; empty renders nothing.
    fallbackCandidates: List<com.slukhayka.audiobooks.data.editions.FallbackCandidate> = emptyList(),
    onSelectFallback: (com.slukhayka.audiobooks.data.editions.FallbackCandidate) -> Unit = {},
    alwaysInk: Boolean = true,
    /** #1474 — the temporary tap-series sum, or null when no series is open. */
    seekSumLabel: String? = null,
) {
    PlayerInkSurface(alwaysInk = alwaysInk) {
        PlayerScreenContentBody(
            playerState = playerState,
            book = book,
            currentChapterTitle = currentChapterTitle,
            progress = progress,
            artworkAccent = artworkAccent,
            artworkAspect = artworkAspect,
            onArtworkLoaded = onArtworkLoaded,
            onDismiss = onDismiss,
            onToggleFavorite = onToggleFavorite,
            onToggleDebug = onToggleDebug,
            onSeek = onSeek,
            onBookSeek = onBookSeek,
            onPreviousChapter = onPreviousChapter,
            onBack = onBack,
            onPlayPause = onPlayPause,
            onForward = onForward,
            onNextChapter = onNextChapter,
            onUndoSeek = onUndoSeek,
            onSpeed = onSpeed,
            onTimer = onTimer,
            onBookmark = onBookmark,
            onChapters = onChapters,
            modifier = modifier,
            activeTool = activeTool,
            castReady = castReady,
            lastBookmarkTarget = lastBookmarkTarget,
            onJumpToBookmark = onJumpToBookmark,
            onShowAllBookmarks = onShowAllBookmarks,
            onRetryPlayback = onRetryPlayback,
            onFindAnotherSource = onFindAnotherSource,
            fallbackCandidates = fallbackCandidates,
            onSelectFallback = onSelectFallback,
            seekSumLabel = seekSumLabel
        )
    }
}

/**
 * #1708 — the player's OWN width at which its two columns hold the transport.
 *
 * The transport row is five fixed controls: four 52 dp targets plus the 80 dp
 * play box, 288 dp in all. The right column takes 1.15/2 of the width left
 * after the page sides, so those 288 dp need
 * `288 × 2 ÷ 1.15 + 2 × 16 ≈ 533 dp` of PLAYER, not of window. At the 486–495
 * dp right pane of an 840–905 dp window the window-wide decision gave the
 * control column ~261 dp and the targets overlapped by 7–11 dp.
 */
private val PlayerTwoColumnMinWidth =
    AppDimens.PageSides * 2 +
        (PaperInkDimens.PlayerTransportTarget * 4 + PaperInkDimens.PlayerPlayButtonWidth) *
        2 / 1.15f

@Composable
private fun PlayerScreenContentBody(
    playerState: PlayerState,
    book: AudiobookEntity,
    currentChapterTitle: String,
    progress: PlayerProgressUi,
    artworkAccent: Color?,
    artworkAspect: Float = 2f / 3f,
    onArtworkLoaded: (Drawable) -> Unit,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDebug: () -> Unit,
    onSeek: (Float) -> Unit,
    onBookSeek: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit,
    onUndoSeek: () -> Unit,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit,
    modifier: Modifier = Modifier,
    activeTool: PlayerQuickTool? = null,
    castReady: Boolean = false,
    lastBookmarkTarget: BookmarkEntity? = null,
    onJumpToBookmark: (BookmarkEntity) -> Unit = {},
    onShowAllBookmarks: () -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onFindAnotherSource: () -> Unit = {},
    // #530 — the ordered fallback offer of #519's action; empty renders nothing.
    fallbackCandidates: List<com.slukhayka.audiobooks.data.editions.FallbackCandidate> = emptyList(),
    onSelectFallback: (com.slukhayka.audiobooks.data.editions.FallbackCandidate) -> Unit = {},
    /** #1474 — the temporary tap-series sum, or null when no series is open. */
    seekSumLabel: String? = null
) {
    val background = MaterialTheme.colorScheme.background
    // One saturated accent for sound; verdigris stays for quiet actions.
    val tint = artworkAccent ?: MaterialTheme.colorScheme.tertiary
    val largeFont = LocalDensity.current.fontScale >= 2f
    // #962 — a wide-but-SHORT window (a phone in landscape: 905 × 411 dp on
    // the device this was found on) cannot fit the portrait column. The cover
    // slot is the flexible element there, and there is no leftover height to
    // give it, so the quick-tool row fell past the bottom edge and reported
    // `bounds=[0,0][0,0]` while the transport row was clipped. This window
    // lays out in two columns instead.
    //
    // #1708 — this answers for the WINDOW. The two-column branch below also
    // measures the player's OWN width (it can be the right pane) and stops
    // applying under a large font.
    val landscapeWindow = rememberIsLandscapePhoneWindow()
    val contentScrollState = rememberScrollState()
    val playerContextFocusRequester = remember { FocusRequester() }
    val speedFocusRequester = remember { FocusRequester() }
    val timerFocusRequester = remember { FocusRequester() }
    val bookmarkFocusRequester = remember { FocusRequester() }
    val chaptersFocusRequester = remember { FocusRequester() }
    val bookmarksFocusRequester = remember { FocusRequester() }
    var restoreTool by remember { mutableStateOf<PlayerQuickTool?>(null) }
    // #597 — the local, user-shared problem report (allowlisted fields only).
    val reportContext = LocalContext.current
    var showProblemReport by remember { mutableStateOf(false) }
    val playerNarrator = book.displayNarrator
    val editionDescription = if (playerNarrator.isNotBlank()) {
        stringResource(R.string.a11y_player_edition, playerNarrator)
    } else {
        stringResource(R.string.a11y_player_edition_unknown)
    }
    val playerContextDescription = stringResource(
        R.string.a11y_player_context,
        book.title,
        book.displayAuthor,
        editionDescription,
        currentChapterTitle
    )

    LaunchedEffect(playerContextFocusRequester) {
        // #1708 — the context pane now sits inside a BoxWithConstraints, whose
        // content is composed during MEASURE, so the requester is attached a
        // beat after this effect starts. Wait one frame before asking for
        // focus — the same pattern ChapterOrderDialog uses for the same reason.
        withFrameNanos { }
        playerContextFocusRequester.requestFocus()
    }
    LaunchedEffect(activeTool) {
        if (activeTool != null) {
            restoreTool = activeTool
        }
    }
    val toolReturnFocusRequester = when (restoreTool) {
        PlayerQuickTool.Speed -> speedFocusRequester
        PlayerQuickTool.Timer -> timerFocusRequester
        PlayerQuickTool.Bookmark -> bookmarkFocusRequester
        PlayerQuickTool.Chapters -> chaptersFocusRequester
        PlayerQuickTool.Bookmarks -> if (lastBookmarkTarget != null) {
            bookmarksFocusRequester
        } else {
            playerContextFocusRequester
        }
        null -> null
    }
    RestoreFocusAfterModal(
        modalVisible = activeTool != null,
        returnFocusRequester = toolReturnFocusRequester,
        onFocusRestored = { restoreTool = null }
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("full_player_screen")
            .background(
                // The player is a full-screen OVERLAY on top of whatever screen
                // was open (AnimatedVisibility in MainActivity), so its backdrop
                // must be fully opaque — a translucent gradient let the previous
                // screen (book page, list) show through the top of the player,
                // which read as a rendering bug. The accent is blended INTO the
                // background color (lerp) instead of painted with alpha, keeping
                // the soft tinted look with zero see-through.
                Brush.verticalGradient(
                    0f to lerp(background, tint, 0.12f),
                    0.42f to background,
                    1f to background
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                castReady = castReady,
                bookTitle = book.title,
                isFavorite = book.isFavorite,
                isOffline = playerState.isOfflineMode,
                onDismiss = onDismiss,
                onToggleFavorite = onToggleFavorite,
                onToggleDebug = onToggleDebug
            )

            // Spec-24 T1: the player fits ONE screen — the scroll wrapper is
            // gone and the cover block is the flexible element (weight-based,
            // aspect preserved, capped), so every control below stays visible.
            //
            // #962 — that is a PORTRAIT contract: it holds in a 411 dp-wide,
            // ~700 dp-tall column. A landscape phone window (905 × 411 dp,
            // measured on device) has no leftover height for that flexible
            // slot, so the quick-tool row was measured at y 1391–1438 px of a
            // 1438 px window and the system bar then took the rest — those
            // controls reported `bounds=[0,0][0,0]` (never placed at all) and
            // the transport row was clipped by the bottom edge. The
            // wide-and-short window therefore lays out the way every other
            // wide surface in this app does: the artwork and identity on the
            // LEFT, progress + transport + tools BESIDE them, each in a column
            // with real height to give.
            //
            // #1708 — but the window's width is not the player's width: at
            // 840–905 dp the player is the RIGHT PANE of WideDetailPane
            // (≈456–495 dp), where the same 0.85/1.15 split leaves the control
            // column ~261 dp against the transport row's 288 dp, and the 52 dp
            // targets overlap by 7–11 dp. So the two-column shape is decided
            // by the player's OWN width (BoxWithConstraints — the same
            // "measure, don't guess" seam QuickTools uses), and never taken
            // under a large font: at fontScale 2.0 two columns are not tall
            // enough for the enlarged title and timer row, while the portrait
            // branch already scrolls.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val twoColumns = landscapeWindow &&
                    !largeFont &&
                    maxWidth >= PlayerTwoColumnMinWidth
                // #1708 — in an 840–905 dp landscape window the player's pane
                // is only ≈456–495 dp wide and 411 dp tall: too narrow for two
                // columns and too short for the one-screen portrait column.
                // The portrait branch then scrolls (the treatment a large font
                // already gets), so no control is clipped off the bottom edge.
                val scrolls = largeFont || (landscapeWindow && !twoColumns)
                Column(modifier = Modifier.fillMaxSize()) {
                    if (twoColumns) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = AppDimens.PageSides),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.85f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                PlayerArtworkPane(
                                    book = book,
                                    playerNarrator = playerNarrator,
                                    currentChapterTitle = currentChapterTitle,
                                    playerContextDescription = playerContextDescription,
                                    artworkAspect = artworkAspect,
                                    onArtworkLoaded = onArtworkLoaded,
                                    playerContextFocusRequester = playerContextFocusRequester,
                                    largeFont = largeFont,
                                    tint = tint,
                                    slotWeight = 1f
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1.15f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                                Spacer(Modifier.height(AppDimens.SpaceLg))
                                                DualProgress(
                                                    progress = progress,
                                                    chapterPositionSeconds = playerState.currentPositionMs / 1000L,
                                                    chapterDurationMs = playerState.durationMs,
                                                    onSeek = onSeek,
                                                    onBookSeek = onBookSeek,
                                                    bookmarkTarget = lastBookmarkTarget,
                                                    bookmarkFocusRequester = bookmarksFocusRequester,
                                                    onJumpToBookmark = onJumpToBookmark,
                                                    onShowAllBookmarks = onShowAllBookmarks
                                                )

                                                Spacer(Modifier.height(AppDimens.SpaceMd))
                                                TransportControls(
                                                    bookTitle = book.title,
                                                    currentChapterTitle = currentChapterTitle,
                                                    isPlaying = playerState.isPlaying,
                                                    // Issue #381: під час резолюції стріму кнопка не має
                                                    // промовляти застаріле «Призупинено».
                                                    isBuffering = playerState.isBuffering,
                                                    seekSumLabel = seekSumLabel,
                                                    onPreviousChapter = onPreviousChapter,
                                                    onBack = onBack,
                                                    onPlayPause = onPlayPause,
                                                    onForward = onForward,
                                                    onNextChapter = onNextChapter
                                                )

                                                if (playerState.canUndoSeek) {
                                                    TextButton(
                                                        onClick = onUndoSeek,
                                                        modifier = Modifier
                                                            .heightIn(min = AppDimens.TouchTarget)
                                                            .testTag("undo_seek_row")
                                                    ) {
                                                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                                                        Spacer(Modifier.width(AppDimens.SpaceSm))
                                                        Text(stringResource(R.string.player_return_to, MainViewModel.formatTime(playerState.undoFromPositionMs / 1000L)))
                                                    }
                                                }

                                                Spacer(Modifier.height(AppDimens.SpaceMd))
                                                QuickTools(
                                                    speed = playerState.playbackSpeed,
                                                    timerMinutes = playerState.sleepTimerMinutes,
                                                    onSpeed = onSpeed,
                                                    onTimer = onTimer,
                                                    onBookmark = onBookmark,
                                                    onChapters = onChapters,
                                                    speedFocusRequester = speedFocusRequester,
                                                    timerFocusRequester = timerFocusRequester,
                                                    bookmarkFocusRequester = bookmarkFocusRequester,
                                                    chaptersFocusRequester = chaptersFocusRequester
                                                )
                            }
                        }
                        // The player is a full-screen OVERLAY rendered outside the host
                        // Scaffold, so it gets no bottom inset from it. Pad past the
                        // system navigation bar / gesture zone so the quick-tools row
                        // is never hidden behind it (3-button nav is ~48dp tall).
                        Spacer(Modifier.navigationBarsPadding())
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (scrolls) Modifier.verticalScroll(contentScrollState)
                                    else Modifier
                                )
                                .fillMaxWidth()
                                .padding(horizontal = AppDimens.PageSides),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlayerArtworkPane(
                                book = book,
                                playerNarrator = playerNarrator,
                                currentChapterTitle = currentChapterTitle,
                                playerContextDescription = playerContextDescription,
                                artworkAspect = artworkAspect,
                                onArtworkLoaded = onArtworkLoaded,
                                playerContextFocusRequester = playerContextFocusRequester,
                                largeFont = largeFont,
                                scrolls = scrolls,
                                tint = tint,
                                slotWeight = 1f
                            )
                        if (playerState.isBuffering) {
                            // Issue #381 (a11y/UX-аудит v1.3.6): під час резолюції
                            // YouTube-стріму TalkBack промовляв застаріле «Призупинено»
                            // — polite liveRegion оголошує «Завантаження» поки стрім
                            // готується і немає помилки (зразок HomeFeedContent).
                            val loadingLabel = stringResource(R.string.a11y_player_state_loading)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_loading_status")
                                    .semantics(mergeDescendants = true) {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(AppDimens.TouchTarget / 2))
                                Spacer(Modifier.width(AppDimens.SpaceXs))
                                Text(
                                    text = loadingLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!playerState.isBuffering && playerState.lastErrorMsg.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(AppDimens.RadiusCard),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_recovery_failed")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_recovery_failed),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                    ) {
                                        TextButton(onClick = onRetryPlayback, modifier = Modifier.heightIn(min = 48.dp).testTag("player_retry")) {
                                            Text(stringResource(R.string.player_retry))
                                        }
                                        TextButton(onClick = onFindAnotherSource, modifier = Modifier.heightIn(min = 48.dp).testTag("player_find_another_source")) {
                                            Text(stringResource(R.string.player_find_another_source))
                                        }
                                        // #597 — a secondary diagnostic action; it never
                                        // crowds the primary retry/source buttons.
                                        TextButton(onClick = { showProblemReport = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("player_problem_report")) {
                                            Text(stringResource(R.string.player_problem_report_view))
                                        }
                                    }
                                }
                            }
                        }

                        // #530 — the ordered alternatives, shown before anything
                        // switches; a different narration carries «Перемкнути».
                        if (fallbackCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(AppDimens.SpaceSm))
                            FallbackCandidatesCard(
                                candidates = fallbackCandidates,
                                onSelect = onSelectFallback
                            )
                        }

                        if (showProblemReport) {
                            // #597 — allowlisted fields only; the listener reviews the
                            // whole text and shares it explicitly (never automatic).
                            val report = com.slukhayka.audiobooks.data.diagnostics.PlaybackProblemReport.build(
                                category = playerState.errorKind.name,
                                stage = "PLAYBACK",
                                appVersion = com.slukhayka.audiobooks.BuildConfig.VERSION_NAME,
                                appVariant = if (com.slukhayka.audiobooks.BuildConfig.DEBUG) "debug" else "release",
                                androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown",
                                androidSdk = android.os.Build.VERSION.SDK_INT,
                                webViewVersion = if (android.os.Build.VERSION.SDK_INT >= 26) {
                                    runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
                                } else {
                                    null
                                }
                            )
                            AlertDialog(
                                onDismissRequest = { showProblemReport = false },
                                title = { Text(stringResource(R.string.player_problem_report_title)) },
                                text = {
                                    Column {
                                        Text(report.toPlainText())
                                        Spacer(Modifier.height(AppDimens.SpaceSm))
                                        Text(
                                            text = stringResource(R.string.player_problem_report_body_note),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showProblemReport = false
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, report.toPlainText())
                                        }
                                        runCatching {
                                            reportContext.startActivity(android.content.Intent.createChooser(send, null))
                                        }
                                    }) {
                                        Text(stringResource(R.string.player_problem_report_share))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showProblemReport = false }) {
                                        Text(stringResource(R.string.player_problem_report_close))
                                    }
                                }
                            )
                        }
                        // F7 — one inter-row gap went: the freed height belongs to the
                        // artwork slot (the audit's 272 dp cover budget).
                        DualProgress(
                            progress = progress,
                            chapterPositionSeconds = playerState.currentPositionMs / 1000L,
                            chapterDurationMs = playerState.durationMs,
                            onSeek = onSeek,
                            onBookSeek = onBookSeek,
                            bookmarkTarget = lastBookmarkTarget,
                            bookmarkFocusRequester = bookmarksFocusRequester,
                            onJumpToBookmark = onJumpToBookmark,
                            onShowAllBookmarks = onShowAllBookmarks
                        )

                        Spacer(Modifier.height(2.dp))
                        TransportControls(
                            bookTitle = book.title,
                            currentChapterTitle = currentChapterTitle,
                            isPlaying = playerState.isPlaying,
                            // Issue #381: під час резолюції стріму кнопка не має
                            // промовляти застаріле «Призупинено».
                            isBuffering = playerState.isBuffering,
                            seekSumLabel = seekSumLabel,
                            onPreviousChapter = onPreviousChapter,
                            onBack = onBack,
                            onPlayPause = onPlayPause,
                            onForward = onForward,
                            onNextChapter = onNextChapter
                        )

                        if (playerState.canUndoSeek) {
                            TextButton(
                                onClick = onUndoSeek,
                                modifier = Modifier
                                    .heightIn(min = AppDimens.TouchTarget)
                                    .testTag("undo_seek_row")
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                                Spacer(Modifier.width(AppDimens.SpaceSm))
                                Text(stringResource(R.string.player_return_to, MainViewModel.formatTime(playerState.undoFromPositionMs / 1000L)))
                            }
                        }

                        // F7 — the quick-tools row sits right under the transport;
                        // the 12 dp gap was artwork height.
                        QuickTools(
                            speed = playerState.playbackSpeed,
                            timerMinutes = playerState.sleepTimerMinutes,
                            onSpeed = onSpeed,
                            onTimer = onTimer,
                            onBookmark = onBookmark,
                            onChapters = onChapters,
                            speedFocusRequester = speedFocusRequester,
                            timerFocusRequester = timerFocusRequester,
                            bookmarkFocusRequester = bookmarkFocusRequester,
                            chaptersFocusRequester = chaptersFocusRequester
                        )
                        // The player is a full-screen OVERLAY rendered outside the
                        // host Scaffold, so it gets no bottom inset from it. Pad
                        // past the system navigation bar / gesture zone so the
                        // quick-tools row is never hidden behind it.
                        Spacer(Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }
    }
}

/**
 * #962 — the player's artwork and identity (cover, title, author, narrator,
 * current chapter) as ONE piece, because the two window shapes put it in two
 * different containers: the portrait column gives it the flexible slot above
 * the transport, and a wide-but-short window puts it in the LEFT column of a
 * Row beside them. Extracting it is what lets both shapes draw the same pixels
 * instead of two copies that drift.
 *
 * [slotModifier] is the #385 contract, moved to the caller: the cover slot is
 * the flexible element and the cover is measured from the slot's LIVE height
 * (not from its width via `aspectRatio`), so it can never outgrow its slot.
 *
 * It is a [ColumnScope] extension because the portrait caller's slot IS
 * `Modifier.weight(1f)`, and `weight` is only meaningful when it is applied
 * inside a `Column`'s own scope. Passing that modifier through a plain
 * parameter silently drops the scope and the weight with it, which collapses
 * the cover to its intrinsic size and pushes the transport off the screen —
 * exactly the regression the portrait pin caught.
 */
@Composable
private fun ColumnScope.PlayerArtworkPane(
    // The artwork accent (or the brand primary before a cover loads) — the
    // glow behind the cover is tinted with it, so the pane cannot resolve it.
    tint: Color,
    book: AudiobookEntity,
    playerNarrator: String,
    currentChapterTitle: String,
    playerContextDescription: String,
    artworkAspect: Float,
    onArtworkLoaded: (Drawable) -> Unit,
    playerContextFocusRequester: FocusRequester,
    largeFont: Boolean,
    slotWeight: Float,
    /** #1708 — a scrolling column has no bounded height to weight a slot in. */
    scrolls: Boolean = false
) {
    val slotModifier: Modifier = if (largeFont || scrolls) {
        Modifier.height(208.dp)
    } else {
        Modifier.weight(slotWeight)
    }
    // The cover absorbs the leftover vertical space (weight slot).
    // Issue #385: розмір обкладинки рахується напряму від живої
    // висоти слота (BoxWithConstraints дає її в dp), а не через
    // .aspectRatio() після fillMaxWidth — той вимірюється від
    // ШИРИНИ і, коли жоден candidate не вміщується в обмеження,
    // падає в fallback, який ігнорує і heightIn, і weight-частку:
    // з'являється рядок «Завантаження» (#381) →
    // слот стискається, а обкладинка лишається w/ratio заввишки й
    // «випадає» за нижню межу слота поверх заголовка (Compose не
    // кліпує сусідів). У спокійному стані виходить той самий
    // w/ratio, що й раніше; у стиснутому — висота слота зі
    // збереженням пропорцій.
    BoxWithConstraints(
        modifier = slotModifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Soft glow behind the cover — tinted with the artwork accent
        // (or the brand accent before the cover loads) so the shadow
        // hue matches the scene instead of reading as flat black.
        // Sized and painted in dp so the falloff is density-stable.
        val glowRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() }
        // The glow mirrors the cover's (real) aspect ratio instead
        // of a hard-coded square, so the halo hugs the artwork.
        val coverAspect = artworkAspect.coerceIn(0.6f, 1.6f)
        val glowWidth = maxWidth * 0.9f
        Box(
            modifier = Modifier
                .size(width = glowWidth, height = glowWidth / coverAspect)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            tint.copy(alpha = 0.28f),
                            tint.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        radius = glowRadiusPx
                    )
                )
        )
        // Cover width keeps the old widthIn(272.dp) + fillMaxWidth(0.76f)
        // result; the height is w/ratio while it fits the slot and
        // clamps to the slot (ratio kept) when the loading status
        // squeezes the column — so the artwork can never
        // outgrow its slot into the title.
        // #885 (wave 3) — the prototype sizes the art as
        // `max-width:215px; width:67%` (`:1193`), i.e. 67 % of the container
        // CAPPED at 215. The app had it the other way round — 76 % of
        // min(272, container) — which is a different curve at every width:
        // at 400 dp it gave 207 against the prototype's 215, and at 300 dp
        // 207 against 201. The cap now applies AFTER the fraction, as written.
        //
        // The responsive contract from spec-24 T6 / #385 is preserved: the
        // cover still shrinks with the container and stays inside its slot
        // (`player_tight_viewport` pins it).
        // F7 (аудит 2026-10-28): спека міряє 67 % від ШИРИНИ ЕКРАНА, а не від
        // вузького слота з page-полями: 0.67 × 411 = 275 → кап 272. Стара
        // формула давала 0.67 × 379 = 254 ще ДО обмеження висотою.
        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val coverWidth = (screenWidth * 0.67f)
            .coerceAtMost(PaperInkDimens.CoverPlayerWidth)
            .coerceAtMost(maxWidth)
        val coverHeight = (coverWidth / coverAspect).coerceAtMost(maxHeight)
        Surface(
            shape = RoundedCornerShape(PaperInkDimens.RadiusLarge),
            // #885 (wave 3) — the prototype gives the art ONE shadow:
            // `box-shadow: 0 12px 34px #0002` (`:1194`). The app had two
            // effects (a 1 dp tonal tint plus a 6 dp drop shadow), which is a
            // different recipe for the same intent.
            //
            // Compose cannot express a CSS offset/blur pair, so
            // `shadowElevation` is an APPROXIMATION — the 12 dp carries the
            // prototype's 12 px offset. This is "closer to the written
            // design", not "identical to it", and the golden shows which.
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            // Test seam: the tight-viewport snapshot measures the
            // cover to pin that it shrinks while keeping its aspect
            // ratio (spec-24 T6) — and, since #385, that it stays
            // inside its slot.
            modifier = Modifier
                .size(width = coverHeight * coverAspect, height = coverHeight)
                .testTag("player_cover")
        ) {
            BookCoverImage(
                book = book,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onImageLoaded = onArtworkLoaded,
                // The player is always ink: a missing cover is a paper/ink
                // block with the book's letter, never the genre's neon tile
                // that fights the whole screen.
                placeholderAccentOverride = MaterialTheme.colorScheme.surfaceVariant,
                placeholderContentColorOverride = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player_context")
            .focusRequester(playerContextFocusRequester)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = playerContextDescription
                heading()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = book.title,
            // #885 (wave 3) — the prototype's player title is its own `h1` at
            // 29 px (`.sl-player h1`, :1197), not the 24 sp `headlineSmall` the
            // root screens share. `headlineMedium` (28 sp) is the canonical
            // style one step up, so this moves to the written design WITHOUT
            // inventing a size — a one-off `29.sp` would be exactly the new
            // variation ADR-0033 calls a defect.
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (largeFont) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(AppDimens.SpaceXs))
        Text(
            text = book.displayAuthor,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
        // Spec-20 T2: narrator renders only when real — the fabricated
        // "4read Voice Narrator" placeholder is scrubbed away. The
        // real narrator lands once the book page fetch back-fills it.
        if (playerNarrator.isNotBlank()) {
            Text(
                text = stringResource(R.string.player_narrated_by, playerNarrator),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeFont) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // F7 — the chapter line follows the author directly; the second
        // 8 dp spacer spent artwork height on air.
        Text(
            text = currentChapterTitle,
            style = MaterialTheme.typography.titleSmall,
            // F32 — the current chapter is SOUND: the direction's lamp, not the
            // verdigris action colour. On the always-ink player that is
            // LampNight, i.e. the same role `lampText` names elsewhere.
            color = PaperInkColors.lampText,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerTopBar(
    bookTitle: String,
    // #885 (wave 3) — cast lives HERE, in the header, exactly as the prototype
    // puts it (`:1318`: for the player route the header renders the cast
    // button). It used to sit in the quick-tools row, which is why that row
    // held FIVE children and could never be the prototype's four equal
    // columns — the row now has four, and `QuickTool`'s existing `weight(1f)`
    // makes them equal without further work.
    castReady: Boolean,
    isFavorite: Boolean,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDebug: () -> Unit
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // #1711 — tagged so the pane placement can prove the host's
            // consumed status-bar inset is not added a second time here.
            .testTag("player_top_bar")
            .statusBarsPadding()
            // F7 — 48 dp header line: the controls inside are 48 dp targets,
            // so the line itself carries no air; the height belongs to the art.
            .heightIn(min = 48.dp)
            .padding(horizontal = AppDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(AppDimens.TouchTarget).testTag("close_player_button")
        ) {
            Icon(
                PaperInkIcons.ChevronDown,
                contentDescription = stringResource(R.string.a11y_player_close),
                modifier = Modifier.size(30.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                // v1.4 (ADR-0033): the overline rides resources, not code.
                text = stringResource(R.string.player_now_playing),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isOffline) {
                Text(stringResource(R.string.book_detail_offline_short), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The transport's cast, in the header beside the overflow — the
        // prototype's own arrangement for the player route.
        CastButton(castReady = castReady)
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(AppDimens.TouchTarget).testTag("player_actions_button")
            ) {
                Icon(
                    PaperInkIcons.More,
                    contentDescription = stringResource(R.string.a11y_player_actions, bookTitle)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFavorite) R.string.player_favorite_remove
                                else R.string.player_favorite_add
                            )
                        )
                    },
                    leadingIcon = { PaperInkIcon(if (isFavorite) PaperInkIcons.HeartFilled else PaperInkIcons.Heart, contentDescription = null) },
                    onClick = { onToggleFavorite(); showMenu = false }
                )
                if (BuildConfig.DEBUG) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.debug_overlay_title)) },
                        leadingIcon = { Icon(Icons.Outlined.BugReport, null) },
                        onClick = { onToggleDebug(); showMenu = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualProgress(
    progress: PlayerProgressUi,
    chapterPositionSeconds: Long,
    chapterDurationMs: Long,
    onSeek: (Float) -> Unit,
    // Kept in the signature for the wide-pane contract and callers; the book
    // line is READ-ONLY in the reference design (one seek: the chapter).
    @Suppress("UNUSED_PARAMETER") onBookSeek: (Float) -> Unit,
    bookmarkTarget: BookmarkEntity? = null,
    bookmarkFocusRequester: FocusRequester,
    onJumpToBookmark: (BookmarkEntity) -> Unit = {},
    onShowAllBookmarks: () -> Unit = {}
) {
    // #1571 — the display and the seek read the SAME exact duration: the
    // label's whole seconds are derived from it and the drag preview maps
    // against it, so the release lands on the second the label shows. Truncating
    // to seconds first (the old shape) made the preview and the commit disagree
    // by a second for durations that are not a whole number of seconds.
    val chapterDurationSeconds = chapterDurationMs / 1_000L

    // #1571 — the scrub session. While a finger is down the slider follows it
    // and the chapter time is rendered from the local fraction; the player (and
    // the progress write that follows every seek) hears only the release, as
    // ONE seek. A cancelled drag commits nothing; a value change no drag
    // interaction produced (TalkBack's SetProgress, a tap on the track) is
    // committed at once, because there will be no release to wait for.
    val seekInteractionSource = remember { MutableInteractionSource() }
    val dragInProgress by seekInteractionSource.collectIsDraggedAsState()
    var previewFraction by remember { mutableStateOf<Float?>(null) }
    var releaseCommit by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)

    fun commitScrub(fraction: Float) {
        previewFraction = null
        releaseCommit = null
        currentOnSeek(fraction)
    }

    LaunchedEffect(seekInteractionSource) {
        seekInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                // The interaction stream is the one place that tells a release
                // from a cancel: `onValueChangeFinished` fires for both (the
                // foundation draggable also stops on ACTION_CANCEL), and it
                // fires after the Stop/Cancel interaction was emitted. Taking
                // the preview as a fallback on Stop keeps the commit single
                // even if the two callbacks are ever observed in the other
                // order.
                is DragInteraction.Stop -> {
                    val released = releaseCommit ?: previewFraction
                    if (released != null) commitScrub(released) else releaseCommit = null
                }
                is DragInteraction.Cancel -> {
                    releaseCommit = null
                    previewFraction = null
                }
            }
        }
    }

    val previewSeconds = previewFraction?.let {
        SeekSession.previewSeconds(it, chapterDurationMs)
    }
    val shownChapterPositionSeconds = previewSeconds ?: chapterPositionSeconds
    val shownChapterFraction = previewFraction ?: progress.chapterFraction

    val chapterPositionLabel = stringResource(R.string.a11y_player_chapter_position)
    val bookPositionLabel = stringResource(R.string.a11y_player_book_position)
    val unknownDuration = stringResource(R.string.a11y_player_duration_unknown)
    val chapterTimeDescription = if (chapterDurationSeconds > 0L) {
        stringResource(
            R.string.a11y_player_time_of,
            MainViewModel.formatTime(shownChapterPositionSeconds),
            MainViewModel.formatTime(chapterDurationSeconds)
        )
    } else {
        unknownDuration
    }
    val bookTimeDescription = if (progress.bookDurationSeconds > 0L) {
        stringResource(
            R.string.a11y_player_time_of,
            MainViewModel.formatTime(progress.bookPositionSeconds),
            MainViewModel.formatTime(progress.bookDurationSeconds)
        )
    } else {
        unknownDuration
    }
    val hasBookDuration = progress.bookDurationSeconds > 0L
    val hasChapterDuration = chapterDurationSeconds > 0L
    // One seek (chapter) 6 dp with a 44 dp interaction zone; the book is a
    // 2 dp read-only line whose parts carry the chapter/bookmark marks.
    // #1707 — DualProgress recomposes on every position tick, but the slider
    // colors are a theme fact. `SliderDefaults.colors` is @Composable in M3
    // 1.3, so `remember` cannot wrap the call itself; a one-slot remembered
    // holder evaluates it once per ColorScheme and reuses the immutable
    // SliderColors on the later ticks (a theme change resets the slot).
    val colorScheme = MaterialTheme.colorScheme
    val sliderColorsSlot = remember(colorScheme) { arrayOfNulls<SliderColors>(1) }
    val chapterSliderColors = sliderColorsSlot[0] ?: SliderDefaults.colors(
        thumbColor = colorScheme.tertiary,
        activeTrackColor = colorScheme.tertiary,
        inactiveTrackColor = colorScheme.outlineVariant
    ).also { sliderColorsSlot[0] = it }
    Column(modifier = Modifier.fillMaxWidth()) {
        // F7 (аудит 2026-10-28) — the book's read-only time+percent row went
        // away: it duplicated the chapter time below, ate ~40 dp of the ONLY
        // flexible slot, and the spec keeps ONE seek (chapter). The read-only
        // book line stays as the part/bookmark ruler, so TalkBack can still
        // read the book position.
        BookProgressLine(
            progress = progress,
            positionLabel = bookPositionLabel,
            timeDescription = bookTimeDescription,
            bookmarkTarget = bookmarkTarget,
            bookmarkFocusRequester = bookmarkFocusRequester,
            onJumpToBookmark = onJumpToBookmark,
            onShowAllBookmarks = onShowAllBookmarks,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("chapter_progress_visual_row")
                .semantics { hideFromAccessibility() },
            verticalAlignment = Alignment.Bottom,
        ) {
            if (hasChapterDuration) {
                Text(
                    text = MainViewModel.formatTime(shownChapterPositionSeconds),
                    style = PaperInkType.TimerXL,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " / ${MainViewModel.formatTime(chapterDurationSeconds)}",
                    style = PaperInkType.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            } else {
                Text(
                    text = unknownDuration,
                    style = PaperInkType.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.listen_chapter),
                style = PaperInkType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SilentlySeekChapter(
            value = shownChapterFraction,
            onValueChange = { previewFraction = it },
            onValueChangeFinished = {
                val released = previewFraction
                if (released != null) {
                    if (dragInProgress) {
                        // The Stop/Cancel interaction arrives just after this
                        // callback; the collector above turns it into the one
                        // commit (release) or into nothing (cancel).
                        releaseCommit = released
                    } else {
                        commitScrub(released)
                    }
                }
            },
            interactionSource = seekInteractionSource,
            positionLabel = chapterPositionLabel,
            timeDescription = chapterTimeDescription,
            colors = chapterSliderColors,
            hasDuration = hasChapterDuration,
        )
    }
}

/**
 * The chapter seek: a 6 dp track whose touch zone is at least 44 dp, and the
 * only adjustable progress control in the reference player.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SilentlySeekChapter(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    interactionSource: MutableInteractionSource,
    positionLabel: String,
    timeDescription: String,
    colors: SliderColors,
    hasDuration: Boolean,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = hasDuration,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(PaperInkDimens.PlayerSeekHeight)
            .semantics {
                contentDescription = positionLabel
                stateDescription = timeDescription
            }
            .testTag("player_progress_slider"),
        colors = colors,
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = colors,
                drawStopIndicator = {},
            )
        },
    )
}

/**
 * The book ruler: 2 dp, read-only (the chapter slider is the one seek), with
 * the chapter boundaries as part dividers and the bookmarks as dots. It keeps
 * its own accessible description/state, so TalkBack can still read the book
 * position even though the line is not adjustable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookProgressLine(
    progress: PlayerProgressUi,
    positionLabel: String,
    timeDescription: String,
    bookmarkTarget: BookmarkEntity?,
    bookmarkFocusRequester: FocusRequester,
    onJumpToBookmark: (BookmarkEntity) -> Unit,
    onShowAllBookmarks: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.tertiary
    val activeMarker = MaterialTheme.colorScheme.onTertiary
    val inactiveMarker = MaterialTheme.colorScheme.onSurfaceVariant
    val bookmarkColor = MaterialTheme.colorScheme.tertiary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    // F7 — the read-only book ruler is 24 dp tall (it carries no controls),
    // EXCEPT when it holds the last-bookmark target, whose 48 dp touch target
    // still needs the 44 dp row.
    val lineHeight = if (bookmarkTarget != null) 44.dp else 14.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeight)
            .semantics {
                contentDescription = positionLabel
                stateDescription = timeDescription
            }
            .testTag("book_progress_line"),
    ) {
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(2.dp)
        ) {
            drawLine(
                color = trackColor,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            val filled = (size.width * progress.bookFraction.coerceIn(0f, 1f))
            drawLine(
                color = active,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(filled, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("book_progress_markers")
                .semantics { hideFromAccessibility() },
        ) {
            val centerY = size.height / 2f
            // F32 — the spec asks for ONE seek plus a read-only line with the
            // book's THREE parts. The old ruler drew a tick per chapter (12+
            // here), which read as a second, nearly identical progress bar.
            BOOK_PART_DIVIDERS.forEach { divider ->
                val color = if (divider <= progress.bookFraction) {
                    activeMarker.copy(alpha = 0.72f)
                } else {
                    inactiveMarker
                }
                drawMarker(divider, color, size.height, 1.5.dp.toPx())
            }
            progress.bookmarkMarkers.forEach { marker ->
                val color = if (marker <= progress.bookFraction) bookmarkColor else bookmarkColor.copy(alpha = 0.55f)
                // A read-only ruler must not offer a slider-looking dot: the
                // bookmark is a small tick on the line, not a thumb.
                drawCircle(color, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * marker, centerY))
            }
        }
        if (bookmarkTarget != null) {
            val bookmarkTime = MainViewModel.formatTime(bookmarkTarget.timestampSeconds)
            val jumpDescription = stringResource(R.string.a11y_player_bookmark_jump, bookmarkTime)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(PaperInkDimens.TouchTarget)
                    .focusRequester(bookmarkFocusRequester)
                    .focusable()
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onJumpToBookmark(bookmarkTarget) },
                        onLongClick = onShowAllBookmarks,
                    )
                    .clearAndSetSemantics {
                        contentDescription = jumpDescription
                        onClick {
                            onJumpToBookmark(bookmarkTarget)
                            true
                        }
                        onLongClick {
                            onShowAllBookmarks()
                            true
                        }
                    }
                    .testTag("jump_to_last_bookmark"),
                contentAlignment = Alignment.Center,
            ) {
                PaperInkIcon(PaperInkIcons.BookmarkFilled, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun DrawScope.drawMarker(fraction: Float, color: Color, height: Float, width: Float) {
    translate(left = size.width * fraction) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, (size.height - height) / 2f),
            end = androidx.compose.ui.geometry.Offset(0f, (size.height + height) / 2f),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TransportControls(
    bookTitle: String,
    currentChapterTitle: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    /** #1474 — the temporary tap-series sum, or null when no series is open. */
    seekSumLabel: String?,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit
) {
    val previousDescription = stringResource(
        R.string.a11y_player_previous_chapter,
        bookTitle,
        currentChapterTitle
    )
    val backDescription = stringResource(R.string.a11y_player_seek_back, bookTitle, currentChapterTitle)
    val playDescription = stringResource(
        if (isPlaying) R.string.a11y_player_pause else R.string.a11y_player_play,
        bookTitle,
        currentChapterTitle
    )
    // Issue #381: третій стан «Завантаження» поки стрім резолвиться/буфериться —
    // інакше TalkBack озвучує минулий стан («Призупинено») кілька секунд.
    val playbackStateDescription = stringResource(
        when {
            isBuffering -> R.string.a11y_player_state_loading
            isPlaying -> R.string.a11y_player_state_playing
            else -> R.string.a11y_player_state_paused
        }
    )
    val forwardDescription = stringResource(R.string.a11y_player_seek_forward, bookTitle, currentChapterTitle)
    val nextDescription = stringResource(
        R.string.a11y_player_next_chapter,
        bookTitle,
        currentChapterTitle
    )
    // #1474 — the sum floats OVER the transport row, it must not add height:
    // the landscape column is fixed and the undo row can already fill it
    // (a zero-height QuickTools row is exactly the #962 defect class this
    // screen's layout test fences).
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
        TransportIcon(
            PaperInkIcons.SkipBack,
            previousDescription,
            onPreviousChapter,
            testTag = "player_previous_button"
        )
        // F9 (аудит 2026-10-28): the step is IN the glyph — `Rew15`/`Fwd30`
        // carry their own numeral, so the visible «15 с»/«30 с» caption and
        // the mirrored Replay are gone.
        TransportIcon(
            icon = PaperInkIcons.Rew15,
            description = backDescription,
            onClick = onBack,
            testTag = "player_rewind_button",
            glyphSize = PaperInkIconSizes.Transport,
        )
        FilledIconButton(
            onClick = onPlayPause,
            // #885 (wave 3) — the prototype's play control is NOT a circle:
            // `.sl-play { width:80px; height:72px; border-radius:26px }`
            // (`:1201`). The 80×72 box is the point — a wider-than-tall
            // squircle reads as a deliberate control rather than as the M3
            // default FAB, which is what `CornerFull` gave it.
            //
            // 26 px is not in the app's vocabulary, so this uses `RadiusPanel`
            // (24 dp) — 2 dp on a 72 dp button is not perceptible, and
            // inventing a size for it would be the "new variation without
            // collapsing the old" ADR-0033 calls a defect. Same reasoning as
            // the player title's 28 sp against the prototype's 29 px.
            shape = RoundedCornerShape(PaperInkDimens.RadiusLarge),
            modifier = Modifier
                // F8 — the prototype's box is 80×72, not a square: the extra
                // width is what makes it read as a deliberate control.
                .size(
                    width = PaperInkDimens.PlayerPlayButtonWidth,
                    height = PaperInkDimens.PlayerPlayButton,
                )
                .semantics {
                    contentDescription = playDescription
                    stateDescription = playbackStateDescription
                }
                .testTag("player_play_pause_button"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
        ) {
            Icon(
                if (isPlaying) PaperInkIcons.Pause else PaperInkIcons.Play,
                contentDescription = null,
                // F8 — прототип: play-глиф 28 dp; 40 dp перевантажував кнопку.
                modifier = Modifier.size(PaperInkDimens.PlayerPlayGlyph)
            )
        }
        TransportIcon(
            icon = PaperInkIcons.Fwd30,
            description = forwardDescription,
            onClick = onForward,
            testTag = "player_forward_button",
            glyphSize = PaperInkIconSizes.Transport,
        )
        TransportIcon(
            PaperInkIcons.SkipFwd,
            nextDescription,
            onNextChapter,
            testTag = "player_next_button"
        )
        }
        // Temporary and closest to the action: it is not a second clock and
        // never argues with the «Return to …» rewind-undo row.
        if (seekSumLabel != null) {
            Text(
                text = seekSumLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-28).dp)
                    .testTag("player_seek_sum")
            )
        }
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    // #1708 — the geometry test needs the five targets by name; the row is
    // sized by them (4 × 52 dp + the 80 dp play box), so a name per target is
    // what lets the pane test state "these do not overlap" without reading px.
    testTag: String,
    glyphSize: androidx.compose.ui.unit.Dp = PaperInkDimens.PlayerTransportGlyph,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(PaperInkDimens.PlayerTransportTarget)
            .testTag(testTag)
            .semantics { contentDescription = description }
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize)
        )
    }
}

@Composable
private fun QuickTools(
    speed: Float,
    timerMinutes: Int,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit,
    speedFocusRequester: FocusRequester,
    timerFocusRequester: FocusRequester,
    bookmarkFocusRequester: FocusRequester,
    chaptersFocusRequester: FocusRequester
) {
    val speedLabel = stringResource(R.string.a11y_player_tool_speed)
    val speedVisualLabel = stringResource(R.string.player_tool_speed_label)
    val speedVisualValue = stringResource(R.string.player_speed_value, formatSpeed(speed))
    val speedState = stringResource(
        R.string.a11y_player_speed_value,
        formatSpeedForSpeech(speed)
    )
    val timerLabel = stringResource(R.string.a11y_player_tool_timer)
    val timerVisualLabel = stringResource(R.string.player_tool_timer_label)
    val timerState = when {
        timerMinutes > 0 -> pluralStringResource(
            R.plurals.a11y_player_timer_minutes,
            timerMinutes,
            timerMinutes
        )
        timerMinutes == -1 -> stringResource(R.string.a11y_player_timer_end_of_chapter)
        else -> stringResource(R.string.a11y_player_timer_off)
    }
    val timerVisualValue = when {
        timerMinutes > 0 -> stringResource(R.string.player_timer_minutes_value, timerMinutes)
        timerMinutes == -1 -> stringResource(R.string.player_timer_end_value)
        else -> null
    }
    val bookmarkLabel = stringResource(R.string.a11y_player_tool_bookmark)
    val bookmarkVisualLabel = stringResource(R.string.player_tool_bookmark_label)
    val chaptersLabel = stringResource(R.string.a11y_player_tool_chapters)
    val chaptersVisualLabel = stringResource(R.string.player_tool_chapters_label)
    // #1629 — F7/F32 made each tool ONE line (icon + value); that only works
    // when four of them fit. In the landscape player's RIGHT PANE the control
    // column is ~294 dp wide, so four slots are ~73 dp and «Закладка»/
    // «Розділи» were ellipsized on device («Зак…», «Роз…»). Measure the
    // widest visible string instead of guessing: below four full chips, lay
    // the tools out 2×2, where each slot gets twice the width.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelMedium
        val widestVisual = listOf(
            speedVisualValue,
            speedVisualLabel,
            timerVisualValue ?: timerVisualLabel,
            timerVisualLabel,
            bookmarkVisualLabel,
            chaptersVisualLabel
        ).maxOf { text ->
            with(density) {
                measurer.measure(AnnotatedString(text), style = style).size.width.toDp()
            }
        }
        // icon + its 4 dp gap + the text + 8 dp of breathing room per slot
        val neededPerChip = PaperInkIconSizes.Inline + 4.dp + widestVisual + 8.dp
        val compact = maxWidth / 4 < neededPerChip
        if (compact) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuickTool(
                        PaperInkIcons.Speed, speedLabel, speedVisualLabel, speedVisualValue,
                        speedState, "speed_chip", speedFocusRequester, onSpeed, compact = true
                    )
                    QuickTool(
                        PaperInkIcons.Moon, timerLabel, timerVisualLabel, timerVisualValue,
                        timerState, "sleep_timer_chip", timerFocusRequester, onTimer, compact = true
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuickTool(
                        PaperInkIcons.BookmarkAdd, bookmarkLabel, bookmarkVisualLabel, null,
                        null, "add_bookmark_chip", bookmarkFocusRequester, onBookmark, compact = true
                    )
                    QuickTool(
                        PaperInkIcons.List, chaptersLabel, chaptersVisualLabel, null,
                        null, "chapters_chip", chaptersFocusRequester, onChapters, compact = true
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QuickTool(
                    PaperInkIcons.Speed, speedLabel, speedVisualLabel, speedVisualValue,
                    speedState, "speed_chip", speedFocusRequester, onSpeed
                )
                QuickTool(
                    PaperInkIcons.Moon, timerLabel, timerVisualLabel, timerVisualValue,
                    timerState, "sleep_timer_chip", timerFocusRequester, onTimer
                )
                QuickTool(
                    PaperInkIcons.BookmarkAdd, bookmarkLabel, bookmarkVisualLabel, null,
                    null, "add_bookmark_chip", bookmarkFocusRequester, onBookmark
                )
                QuickTool(
                    PaperInkIcons.List, chaptersLabel, chaptersVisualLabel, null,
                    null, "chapters_chip", chaptersFocusRequester, onChapters
                )
            }
        }
    }
}

@Composable
private fun RowScope.QuickTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accessibilityLabel: String,
    visualLabel: String,
    visualValue: String?,
    accessibilityState: String?,
    testTag: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    /** #1629 — 2×2 slot: a full 48 dp target, but no extra vertical padding. */
    compact: Boolean = false
) {
    val toolDescription = accessibilityState?.let {
        stringResource(R.string.a11y_player_tool_state, accessibilityLabel, it)
    } ?: accessibilityLabel
    // F7/F32 (аудит 2026-10-28) — ONE line per tool: the icon and its value
    // share a row. The old column stacked «1.0×» over «Швидкість», which broke
    // the shared baseline of the four tools and spent ~20 dp of the artwork
    // slot on a word the screen reader already carries in the description.
    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
            // A physical FocusRequester needs an explicit target; clickable
            // alone is not sufficient for modal return focus.
            .focusable()
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = toolDescription
            }
            .padding(vertical = if (compact) 0.dp else AppDimens.SpaceSm)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(PaperInkIconSizes.Inline),
        )
        Spacer(Modifier.width(AppDimens.SpaceXs))
        Text(
            visualValue ?: visualLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .testTag("${testTag}_value")
                .semantics { hideFromAccessibility() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksListSheet(
    workTitle: String,
    bookmarks: List<BookmarkEntity>,
    onSelect: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    var bookmarkToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deleteOriginFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var returnToHeadingAfterDelete by remember { mutableStateOf(false) }
    val paneTitle = stringResource(R.string.a11y_player_bookmarks_pane)
    RestoreFocusAfterModal(
        modalVisible = bookmarkToDelete != null,
        returnFocusRequester = if (returnToHeadingAfterDelete) {
            headingFocusRequester
        } else {
            deleteOriginFocusRequester
        },
        fallbackFocusRequester = headingFocusRequester,
        onFocusRestored = {
            deleteOriginFocusRequester = null
            returnToHeadingAfterDelete = false
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .accessibilityModalBackground(bookmarkToDelete != null)
            .testTag("bookmarks_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = paneTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("bookmarks_sheet_heading")
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                Icon(
                    PaperInkIcons.Close,
                    contentDescription = stringResource(R.string.a11y_player_bookmarks_close)
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppDimens.SpaceLg)
        ) {
            itemsIndexed(bookmarks, key = { _, bookmark -> bookmark.id }) { _, bookmark ->
                val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
                val deleteFocusRequester = remember(bookmark.id) { FocusRequester() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.TouchTarget)
                        .clickable { onSelect(bookmark) }
                        .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaperInkIcon(PaperInkIcons.BookmarkFilled, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(AppDimens.SpaceSm))
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.chapterTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (bookmark.note.isBlank()) timestamp else "$timestamp · ${bookmark.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            deleteOriginFocusRequester = deleteFocusRequester
                            returnToHeadingAfterDelete = false
                            bookmarkToDelete = bookmark
                        },
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .focusRequester(deleteFocusRequester)
                            .testTag("bookmarks_sheet_delete_${bookmark.id}")
                    ) {
                        Icon(
                            PaperInkIcons.Trash,
                            contentDescription = stringResource(
                                R.string.a11y_player_bookmark_delete,
                                bookmark.chapterTitle,
                                timestamp
                            ),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    bookmarkToDelete?.let { bookmark ->
        BookmarkDeleteConfirmation(
            workTitle = workTitle,
            bookmark = bookmark,
            onConfirm = {
                // The destructive action removes its own launcher. Return to
                // the sheet heading rather than trying to focus a stale row.
                returnToHeadingAfterDelete = true
                onDelete(bookmark)
                bookmarkToDelete = null
            },
            onDismiss = {
                returnToHeadingAfterDelete = false
                bookmarkToDelete = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarkBottomSheet(
    timestampSeconds: Long,
    chapterTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var note by rememberSaveable { mutableStateOf("") }
    val headingFocusRequester = remember { FocusRequester() }
    val paneTitle = stringResource(R.string.a11y_bookmark_pane)
    val closeDescription = stringResource(R.string.a11y_bookmark_close)
    val positionDescription = stringResource(
        R.string.a11y_bookmark_context,
        chapterTitle,
        MainViewModel.formatTime(timestampSeconds)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("bookmark_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl, vertical = AppDimens.SpaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    paneTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .semantics { heading() }
                        .testTag("bookmark_sheet_heading")
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                    Icon(PaperInkIcons.Close, contentDescription = closeDescription)
                }
            }
            Spacer(Modifier.height(AppDimens.SpaceXs))
            Text(
                "$chapterTitle · ${MainViewModel.formatTime(timestampSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("bookmark_position_context")
                    .clearAndSetSemantics { contentDescription = positionDescription }
            )
            Spacer(Modifier.height(AppDimens.SpaceLg))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.listen_note_optional)) },
                modifier = Modifier.fillMaxWidth().testTag("bookmark_note_input"),
                minLines = 2,
                maxLines = 3
            )
            Spacer(Modifier.height(AppDimens.SpaceLg))
            Button(
                onClick = { onSave(note); onDismiss() },
                modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.TouchTarget).testTag("save_bookmark_button")
            ) { Text(stringResource(R.string.book_detail_bookmark_save)) }
            Spacer(Modifier.height(AppDimens.SpaceLg))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterBottomSheet(
    chapters: List<ChapterEntity>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onFeedback: (() -> Unit)? = null
) {
    val headingFocusRequester = remember { FocusRequester() }
    val paneTitle = stringResource(R.string.a11y_chapter_pane)
    val closeDescription = stringResource(R.string.a11y_chapter_close)
    val currentDescription = stringResource(R.string.a11y_chapter_current)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("chapter_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.player_chapters_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .semantics { heading() }
                        .testTag("chapter_sheet_heading")
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                    Icon(PaperInkIcons.Close, contentDescription = closeDescription)
                }
            }
            Spacer(Modifier.height(AppDimens.SpaceMd))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .testTag("chapter_sheet_list")
                    .selectableGroup()
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    val isCurrent = index == selectedIndex
                    val visibleDuration = if (chapter.durationSeconds > 0L) {
                        MainViewModel.formatTime(chapter.durationSeconds)
                    } else {
                        stringResource(R.string.a11y_player_duration_unknown)
                    }
                    val spokenDuration = if (chapter.durationSeconds > 0L) {
                        visibleDuration
                    } else {
                        stringResource(R.string.a11y_duration_unknown_value)
                    }
                    val chapterDescription = stringResource(
                        R.string.a11y_chapter_option,
                        chapter.title,
                        spokenDuration,
                        if (isCurrent) " $currentDescription" else ""
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppDimens.TouchTarget)
                            .selectable(
                                selected = isCurrent,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) }
                            )
                            .semantics { contentDescription = chapterDescription }
                            .padding(vertical = AppDimens.SpaceMd)
                            .testTag("chapter_option_$index"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(PaperInkIcons.Wave, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppDimens.SpaceMd))
                        }
                        Text(
                            chapter.title,
                            modifier = Modifier
                                .weight(1f)
                                .clearAndSetSemantics { },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            visibleDuration,
                            // Spec-22 T2: tabular figures for durations — no
                            // digit-width drift in the chapter list either.
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag("chapter_option_duration_$index")
                                .semantics { hideFromAccessibility() }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
                if (onFeedback != null) item(key = "feedback") { BookFeedbackEntry(onFeedback) }
            }
            Spacer(Modifier.height(AppDimens.SpaceXl))
        }
    }
}

/** Average a small software bitmap; the tint is decorative and always low-alpha. */
private fun extractArtworkAccent(drawable: Drawable): Color? = runCatching {
    val bitmap = drawable.toBitmap(width = 24, height = 24, config = Bitmap.Config.ARGB_8888)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in 0 until bitmap.height step 2) {
        for (x in 0 until bitmap.width step 2) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) < 128) continue
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
    }
    if (count == 0L) null else Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}.getOrNull()
