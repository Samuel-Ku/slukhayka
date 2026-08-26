@file:androidx.annotation.OptIn(UnstableApi::class)

package com.slukhayka.audiobooks.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventFilter
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.db.PlaybackEventPolicy
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.listening.ProgressSyncController
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample

/**
 * Spec-22 T5: sleep-timer fade volume for a given remaining-second count.
 * Linear 1.0→0.0 over the last 30 s; outside the fade window volume is
 * untouched (1.0). Extracted as a pure function so the boundary math is
 * unit-testable.
 */
internal fun sleepTimerFadeVolume(remainingSec: Int): Float =
    if (remainingSec in 1..30) remainingSec / 30f else 1.0f

data class PlayerState(
    val currentBook: AudiobookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 1000L,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemainingSeconds: Int = 0,
    // Spec-22 T5: true while the timer is in «до кінця розділу» mode (-1).
    val isSleepTimerEndOfChapter: Boolean = false,
    val isBuffering: Boolean = false,
    val isOfflineMode: Boolean = false,
    val audioEngineMode: String = "4read Audio Engine",
    val currentStreamUrl: String = "",
    val lastErrorMsg: String = "",
    // Position-history undo (wayfinder #25): true while a big accidental seek
    // can be undone back to [undoFromPositionMs].
    val canUndoSeek: Boolean = false,
    val undoFromPositionMs: Long = 0L
)

/**
 * Creates the [Player] that [AudioPlayerManager] drives.
 *
 * This exists purely as a test seam (GitHub issue #4): production builds a
 * real ExoPlayer inside [AudioPlayerManager] (wired to its own HTTP data
 * source factory so per-source headers can be set per book), while JVM unit
 * tests substitute `FakePlayerEngine` so they never touch ExoPlayer, the
 * network, or a real audio device.
 */
fun interface PlayerFactory {
    fun create(context: Context): Player
}

/**
 * AudioPlayerManager wraps Media3 ExoPlayer. This file opts into Media3's
 * `UnstableApi` surface (`@file:androidx.annotation.OptIn` — kotlin's
 * `@OptIn` is a no-op for androidx `@RequiresOptIn` markers) because we
 * intentionally call HttpDataSource.Factory accessors (`setUserAgent`,
 * `setDefaultRequestProperties`, etc.) that are not part of the stable API
 * yet.
 *
 * Historical note: before Phase 2.5 hotfix these calls were unguarded, which
 * caused `./gradlew lintDebug` to fail with 11 `UnsafeOptInUsageError`s and
 * was flagged as CRITICAL finding CR-004 in
 * docs/audits/2026-07-30-static-and-agents.md.
 *
 * Lifecycle change (background playback): the manager now owns ONE
 * long-lived [Player] for its whole lifetime instead of building a fresh
 * player per chapter. This is required for the MediaSession in
 * [PlaybackService] to keep working across chapter switches, and it avoids
 * paying the ExoPlayer construction cost on every chapter boundary.
 */
/**
 * ADR-0024 (#362): while a cast session is live, module-level commands route
 * to the remote engine through this hook at exactly ONE branch point per
 * public method — the engine boundary only. Everything above it (state
 * bookkeeping, listening events, smart rewind, progress persistence) stays
 * shared, so no second «cast logic» path can drift from the local one.
 */
internal interface CastEngineHook {
    val isActive: Boolean

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun prepareChapter(chapterIndex: Int, startPositionMs: Long, autoPlay: Boolean)

    fun setPlaybackSpeed(speed: Float)
}

class AudioPlayerManager(
    private val context: Context,
    // ADR-0002: the player runs on the Listening State Store + the chapter-
    // fetch path, not the whole god module. All listening persistence funnels
    // through [ListeningStateStore]; chapter materialisation (incl. the 4read
    // page fallback) comes in as a suspend fetcher.
    private val listeningState: ListeningStateStore,
    // ADR-0007: the fetch path returns each logical chapter PAIRED with the
    // physical track of the book's primary source (playback resolves chapter
    // → track one-to-one by index: track.localFilePath ?: track.url).
    private val chapterFetcher: suspend (String) -> List<SourceCatalog.PlayableChapter>,
    /**
     * Spec-32 T4 (#234) — the self-healing seam: on a 404/403 stream failure
     * the manager re-resolves the source page through this lambda
     * (bookId, chapterIndex, failedUrl) -> freshUrl or null, and re-prepares
     * ONCE with the fresh URL ([StreamHealPolicy] decides; the budget is
     * one retry per user-initiated chapter prepare). Null in tests that
     * exercise the pre-heal behaviour.
     */
    private val streamUrlHealer: (suspend (String, Int, String) -> String?)? = null,
    private val injectedPlayerFactory: PlayerFactory? = null,
    /** Wall clock, injectable for deterministic smart-rewind tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Global playback preferences; defaults to the real SharedPreferences store. */
    settings: PlaybackSettings? = null,
    /**
     * Dispatcher for the undo-candidate restore seeded on load (spec-16 T3,
     * flake #101). Tests inject the test scheduler so the restore lands
     * deterministically; production keeps the real IO pool. Other speculative
     * writes (event-log rows, bookmarks, progress) stay on Dispatchers.IO.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Spec-22 T4: whether the manager pushes sampled state to the home-screen
     * widget. Production keeps it on; tests disable it so a forever-running
     * widget collector cannot perturb the test scheduler.
     */
    private val widgetSyncEnabled: Boolean = true,
    /**
     * ADR-0023 (spec-43 T6) — Progress Sync: throttled upload at save points,
     * immediate on pauses/completions. Null in tests and without Firebase —
     * then nothing ever leaves the device.
     */
    private val progressSync: ProgressSyncController? = null
) {

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Session telemetry (wayfinder #52): ring buffer of recent events and
     * attempt/failure counters, surfaced in the diagnostic overlay
     * (PlayerDebugOverlay) and the exported journal. In-memory only; failures
     * additionally land in the durable Room ledger via
     * [com.slukhayka.audiobooks.data.listening.ListeningStateStore.recordPlaybackFailure].
     */
    val playbackEventLog = PlaybackEventLog()
    val playbackMetrics = PlaybackMetrics()

    /**
     * The single player instance, created on first playback (or first access
     * from [PlaybackService]) and reused for every chapter until [release].
     * The listener is attached exactly once.
     */
    private var mediaPlayer: Player? = null

    /**
     * Production HTTP data source factory, owned by the manager (spec-13 T2).
     * The playerjs CDN (`*.redirectto.cc`) is shared by audiobookmp3/sluhay/
     * sluhayknigi and 403s without the OWNING source's Referer, so the headers
     * must change per book — the manager sets them as default request
     * properties right before each chapter's prepare. A global hardcoded
     * Referer is impossible (and would leak a Referer onto hosts that need
     * none — SEC-004). Tests inject their own PlayerFactory and never touch
     * this one.
     */
    private val httpDataSourceFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .setAllowCrossProtocolRedirects(false)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(30000)
        // Device-session evidence (spec-13 S04 / spec-14 T6): log the HTTP
        // status and the per-source Referer actually applied for EVERY stream
        // request. This is what proves the redirectto.cc gate on the phone —
        // a 206 with `Referer=https://sluhay.com/` plays, a 403 without it
        // does not. onTransferStart fires right after open(), when the
        // response code is known; file:// (local) chapters never reach this
        // listener (it sits on the HTTP factory only).
        .setTransferListener(object : androidx.media3.datasource.TransferListener {
            override fun onTransferInitializing(
                source: androidx.media3.datasource.DataSource,
                dataSpec: androidx.media3.datasource.DataSpec,
                isReadingFromCache: Boolean
            ) = Unit

            override fun onTransferStart(
                source: androidx.media3.datasource.DataSource,
                dataSpec: androidx.media3.datasource.DataSpec,
                isReadingFromCache: Boolean
            ) {
                val http = source as? androidx.media3.datasource.HttpDataSource ?: return
                val referer = lastAppliedStreamHeaders["Referer"]
                Log.d(
                    "AudioPlayer",
                    "HTTP ${http.responseCode} ${dataSpec.uri}${referer?.let { " Referer=$it" } ?: ""}"
                )
            }

            override fun onBytesTransferred(
                source: androidx.media3.datasource.DataSource,
                dataSpec: androidx.media3.datasource.DataSpec,
                isReadingFromCache: Boolean,
                bytesTransferred: Int
            ) = Unit

            override fun onTransferEnd(
                source: androidx.media3.datasource.DataSource,
                dataSpec: androidx.media3.datasource.DataSpec,
                isReadingFromCache: Boolean
            ) = Unit
        })

    /**
     * The player factory actually used: the injected test one, or the
     * production ExoPlayer builder wired to [httpDataSourceFactory].
     */
    private val playerFactory: PlayerFactory = injectedPlayerFactory
        ?: PlayerFactory { playerContext -> buildProductionPlayer(playerContext) }

    /**
     * Production ExoPlayer wired to the manager's own [httpDataSourceFactory]
     * (spec-13 T2).
     *
     * Phase 2.5 hotfix (SEC-004, SEC-018, SEC-019 in the audit report): drop
     * cross-protocol redirects so a cleartext downgrade via 4read is
     * impossible, drop the hardcoded "SM-S918B" User-Agent that leaks a
     * developer's device model, and remove the 4read.org Referer leak that
     * archive.org uses to correlate playback.
     *
     * The HTTP factory is the manager's own instance so its default request
     * properties (per-source Referer for the playerjs CDN) can be set per
     * book right before each chapter prepare. DefaultDataSource wraps it so
     * file:// and content:// URIs from locally-imported books (spec #8 T7 /
     * Block 4) are read via FileDataSource/ContentDataSource instead of being
     * forced through HTTP.
     */
    private fun buildProductionPlayer(playerContext: Context): Player {
        val dataSourceFactory = DefaultDataSource.Factory(playerContext, httpDataSourceFactory)
        val audioAttr = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(playerContext) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // Do not build video renderers for an audio-only app. This
                // prevents MediaCodec resource queries that fail on some
                // device/emulator environments.
            }
        }.setEnableDecoderFallback(true)

        return ExoPlayer.Builder(playerContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttr, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    /**
     * Test seam: the stream headers most recently applied to
     * [httpDataSourceFactory] (empty for sources that need none).
     */
    internal var lastAppliedStreamHeaders: Map<String, String> = emptyMap()

    /** Access for [PlaybackService] to build its [androidx.media3.session.MediaSession]. */
    val player: Player get() = ensurePlayerCreated()

    /**
     * ADR-0024 (#362): the cast controller installs itself here once at
     * composition time; null (tests, pre-cast builds) means every command
     * behaves exactly as before. The hook is consulted only through
     * [castEngineHook] branch points.
     */
    internal fun attachCastHook(hook: CastEngineHook?) {
        castEngineHook = hook
    }

    private var castEngineHook: CastEngineHook? = null

    /** Read-only snapshot the cast controller needs to build its playlist. */
    internal fun castSnapshot(): PlayerState = _playerState.value

    internal fun playableTracksForCast(): List<SourceCatalog.PlayableChapter> = playableChapters.toList()

    /**
     * ADR-0024 (#362): the receiver is the truth while it plays — the cast
     * controller mirrors position, play state and chapter index into the ONE
     * StateFlow the UI, the progress tracker and the persistence path read,
     * so Listening State stays written from a single place.
     */
    internal fun mirrorCastState(transform: (PlayerState) -> PlayerState) {
        _playerState.value = transform(_playerState.value)
    }

    /** Chapter currently loaded on the player; used by the single listener. */
    private var currentChapter: ChapterEntity? = null

    /** The current book's chapter→track pairing (ADR-0007). */
    private var playableChapters: List<SourceCatalog.PlayableChapter> = emptyList()

    /** The physical track of the currently loaded chapter (null = no stream). */
    private var currentTrack: SourceTrackEntity? = null

    /**
     * Spec-32 T4 (#234): how many self-heal retries the CURRENT chapter
     * prepare has already spent ([StreamHealPolicy.MAX_HEAL_ATTEMPTS] at
     * most). Reset by every user-initiated prepare; the heal retry itself
     * re-prepares with the budget intact, so a dead fresh URL cannot loop.
     */
    private var healAttemptsForChapter = 0

    /** Spec-32 T4 (#234) — honest state after an exhausted heal budget. */
    private val healFailedDetail =
        "Книга зараз недоступна: файл джерела переїхав або заблокований, і оновити його не вдалося."

    /** Whether the current prepare should auto-start once READY. */
    private var shouldAutoPlay: Boolean = false

    /**
     * Resume position to seek EXACTLY ONCE after the next READY transition,
     * or -1 when nothing is pending.
     *
     * Root cause of the 2026-08-08 device bug (resume seek loop): the READY
     * listener used to call `mp.seekTo(_playerState.currentPositionMs)` on
     * EVERY READY. When resuming at a saved position > 0 the state position
     * never advances while the player is in BUFFERING, so every READY re-issued
     * the same seek -> READY -> seek -> BUFFERING -> READY forever (device
     * logcat: buffered position kept resetting to the frozen resume position
     * 452000). Seeking is now a consumed one-shot: [prepareChapter],
     * [seekTo] while buffering and [applySmartRewindIfNeeded] arm it, the READY
     * listener fires it once and disarms.
     */
    private var pendingResumeSeekMs: Long = -1L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Spec-22 T4: keep the home-screen widget's progress/transport in sync
    // with playback. Sampled to ~one update per 2 s while playing (widget
    // updates are expensive); a pause still refreshes within the current
    // window. Fire-and-forget — a failing widget must never break playback.
    init {
        if (widgetSyncEnabled) {
            scope.launch {
                playerState
                    .sample(2_000)
                    .collect { com.slukhayka.audiobooks.widget.AudiobookGlanceWidgetReceiver.update(context) }
            }
        }
    }

    private var updateProgressJob: Job? = null
    private var sleepTimer: CountDownTimer? = null
    private var shakeDetector: ShakeDetector? = null
    private var prepareTimeoutJob: Job? = null

    /** Global playback preferences (wayfinder #26): default speed etc. */
    private val playbackSettings = settings ?: PlaybackSettings(context)

    /** Wall-clock epoch of the last in-session pause (wayfinder #25). */
    private var pausedAtEpochMs: Long? = null

    // --- Spec-16 T2: listening-segment bookkeeping for the capture filter ---
    // The player asks PlaybackEventFilter before recording a transition; these
    // three markers answer its questions without touching the wall clock twice.

    /** When the current listening segment started (load/resume); null while paused. */
    private var playbackSegmentStartMs: Long? = null

    /** The last prepared chapter index; null before the first prepare. */
    private var lastPreparedChapterIndex: Int? = null

    /** Guards the end-of-book completion so the trail records it exactly once. */
    private var completionLogged = false

    // ADR-0007: source-switch tracking is gone — progress/bookmarks are keyed
    // by the Edition, so loading the same book from another source is not a
    // listening-state transition anymore (no SOURCE_SWITCH event).
    private var lastLoadedBookId: String? = null

    /** Position history for the "Повернутися" undo action (wayfinder #25). */
    private val seekHistory = SeekHistory()

    private fun getPlayerContext(): Context {
        return context.applicationContext
    }

    init {
        startProgressTracker()
    }

    /** Creates the player exactly once; subsequent calls return the same instance. */
    private fun ensurePlayerCreated(): Player {
        mediaPlayer?.let { return it }
        return playerFactory.create(getPlayerContext()).also { mp ->
            mp.addListener(playerListener)
            mediaPlayer = mp
        }
    }

    /**
     * Single listener attached once at player creation. Unlike the old
     * per-prepare listener, this one reads the "current chapter" state instead
     * of closing over a chapter local.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                prepareTimeoutJob?.cancel()
                val mp = mediaPlayer ?: return
                val localFile = currentTrack?.localFilePath?.let { java.io.File(it) }
                val isLocal = localFile != null && localFile.exists() && localFile.length() > 100
                _playerState.value = _playerState.value.copy(
                    isBuffering = false,
                    durationMs = if (mp.duration > 0) mp.duration else _playerState.value.durationMs,
                    audioEngineMode = if (isLocal) "Offline Local File" else "4read Direct Stream"
                )
                // Persist the real chapter duration once the stream reports it,
                // so the book's total duration is honest instead of a seeded
                // placeholder ("4:00:00" for every catalogue book).
                persistRealDurationIfKnown(mp.duration)
                applyPlaybackSpeed(_playerState.value.playbackSpeed)
                val validDur = if (mp.duration > 0) mp.duration else _playerState.value.durationMs
                // One-shot resume seek (see [pendingResumeSeekMs]): fire it once
                // and consume it. Never seek from the live state position here -
                // that re-armed on every READY and stalled resume forever.
                val pendingSeek = pendingResumeSeekMs
                pendingResumeSeekMs = -1L
                if (pendingSeek > 0 && pendingSeek < validDur) {
                    try { mp.seekTo(pendingSeek) } catch (_: Exception) {}
                }
                if (shouldAutoPlay || _playerState.value.isPlaying) {
                    try {
                        mp.play()
                        _playerState.value = _playerState.value.copy(isPlaying = true)
                    } catch (e: Exception) {
                        Log.e("AudioPlayer", "Error starting after prepare", e)
                    }
                } else {
                    _playerState.value = _playerState.value.copy(isPlaying = false)
                }
            } else if (playbackState == Player.STATE_ENDED) {
                onChapterCompleted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            prepareTimeoutJob?.cancel()
            Log.w("AudioPlayer", "Stream playback error (${error.errorCodeName}) for URL: ${currentTrack?.url}")
            val responseCode = StreamHealPolicy.responseCodeOf(error)
            // Spec-32 T4 (#234): a 404/403 on a network stream heals — the
            // source page is re-fetched once and the chapter re-prepares
            // with the fresh URL (no heal loops: the budget is one retry per
            // user-initiated chapter prepare). Everything else keeps the
            // honest immediate failure.
            if (StreamHealPolicy.shouldHeal(responseCode, healAttemptsForChapter) &&
                streamUrlHealer != null &&
                isNetworkStream(currentTrack)
            ) {
                attemptSelfHeal()
                return
            }
            // Spec-32 T4 (#234): a 404/403 that already spent the heal budget
            // is the honest «book unavailable» state — the file moved, was
            // retried once with a fresh URL, and is still dead. Any other
            // status keeps the generic primary-stream message.
            if (StreamHealPolicy.budgetExhausted(responseCode, healAttemptsForChapter)) {
                reportHealFailed()
                return
            }
            _playerState.value = _playerState.value.copy(
                lastErrorMsg = "Primary stream error (${error.errorCodeName})"
            )
            reportPlaybackFailure(
                errorCodeName = error.errorCodeName,
                detail = "Primary stream error (${error.errorCodeName})"
            )
        }
    }

    /**
     * Spec-32 T4 (#234): the honest end state of a heal — one re-fetch was
     * spent and the file is still dead. [reportPlaybackFailure] carries the
     * message into [PlayerState.lastErrorMsg] and the failure ledger.
     */
    private fun reportHealFailed() {
        reportPlaybackFailure(
            errorCodeName = "STREAM_HEAL_FAILED",
            detail = healFailedDetail
        )
    }

    /**
     * Spec-32 T4 (#234): whether a stream failure qualifies for self-healing.
     * Mirrors [buildMediaItem]'s source decision exactly: a track plays
     * locally only while its file exists with real content — a stale local
     * path falls back to the network stream, and THAT stream may heal. Pure
     * local playback never re-fetches a page.
     */
    private fun isNetworkStream(track: SourceTrackEntity?): Boolean {
        if (track == null) return false
        val localFile = track.localFilePath?.let { java.io.File(it) }
        val playsLocally = localFile != null && localFile.exists() && localFile.length() > 100
        return !playsLocally && track.url?.startsWith("http", ignoreCase = true) == true
    }

    /**
     * Spec-32 T4 (#234): re-resolves the failed chapter's source page through
     * the healer seam and re-prepares ONCE with the fresh URL. Runs off the
     * player thread (the re-fetch is a suspend network call). A heal that
     * yields nothing surfaces the honest unavailable state.
     */
    private fun attemptSelfHeal() {
        val state = _playerState.value
        val failedUrl = currentTrack?.url
        val bookId = state.currentBook?.id
        val chapterIndex = state.currentChapterIndex
        if (failedUrl == null || bookId == null || streamUrlHealer == null) {
            reportHealFailed()
            return
        }
        healAttemptsForChapter++
        _playerState.value = state.copy(
            isBuffering = true,
            lastErrorMsg = ""
        )
        scope.launch {
            // Fail-open: a dead page contributes nothing — the honest
            // failure stays, exactly one retry was spent.
            val freshUrl = runCatching {
                streamUrlHealer.invoke(bookId, chapterIndex, failedUrl)
            }.getOrNull()
            if (freshUrl == null || freshUrl == failedUrl) {
                reportHealFailed()
                return@launch
            }
            // ADR-0007: swap the physical track's URL (the pairing the next
            // prepare resolves chapter → track by), then re-prepare the same
            // chapter from the last known position. The budget stays spent so
            // a dead fresh URL cannot loop.
            playableChapters = playableChapters.mapIndexed { index, pair ->
                if (index == chapterIndex && pair.track?.url == failedUrl) {
                    pair.copy(track = pair.track!!.copy(url = freshUrl))
                } else {
                    pair
                }
            }
            prepareChapter(
                chapterIndex,
                startPositionMs = _playerState.value.currentPositionMs,
                autoPlay = true,
                resetHealBudget = false
            )
        }
    }

    fun loadAndPlayBook(
        book: AudiobookEntity,
        chapters: List<ChapterEntity>,
        playable: List<SourceCatalog.PlayableChapter> = emptyList(),
        initialChapterIndex: Int = 0,
        initialPositionSeconds: Long = 0L,
        autoPlay: Boolean = true,
        // #40 decision 1: the book page's «Почати спочатку» asks for a
        // deterministic re-listen — reset to chapter 0 / position 0 and log
        // RELISTEN even when the stored end position alone would not trip the
        // position-based rule (e.g. a multi-chapter book whose saved position
        // is the last chapter's in-chapter seconds, below the book total).
        forceRelisten: Boolean = false
    ) {
        // ADR-0007: keep the chapter→track pairing for prepare/build; the
        // display list stays the logical chapters.
        playableChapters = if (playable.isEmpty()) {
            chapters.map { SourceCatalog.PlayableChapter(chapter = it, track = null) }
        } else {
            playable
        }
        var chapterIdx = initialChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        var positionSeconds = initialPositionSeconds
        // Spec-16 T4: starting playback of a finished book is a re-listen — it
        // resets to the beginning (chapter 0, position 0) and logs RELISTEN
        // instead of RESUME. Completion is derived from position, the same rule
        // the library card uses (AC4): only a start at/after the very end of
        // the book triggers it, so explicit chapter or bookmark navigation
        // (which never asks to start at the end) is untouched.
        val totalDurationSeconds = chapters.sumOf { it.durationSeconds }
        val relisten = forceRelisten ||
            (autoPlay && totalDurationSeconds > 0L && positionSeconds >= totalDurationSeconds)
        if (relisten) {
            chapterIdx = 0
            positionSeconds = 0L
        }
        // ADR-0007: no source-switch detection — listening identity is the
        // Edition, not the source that happens to play it. (The old code
        // recorded a SOURCE_SWITCH transition here.)
        lastLoadedBookId = book.id
        _playerState.value = _playerState.value.copy(
            currentBook = book,
            chapters = chapters,
            currentChapterIndex = chapterIdx,
            currentPositionMs = positionSeconds * 1000L,
            durationMs = if (chapters.isNotEmpty()) chapters[chapterIdx].durationSeconds * 1000L else 1000L,
            isOfflineMode = book.isDownloaded,
            // Per-book speed memory (wayfinder #26): the book's own speed if it
            // has one, otherwise the global default. Applied to the engine once
            // the chapter reaches READY.
            playbackSpeed = book.preferredSpeed ?: playbackSettings.defaultSpeed,
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )
        // A fresh load starts a new listening cycle: the sleep timer belongs to
        // the previous listening session and does not follow the new book.
        sleepTimer?.cancel()
        shakeDetector?.stopListening()
        _playerState.value = _playerState.value.copy(
            sleepTimerMinutes = 0,
            sleepTimerRemainingSeconds = 0,
            isSleepTimerEndOfChapter = false
        )
        try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
        // A fresh load starts a new listening cycle: completion resets, chapter
        // history resets, and — when autoplaying — the resume + segment begin.
        completionLogged = false
        lastPreparedChapterIndex = null
        seekHistory.clear()
        if (relisten) {
            playbackSegmentStartMs = now()
            recordPlaybackEvent(PlaybackEventKind.RELISTEN, positionSeconds = 0L)
        } else if (autoPlay) {
            playbackSegmentStartMs = now()
            recordPlaybackEvent(PlaybackEventKind.RESUME, positionSeconds = positionSeconds)
        }

        // Spec-16 T3: a persisted undo candidate from a previous process is
        // restored when the listener is still where the jump landed — the
        // «Повернутися» offer survives a restart. Seeded asynchronously from
        // the event log; skipped if the session already recorded a newer jump
        // (latest candidate wins) or the candidate is stale or far away. The
        // position used is the post-relisten one (0), so a finished book's
        // stale candidate never re-offers.
        val restoredPositionSeconds = positionSeconds
        scope.launch(ioDispatcher) {
            val candidate = listeningState.lastUndoCandidate(book.id)
            if (candidate != null &&
                !seekHistory.canUndo() &&
                !PlaybackEventPolicy.isStaleUndoCandidate(candidate, now()) &&
                PlaybackEventPolicy.isAtUndoPosition(candidate, restoredPositionSeconds)
            ) {
                val from = candidate.fromPositionSeconds ?: return@launch
                seekHistory.restore(
                    SeekJump(from * 1000L, candidate.positionSeconds * 1000L, candidate.chapterIndex)
                )
                _playerState.value = _playerState.value.copy(
                    canUndoSeek = true,
                    undoFromPositionMs = from * 1000L
                )
            }
        }

        prepareChapter(chapterIdx, positionSeconds * 1000L, autoPlay)
    }

    fun prepareChapter(
        chapterIndex: Int,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        // Spec-32 T4 (#234): every user-initiated prepare resets the heal
        // budget; the self-heal retry passes FALSE so a dead fresh URL cannot
        // re-heal in a loop within the same chapter prepare.
        resetHealBudget: Boolean = true
    ) {
        val chapters = _playerState.value.chapters
        if (chapters.isEmpty() || chapterIndex !in chapters.indices) return
        if (resetHealBudget) healAttemptsForChapter = 0

        // Spec-16 T2: a deliberate chapter change (next/previous/select or the
        // auto-advance after a chapter ends) is a discrete transition. The
        // initial load and re-prepares of the same chapter are not — the load
        // records a RESUME instead.
        val previousIndex = lastPreparedChapterIndex
        lastPreparedChapterIndex = chapterIndex
        if (previousIndex != null && previousIndex != chapterIndex) {
            recordPlaybackEvent(
                kind = PlaybackEventKind.CHAPTER_CHANGE,
                chapterIndex = chapterIndex,
                positionSeconds = startPositionMs / 1000L
            )
        }

        val chapter = chapters[chapterIndex]
        val durationMs = chapter.durationSeconds * 1000L
        val track = playableChapters.getOrNull(chapterIndex)?.track

        currentChapter = chapter
        currentTrack = track
        shouldAutoPlay = autoPlay || _playerState.value.isPlaying

        _playerState.value = _playerState.value.copy(
            currentChapterIndex = chapterIndex,
            currentPositionMs = startPositionMs,
            durationMs = durationMs,
            isBuffering = true,
            currentStreamUrl = track?.url.orEmpty(),
            lastErrorMsg = "",
            // A chapter switch is deliberate — the seek history is cleared.
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )

        // Spec-22 T5: «до кінця розділу» follows manual chapter switches.
        rearmEndOfChapterTimerIfActive(chapterIndex, startPositionMs)

        // Arm the one-shot resume seek (see [pendingResumeSeekMs]): it is fired
        // by the READY listener exactly once and consumed, so a resume position
        // can never re-trigger a READY -> seek -> BUFFERING loop.
        pendingResumeSeekMs = startPositionMs.takeIf { it > 0 } ?: -1L

        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = scope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (_playerState.value.isBuffering) {
                Log.w("AudioPlayer", "Primary stream timeout")
                _playerState.value = _playerState.value.copy(
                    lastErrorMsg = "Primary stream timeout (${PREPARE_TIMEOUT_MS / 1000}s)"
                )
                reportPlaybackFailure(
                    errorCodeName = "PREPARE_TIMEOUT",
                    detail = "Primary stream timeout (${PREPARE_TIMEOUT_MS / 1000}s)"
                )
            }
        }

        if (autoPlay) {
            ensurePlaybackServiceStarted()
        }

        playbackMetrics.recordAttempt()
        playbackEventLog.record("PREPARE ch${chapterIndex} ${track?.url}")

        // ADR-0024 (#362): a chapter change while casting re-targets the
        // receiver's playlist item — same state bookkeeping above, remote
        // engine below.
        castEngineHook?.takeIf { it.isActive }?.let { hook ->
            hook.prepareChapter(chapterIndex, startPositionMs, autoPlay)
            return
        }

        try {
            // Spec-13 T2 — per-source stream headers: the playerjs CDN
            // (redirectto.cc) 403s without the owning source's Referer. The
            // manager's HTTP factory reads its default request properties when
            // it CREATES a data source, so setting them before setMediaItem
            // covers every request of this chapter; the next chapter re-sets
            // them (empty for sources that serve plain GETs).
            val headers = _playerState.value.currentBook
                ?.let { headersFor(sourceIdForUrl(it.sourceUrl), track?.url.orEmpty()) }
                ?: emptyMap()
            httpDataSourceFactory.setDefaultRequestProperties(headers)
            lastAppliedStreamHeaders = headers

            // setMediaItem replaces the previous playlist entry and resets the
            // player to IDLE; prepare() re-enters BUFFERING -> READY.
            val mp = ensurePlayerCreated()
            mp.setMediaItem(buildMediaItem(chapter, track))
            mp.prepare()
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel()
            Log.e("AudioPlayer", "Exception in prepareChapter", e)
            reportPlaybackFailure(
                errorCodeName = e::class.java.simpleName,
                detail = "Exception in prepareChapter (${e::class.java.simpleName})"
            )
        }
    }

    /**
     * Failed primary stream (PlaybackException OR 45s prepare timeout).
     *
     * Phase 2.5 hotfix (CR-002 / SF-003 / SF-005 / SF-006 in
     * docs/audits/2026-07-30-static-and-agents.md): the previous
     * implementation walked a hardcoded list of unrelated archive.org / GitHub
     * sample MP3s and silently played them as if they were the user-requested
     * chapter. Users heard time-machine / war-of-the-worlds audio while the
     * UI showed their selected book. That is the textbook "failure that looks
     * like success" and trips every reasonable trust assumption about media
     * playback.
     *
     * The contract: report the failure to PlayerState and let the UI decide
     * what to render. We do NOT synthesize audio from unrelated sources.
     *
     * Unlike the old code, the shared player is intentionally NOT released
     * here: the MediaSession in PlaybackService wraps it, and a subsequent
     * [play] re-prepares the same instance.
     */
    private fun reportPlaybackFailure(
        errorCodeName: String = "UNKNOWN",
        detail: String = "Цю главу зараз не вдалося відтворити. Спробуйте пізніше або інший розділ."
    ) {
        prepareTimeoutJob?.cancel()
        val state = _playerState.value
        val failedUrl = currentTrack?.url ?: state.currentStreamUrl
        playbackMetrics.recordFailure(errorCodeName)
        playbackEventLog.record("FAIL $errorCodeName ${failedUrl}")
        // The host part turns an opaque error into an actionable one ("which
        // server refused the stream"), without leaking credentials: stream
        // URLs never carry secrets.
        val host = failedUrl.toUri().host
        val enriched = if (host != null) "$detail (host: $host)" else detail
        _playerState.value = state.copy(
            isBuffering = false,
            isPlaying = false,
            currentStreamUrl = "",
            audioEngineMode = "Playback error",
            lastErrorMsg = enriched
        )
        val chapter = currentChapter
        val bookId = state.currentBook?.id
        if (bookId != null && chapter != null) {
            scope.launch {
                try {
                    listeningState.recordPlaybackFailure(
                        bookId = bookId,
                        chapterIndex = chapter.chapterIndex,
                        errorCodeName = errorCodeName,
                        streamUrl = failedUrl,
                        audioEngineMode = "Playback error"
                    )
                } catch (e: Exception) {
                    // Observability must never break playback (wayfinder #52).
                    Log.w("AudioPlayer", "Failed to record playback failure", e)
                }
            }
        }
    }

    /**
     * MediaItem with book/chapter metadata so the system media notification
     * (and Android Auto / lock screen) shows a real title instead of a URL.
     */
    private fun buildMediaItem(chapter: ChapterEntity, track: SourceTrackEntity?): MediaItem {
        val book = _playerState.value.currentBook
        val metadata = MediaMetadata.Builder()
            .setTitle(chapter.title)
            .setArtist(book?.author?.ifBlank { null } ?: book?.title)
            .setAlbumTitle(book?.title)
            .setArtworkUri(book?.coverImageUrl?.toUri())
            .build()
        // ADR-0007: playback resolves chapter → track one-to-one by index
        // (track.localFilePath ?: track.url — current behavior preserved
        // exactly).
        val localFile = track?.localFilePath?.let { java.io.File(it) }
        return if (localFile != null && localFile.exists() && localFile.length() > 100) {
            MediaItem.Builder()
                .setUri(Uri.fromFile(localFile))
                .setMediaMetadata(metadata)
                .build()
        } else {
            // Stream headers for this chapter were already applied to the
            // shared HTTP factory by prepareChapter (spec-13 T2) — the media
            // item itself carries only the URI and metadata.
            MediaItem.Builder()
                .setUri(track?.url.orEmpty().toUri())
                .setMediaMetadata(metadata)
                .build()
        }
    }

    /**
     * Starts [PlaybackService] so background playback survives the Activity
     * being destroyed. Called on every user-initiated play path.
     *
     * Uses `startService`, deliberately NOT `startForegroundService`: Media3's
     * MediaSessionService only calls `startForeground()` once playback is
     * actually playing (READY + playWhenReady). With a slow stream the 30s
     * startForeground deadline imposed on startForegroundService() would be
     * exceeded and the system would kill the app with
     * ForegroundServiceDidNotStartInTimeException (observed on device:
     * BUFFERING stream -> crash at 20:47:29). startService has no deadline,
     * and the service promotes itself to foreground the moment playback
     * starts. The call is always user-initiated while the app is in the
     * foreground, so the API 26+ background-start ban does not apply.
     */
    private fun ensurePlaybackServiceStarted() {
        try {
            context.startService(PlaybackService.playIntent(getPlayerContext()))
        } catch (e: Exception) {
            // e.g. IllegalStateException if a background start is ever rejected;
            // playback itself still works.
            Log.w("AudioPlayer", "Unable to start playback service", e)
        }
        // Media3 1.3.1 only promotes the service to foreground (and shows the
        // media notification) when at least one MediaController is connected to
        // the session: `MediaNotificationManager.shouldRunInForeground()`
        // returns false when `getConnectedControllerForSession() == null`.
        // Verified on device (OnePlus 8 Pro): with no controller the service
        // runs as a plain background service and the system kills it after
        // ~90s with "Stopping service due to app idle" even though playback
        // was PLAYING. Connect a controller we keep around for the process
        // lifetime; the UI still drives the same Player directly.
        ensureMediaControllerConnected()
    }

    /**
     * Connects a background [MediaController] to the session hosted by
     * [PlaybackService]. We never issue commands through it (the UI drives the
     * shared Player directly), but its mere presence tells Media3 the session
     * is controller-connected, which unlocks the foreground service + media
     * notification. It also exposes the session to Android Auto / headset
     * media buttons through the service's intent filter.
     */
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private fun ensureMediaControllerConnected() {
        if (mediaController != null || mediaControllerFuture != null) return
        val token = SessionToken(
            getPlayerContext(),
            ComponentName(getPlayerContext(), PlaybackService::class.java)
        )
        val future = MediaController.Builder(getPlayerContext(), token).buildAsync()
        mediaControllerFuture = future
        future.addListener(
            {
                try {
                    mediaController = future.get()
                } catch (e: Exception) {
                    Log.w("AudioPlayer", "MediaController connect failed", e)
                }
            },
            ContextCompat.getMainExecutor(getPlayerContext())
        )
    }

    fun play() {
        val wasPlaying = _playerState.value.isPlaying
        // Spec-16 T2: the resume is recorded only when playback actually
        // starts again, and only after a break long enough to matter (or with
        // no break at all — a fresh start). Read the gap before the smart
        // rewind consumes the pause marker.
        val resumeGapMs = if (wasPlaying) null else pausedAtEpochMs?.let { now() - it }
        _playerState.value = _playerState.value.copy(isPlaying = true)
        ensurePlaybackServiceStarted()
        // Smart rewind (wayfinder #25): coming back from a pause rewinds a few
        // seconds to a few tens of seconds depending on how long the break was.
        applySmartRewindIfNeeded()
        if (!wasPlaying) {
            if (PlaybackEventFilter.shouldRecordResume(resumeGapMs, now())) {
                recordPlaybackEvent(PlaybackEventKind.RESUME)
            }
            playbackSegmentStartMs = now()
        }
        // ADR-0024 (#362): while casting, everything above ran unchanged (the
        // resume event, the segment start, the smart-rewind target written
        // into the state); only the engine command routes remotely.
        castEngineHook?.takeIf { it.isActive }?.let { hook ->
            hook.play()
            return
        }
        if (_playerState.value.isBuffering) return

        val mp = mediaPlayer
        if (mp != null) {
            try {
                // After an error/timeout the player sits in STATE_IDLE with a
                // stale error; re-prepare the current chapter instead of
                // issuing play() into a dead player.
                if (mp.playbackState == Player.STATE_READY || mp.playbackState == Player.STATE_ENDED) {
                    mp.play()
                } else if (currentChapter != null) {
                    prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error resume play", e)
                prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
            }
        } else {
            prepareChapter(_playerState.value.currentChapterIndex, _playerState.value.currentPositionMs, true)
        }
    }

    fun pause() {
        _playerState.value = _playerState.value.copy(isPlaying = false)
        // Record the pause moment (wayfinder #25): the resume rewind depends on
        // how long the listener has been away. Also persisted so a later app
        // restart can rewind too.
        pausedAtEpochMs = now()
        persistPausedAt(pausedAtEpochMs)
        // Spec-16 T2: record the pause only after a listening segment of at
        // least a minute — quick toggles are noise.
        if (PlaybackEventFilter.shouldRecordPause(playbackSegmentStartMs, now())) {
            recordPlaybackEvent(PlaybackEventKind.PAUSE)
        }
        playbackSegmentStartMs = null
        val hook = castEngineHook?.takeIf { it.isActive }
        if (hook != null) {
            // ADR-0024 (#362): the pause bookkeeping above (marker, event,
            // segment end) is shared; only the engine command is remote. A
            // receiver pause persists progress — there is no buffering gate
            // to honour remotely (the mirror state already carries truth).
            hook.pause()
        } else {
            if (_playerState.value.isBuffering) return
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) mp.pause()
                } catch (e: Exception) {
                    Log.e("AudioPlayer", "Error pause", e)
                }
            }
        }
        // ADR-0023 (spec-43 T6): a pause is an honest moment — push at once.
        saveCurrentProgressToDb(immediateSync = true)
    }

    fun togglePlayPause() {
        if (_playerState.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long, recordInHistory: Boolean = true) {
        val prevPos = _playerState.value.currentPositionMs
        val chapterIdx = _playerState.value.currentChapterIndex
        val targetMs = positionMs.coerceIn(0L, _playerState.value.durationMs)

        if (recordInHistory) {
            // Position history (wayfinder #25): a big seek is remembered so the
            // UI can offer "Повернутися" back to where the listener was.
            seekHistory.recordSeek(prevPos, targetMs, chapterIdx)
            val jump = seekHistory.lastJump
            _playerState.value = _playerState.value.copy(
                currentPositionMs = targetMs,
                canUndoSeek = jump != null,
                undoFromPositionMs = jump?.fromPositionMs ?: 0L
            )
            // Spec-16 T2: a big seek is a discrete transition (from → to);
            // sub-threshold seeks and programmatic seeks (recordInHistory =
            // false) are noise and stay out of the log, matching SeekHistory.
            if (PlaybackEventFilter.shouldRecordSeek(prevPos, targetMs)) {
                recordPlaybackEvent(
                    kind = PlaybackEventKind.SEEK,
                    chapterIndex = chapterIdx,
                    positionSeconds = targetMs / 1000L,
                    fromPositionSeconds = prevPos / 1000L
                )
            }
        } else {
            _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        }

        // Seek requested while the engine is still preparing/buffering: the
        // player cannot honour it now, so arm the one-shot READY seek instead.
        // (A target of 0 needs no arm: a fresh prepare starts at 0 anyway.)
        if (_playerState.value.isBuffering) {
            if (targetMs > 0) pendingResumeSeekMs = targetMs
            return
        }

        // ADR-0024 (#362): a remote seek rides the same history bookkeeping
        // above; only the engine command routes to the receiver.
        castEngineHook?.takeIf { it.isActive }?.let { hook ->
            hook.seekTo(targetMs)
            return
        }

        mediaPlayer?.let { mp ->
            try {
                mp.seekTo(targetMs)
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error seeking", e)
            }
        }
        saveCurrentProgressToDb()
    }

    fun skipForward(seconds: Int = 30) {
        seekTo(_playerState.value.currentPositionMs + (seconds * 1000L))
    }

    fun skipBackward(seconds: Int = 15) {
        seekTo(_playerState.value.currentPositionMs - (seconds * 1000L))
    }

    fun nextChapter() {
        val chapters = _playerState.value.chapters
        val nextIdx = _playerState.value.currentChapterIndex + 1
        if (nextIdx in chapters.indices) {
            prepareChapter(nextIdx, startPositionMs = 0L, autoPlay = _playerState.value.isPlaying)
        }
    }

    fun previousChapter() {
        val chapters = _playerState.value.chapters
        val prevIdx = _playerState.value.currentChapterIndex - 1
        if (prevIdx in chapters.indices) {
            prepareChapter(prevIdx, startPositionMs = 0L, autoPlay = _playerState.value.isPlaying)
        } else {
            seekTo(0L)
        }
    }

    fun selectChapter(index: Int) {
        val chapters = _playerState.value.chapters
        if (index in chapters.indices) {
            prepareChapter(index, startPositionMs = 0L, autoPlay = true)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playerState.value = _playerState.value.copy(playbackSpeed = speed)
        castEngineHook?.takeIf { it.isActive }?.let { it.setPlaybackSpeed(speed) } ?: applyPlaybackSpeed(speed)
    }

    /**
     * Remembers [speed] for the current book (wayfinder #26) so the next time
     * this book loads it resumes at that speed. No-op when nothing is playing.
     */
    fun savePreferredSpeed(speed: Float) {
        val book = _playerState.value.currentBook ?: return
        scope.launch(Dispatchers.IO) {
            listeningState.setPreferredSpeed(book.id, speed)
        }
    }

    /** Sets the global default speed applied to books without a saved one (wayfinder #26). */
    fun setDefaultSpeed(speed: Float) {
        playbackSettings.defaultSpeed = speed
    }

    /**
     * The "Повернутися" action (wayfinder #25): jumps back to the position the
     * listener was at before the last big seek.
     */
    fun undoLastSeek() {
        val jump = seekHistory.consumeUndo() ?: return
        _playerState.value = _playerState.value.copy(
            canUndoSeek = false,
            undoFromPositionMs = 0L
        )
        // Spec-16 T3: the jump back is itself a transition — logged as a SEEK
        // event so the log tells the truth. Its from-position is deliberately
        // withheld: with one-undo semantics an undo target must never become
        // the next candidate (that would re-offer the accident on restart).
        recordPlaybackEvent(
            kind = PlaybackEventKind.SEEK,
            chapterIndex = jump.chapterIndex,
            positionSeconds = jump.fromPositionMs / 1000L,
            fromPositionSeconds = null
        )
        if (jump.fromPositionMs == jump.toPositionMs) return
        if (jump.chapterIndex != _playerState.value.currentChapterIndex &&
            jump.chapterIndex in _playerState.value.chapters.indices
        ) {
            prepareChapter(jump.chapterIndex, jump.fromPositionMs, autoPlay = _playerState.value.isPlaying)
        } else {
            // recordInHistory = false: the jump back is not a new candidate
            // (it was just logged explicitly above).
            seekTo(jump.fromPositionMs, recordInHistory = false)
        }
    }

    /**
     * Applies the smart rewind once when resuming from a pause (wayfinder #25,
     * ADR-0003). The rewind is written straight into the player state so it
     * also takes effect on a buffering resume (the READY listener seeks to the
     * state position); a ready engine is seeked directly. The target comes
     * from the ONE pure rule ([SmartRewind.rewoundPositionMs]) shared with the
     * across-restart resume path — no tiers or boundary behavior re-derived
     * here. Unchanged target (short pause / nothing to rewind) skips the seek.
     */
    private fun applySmartRewindIfNeeded() {
        val pausedAt = pausedAtEpochMs ?: return
        // The pause has been consumed either way — keep the in-memory marker and
        // the persisted one in lockstep so a restart never rewinds the same
        // pause twice (code-review: the two markers must not diverge).
        pausedAtEpochMs = null
        persistPausedAt(null)
        val currentPos = _playerState.value.currentPositionMs
        val targetMs = SmartRewind.rewoundPositionMs(currentPos, now() - pausedAt)
        if (targetMs == currentPos) return
        _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        // ADR-0024 (#362): the rewind rule is player-agnostic (ADR-0003) — a
        // pause that happened on the receiver rewinds the receiver too.
        castEngineHook?.takeIf { it.isActive }?.let {
            it.seekTo(targetMs)
            return
        }
        mediaPlayer?.let { mp ->
            try {
                if (mp.playbackState == Player.STATE_READY) {
                    mp.seekTo(targetMs)
                } else {
                    // Engine not ready yet: arm the one-shot seek so the rewind
                    // position survives the pending READY (same consumed-once
                    // mechanism as [pendingResumeSeekMs]).
                    pendingResumeSeekMs = targetMs
                }
            } catch (_: Exception) {}
        }
    }

    private fun persistPausedAt(epochMs: Long?) {
        val book = _playerState.value.currentBook ?: return
        val bookId = book.id
        scope.launch(Dispatchers.IO) {
            listeningState.updatePausedAt(bookId, epochMs)
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        mediaPlayer?.let { mp ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mp.setPlaybackParameters(PlaybackParameters(speed))
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Speed change error", e)
            }
        }
    }

    /**
     * Arms the sleep timer (spec-22 T5).
     *
     * - `0` — cancels any active timer and restores volume.
     * - `-1` — «до кінця розділу»: stops exactly at the current chapter's
     *   boundary (recomputed on manual chapter switches via [prepareChapter]).
     * - positive — minutes until stop.
     *
     * Both timed modes fade the volume 1.0→0.0 linearly over the last 30 s;
     * during that window a shake gesture (see [ShakeDetector]) restores
     * volume and extends the timer by +15 min.
     */
    fun setSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        shakeDetector?.stopListening()
        if (minutes == 0) {
            _playerState.value = _playerState.value.copy(
                sleepTimerMinutes = 0,
                sleepTimerRemainingSeconds = 0,
                isSleepTimerEndOfChapter = false
            )
            try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
            return
        }

        if (minutes == -1) {
            val state = _playerState.value
            val remainingMs = (state.durationMs - state.currentPositionMs).coerceAtLeast(1000L)
            val remainingSec = (remainingMs / 1000L).toInt()
            _playerState.value = _playerState.value.copy(
                sleepTimerMinutes = -1,
                sleepTimerRemainingSeconds = remainingSec,
                isSleepTimerEndOfChapter = true
            )
            startSleepTimerInternal(remainingMs, isEndOfChapter = true)
            return
        }

        val totalMs = minutes * 60 * 1000L
        _playerState.value = _playerState.value.copy(
            sleepTimerMinutes = minutes,
            sleepTimerRemainingSeconds = minutes * 60,
            isSleepTimerEndOfChapter = false
        )
        startSleepTimerInternal(totalMs, isEndOfChapter = false)
    }

    /**
     * Spec-22 T5: if the timer is in «до кінця розділу» mode, re-arm it for
     * the newly prepared chapter (manual skip or auto-advance) so it still
     * stops exactly at the chapter boundary.
     */
    private fun rearmEndOfChapterTimerIfActive(chapterIndex: Int, startPositionMs: Long) {
        if (!_playerState.value.isSleepTimerEndOfChapter) return
        sleepTimer?.cancel()
        shakeDetector?.stopListening()
        val chapter = _playerState.value.chapters.getOrNull(chapterIndex) ?: return
        val remainingMs =
            (chapter.durationSeconds * 1000L - startPositionMs).coerceAtLeast(1000L)
        _playerState.value = _playerState.value.copy(
            sleepTimerRemainingSeconds = (remainingMs / 1000L).toInt()
        )
        startSleepTimerInternal(remainingMs, isEndOfChapter = true)
    }

    private fun startSleepTimerInternal(totalMs: Long, isEndOfChapter: Boolean) {
        if (shakeDetector == null) {
            shakeDetector = ShakeDetector(context) {
                // Shake during the fade-out window: +15 min and full volume.
                try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
                setSleepTimer(15)
            }
        }

        sleepTimer = object : CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingSec = (millisUntilFinished / 1000L).toInt()
                _playerState.value = _playerState.value.copy(
                    sleepTimerRemainingSeconds = remainingSec
                )
                // Smooth 30 s volume fade 1.0→0.0; shake-to-extend only armed
                // inside the fade window.
                val vol = sleepTimerFadeVolume(remainingSec)
                if (vol < 1.0f) {
                    try { mediaPlayer?.volume = vol } catch (_: Exception) {}
                    shakeDetector?.startListening()
                } else {
                    shakeDetector?.stopListening()
                }
            }

            override fun onFinish() {
                shakeDetector?.stopListening()
                val book = _playerState.value.currentBook
                val chapterIdx = _playerState.value.currentChapterIndex
                val chapters = _playerState.value.chapters
                val chapterTitle = chapters.getOrNull(chapterIdx)?.title ?: "Глава ${chapterIdx + 1}"
                val posSec = _playerState.value.currentPositionMs / 1000L

                if (book != null) {
                    scope.launch(Dispatchers.IO) {
                        listeningState.addBookmark(
                            BookmarkEntity(
                                bookId = book.id,
                                chapterIndex = chapterIdx,
                                chapterTitle = chapterTitle,
                                timestampSeconds = posSec,
                                note = "Авто-закладка (Таймер сну)"
                            )
                        )
                    }
                }

                pause()
                // Spec-16 T2: the timer stop itself is a discrete transition
                // (the pause above records a PAUSE only when the segment was
                // long enough to matter).
                recordPlaybackEvent(
                    kind = PlaybackEventKind.TIMER_STOP,
                    chapterIndex = chapterIdx,
                    positionSeconds = posSec
                )
                try { mediaPlayer?.volume = 1.0f } catch (_: Exception) {}
                _playerState.value = _playerState.value.copy(
                    sleepTimerMinutes = 0,
                    sleepTimerRemainingSeconds = 0,
                    isSleepTimerEndOfChapter = false
                )
            }
        }.start()
    }

    private fun onChapterCompleted() {
        val nextIdx = _playerState.value.currentChapterIndex + 1
        val chapters = _playerState.value.chapters
        if (nextIdx in chapters.indices) {
            prepareChapter(nextIdx, startPositionMs = 0L, autoPlay = true)
        } else {
            _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = _playerState.value.durationMs)
            // Spec-16 T2: reaching the end of the book is a completion — one
            // discrete event per listening cycle (the listener and the progress
            // tracker can both observe the end; completionLogged dedupes).
            if (!completionLogged) {
                completionLogged = true
                recordPlaybackEvent(
                    kind = PlaybackEventKind.COMPLETED,
                    chapterIndex = _playerState.value.chapters.lastIndex.coerceAtLeast(0),
                    positionSeconds = _playerState.value.durationMs / 1000L
                 )
             }
            // ADR-0023 (spec-43 T6): completion is an honest moment too.
            saveCurrentProgressToDb(immediateSync = true)
        }
    }

    private fun startProgressTracker() {
        updateProgressJob?.cancel()
        updateProgressJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val state = _playerState.value
                if (state.isPlaying && !state.isBuffering) {
                    var newPos = state.currentPositionMs
                    val mp = mediaPlayer

                    if (mp != null) {
                        try {
                            if (mp.isPlaying) {
                                newPos = mp.currentPosition
                            }
                        } catch (e: Exception) {
                            // Ignore get position errors
                        }
                    }

                    if (newPos >= state.durationMs) {
                        onChapterCompleted()
                    } else {
                        _playerState.value = state.copy(currentPositionMs = newPos)
                    }

                    if ((newPos / 1000L) % 5 == 0L) {
                        saveCurrentProgressToDb()
                    }
                }
            }
        }
    }

    /**
     * Writes the player-reported duration back into the chapter row when the
     * engine actually knows it. The book's total duration is only recomputed
     * once EVERY chapter has a real duration — until then the site's own
     * "Триває:" value (stored via updateBookStats) stays authoritative, so a
     * partially-played book never shows a shrunken partial sum.
     */
    private fun persistRealDurationIfKnown(durationMs: Long) {
        val book = _playerState.value.currentBook ?: return
        val chapter = currentChapter ?: return
        if (durationMs <= 0L) return
        val seconds = durationMs / 1000L
        scope.launch(Dispatchers.IO) {
            listeningState.updateChapterDuration(chapter.id, seconds)
            val chapters = chapterFetcher(book.id)
            if (chapters.isNotEmpty() && chapters.all { it.chapter.durationSeconds > 0L }) {
                listeningState.updateBookStats(
                    book.id,
                    chapters.size,
                    chapters.sumOf { it.chapter.durationSeconds }
                )
            }
        }
    }

    private fun saveCurrentProgressToDb(immediateSync: Boolean = false) {
        val book = _playerState.value.currentBook ?: return
        val currentChapter = _playerState.value.currentChapterIndex
        val posSec = _playerState.value.currentPositionMs / 1000L
        scope.launch(Dispatchers.IO) {
            // ADR-0007: progress is keyed by the Edition — no source key.
            listeningState.updateProgress(book.id, currentChapter, posSec)
            listeningState.recordListeningTime(5L)
            // ADR-0023 (spec-43 T6): pauses/completions push at once, periodic
            // ticks ride the pacing window; failures stay silent.
            progressSync?.pushAfterSave(book.id, immediate = immediateSync)
        }
    }

    /**
     * Appends a discrete transition to the event log through the repository
     * seam (spec-16 T2). The player never touches the DAO for events — every
     * capture funnels through [com.slukhayka.audiobooks.data.listening.ListeningStateStore.recordPlaybackEvent], which
     * writes the row and compacts the (book, source) bucket. The timestamp is
     * the manager's injectable clock so tests stay free of the wall clock.
     */
    private fun recordPlaybackEvent(
        kind: String,
        chapterIndex: Int = _playerState.value.currentChapterIndex,
        positionSeconds: Long = _playerState.value.currentPositionMs / 1000L,
        fromPositionSeconds: Long? = null
    ) {
        val book = _playerState.value.currentBook ?: return
        val bookId = book.id
        scope.launch(Dispatchers.IO) {
            // ADR-0007: the event log is history — rows are written with
            // sourceKey = "" (the store's default).
            listeningState.recordPlaybackEvent(
                bookId = bookId,
                kind = kind,
                chapterIndex = chapterIndex,
                positionSeconds = positionSeconds,
                fromPositionSeconds = fromPositionSeconds,
                timestampMs = now()
            )
        }
    }

    fun release() {
        sleepTimer?.cancel()
        shakeDetector?.stopListening()
        shakeDetector = null
        updateProgressJob?.cancel()
        // Code-review HIGH #1 (post-Wave-1 review): without this, a prepare
        // timeout coroutine launched by `prepareChapter` can outlive the
        // manager and mutate `_playerState.value` after the test (or a
        // future onStop hook) releases the underlying player.
        prepareTimeoutJob?.cancel()
        pendingResumeSeekMs = -1L
        mediaController?.release()
        mediaController = null
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        mediaControllerFuture = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Stops playback and resets the manager to a pristine, empty state.
     *
     * Spec #8 ticket T3: deleting a book that is currently playing must stop
     * the player and clear the whole [PlayerState] (book, chapters, position)
     * so no "ghost" session survives. Unlike [release] the underlying player
     * instance is kept alive — the app-scoped manager stays usable for the
     * next book the user picks.
     */
    fun stopAndClear() {
        sleepTimer?.cancel()
        sleepTimer = null
        prepareTimeoutJob?.cancel()
        // Deliberately NOT persisting progress here: the caller (deleteBook)
        // removes the book's progress row right after, so a save would race it
        // and could re-insert an orphaned row for a deleted bookId (code-review
        // MEDIUM). The book is gone — its position goes with it.
        mediaPlayer?.let { mp ->
            try {
                mp.pause()
                mp.stop()
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error stopping player", e)
            }
        }
        currentChapter = null
        currentTrack = null
        playableChapters = emptyList()
        shouldAutoPlay = false
        pendingResumeSeekMs = -1L
        // Spec-16 T2: a cleared session has no segment, no source history and
        // no pending completion — the next load starts a fresh trail.
        playbackSegmentStartMs = null
        lastPreparedChapterIndex = null
        completionLogged = false
        lastLoadedBookId = null
        _playerState.value = PlayerState()
    }

    companion object {
        /** Prepare timeout for a chapter stream: 45 seconds. */
        const val PREPARE_TIMEOUT_MS: Long = 45_000L

        /** Speed presets (wayfinder #26): 0.5x–3.0x with a 0.25 step. */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

        const val SPEED_MIN: Float = 0.5f
        const val SPEED_MAX: Float = 3.0f
    }
}
