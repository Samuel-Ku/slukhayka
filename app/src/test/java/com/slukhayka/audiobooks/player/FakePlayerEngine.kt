package com.slukhayka.audiobooks.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/**
 * Deterministic in-memory stand-in for ExoPlayer (GitHub issue #4).
 *
 * The engine never starts a thread, never opens a socket and never advances a
 * clock on its own. Every state transition is driven explicitly by the test
 * through one of the `simulate*` helpers, so a test that passes does so because
 * of the production logic under test, not because of a timing accident.
 *
 * Design notes answering the questions raised on issue #4:
 * - **Interface vs. wrapper:** we implement the real [Player] interface via
 *   [FakePlayerBase] so `AudioPlayerManager` needs no production-side type
 *   gymnastics; only a [PlayerFactory] seam was added.
 * - **Listener pub/sub:** a plain list plus manual [notifyStateChanged] /
 *   [notifyPlayerError]. A `SharedFlow` would reintroduce dispatcher timing into
 *   tests that are specifically trying to remove it.
 * - **MediaItem:** the real [MediaItem] is retained (see [lastMediaItemUri]) so
 *   tests can assert on the exact URI the manager chose (local file vs. stream).
 * - **Prepare timeout:** the 45s timeout lives in `AudioPlayerManager`'s own
 *   coroutine scope, so tests drive it with `advanceTimeBy` on the injected
 *   test dispatcher. [simulateTimeout] models the player side of that: a stream
 *   that buffers forever and never reaches [Player.STATE_READY].
 */
class FakePlayerEngine(
    private val initialDurationMs: Long = DEFAULT_DURATION_MS
) : FakePlayerBase() {

    private val listeners = mutableListOf<Player.Listener>()
    private val recordedMediaItems = mutableListOf<MediaItem>()
    private val recordedSeeks = mutableListOf<Long>()

    private var playbackState: Int = Player.STATE_IDLE
    private var playing: Boolean = false
    private var positionMs: Long = 0L
    private var durationMs: Long = initialDurationMs
    private var volume: Float = 1.0f
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    /** Number of times `prepare()` was requested. */
    var prepareCount: Int = 0
        private set

    /** Number of times `play()` was requested. */
    var playCount: Int = 0
        private set

    /** Number of times `pause()` was requested. */
    var pauseCount: Int = 0
        private set

    /** Number of times `release()` was requested. */
    var releaseCount: Int = 0
        private set

    /** Number of times `stop()` was requested. */
    var stopCount: Int = 0
        private set

    /** Every [MediaItem] handed to this engine, in call order. */
    val mediaItems: List<MediaItem> get() = recordedMediaItems.toList()

    /** Every seek target handed to this engine, in call order. */
    val seekTargetsMs: List<Long> get() = recordedSeeks.toList()

    /** URI of the most recent [MediaItem], or `null` if nothing was set. */
    val lastMediaItemUri: String?
        get() = recordedMediaItems.lastOrNull()?.localConfiguration?.uri?.toString()

    /** `true` once [release] has been called at least once. */
    val isReleased: Boolean get() = releaseCount > 0

    /** Playback speed most recently applied via `setPlaybackParameters`. */
    val appliedSpeed: Float get() = playbackParameters.speed

    /** Volume most recently applied, used to assert the sleep-timer fade-out. */
    val appliedVolume: Float get() = volume

    // ---------------------------------------------------------------------
    // The 14 Player members AudioPlayerManager actually calls.
    // ---------------------------------------------------------------------

    override fun addListener(p0: Player.Listener) {
        listeners.add(p0)
    }

    override fun removeListener(p0: Player.Listener) {
        listeners.remove(p0)
    }

    override fun setMediaItem(p0: MediaItem) {
        recordedMediaItems.add(p0)
    }

    override fun prepare() {
        prepareCount += 1
        playbackState = Player.STATE_BUFFERING
    }

    override fun play() {
        playCount += 1
        playing = true
    }

    override fun pause() {
        pauseCount += 1
        playing = false
    }

    override fun seekTo(p0: Long) {
        recordedSeeks.add(p0)
        positionMs = p0
    }

    override fun isPlaying(): Boolean = playing

    override fun getPlaybackState(): Int = playbackState

    override fun getCurrentPosition(): Long = positionMs

    override fun getDuration(): Long = durationMs

    override fun setPlaybackParameters(p0: PlaybackParameters) {
        playbackParameters = p0
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setVolume(p0: Float) {
        volume = p0
    }

    override fun getVolume(): Float = volume

    override fun stop() {
        stopCount += 1
        playing = false
        playbackState = Player.STATE_IDLE
    }

    override fun release() {
        releaseCount += 1
        playing = false
        playbackState = Player.STATE_IDLE
        listeners.clear()
    }

    // ---------------------------------------------------------------------
    // Test-facing simulation helpers.
    // ---------------------------------------------------------------------

    /**
     * The stream became playable: report [Player.STATE_READY] with [durationMs]
     * as the resolved track duration.
     *
     * @param durationMs resolved duration; pass a non-positive value to model a
     *   source whose duration is unknown, which makes `AudioPlayerManager` fall
     *   back to the chapter metadata duration.
     */
    fun simulateReady(durationMs: Long = initialDurationMs) {
        this.durationMs = durationMs
        playbackState = Player.STATE_READY
        notifyStateChanged(Player.STATE_READY)
    }

    /**
     * The player advanced to [positionMs] while playing. Mirrors what the real
     * ExoPlayer would report to `startProgressTracker`.
     */
    fun simulatePlayback(positionMs: Long) {
        require(positionMs >= 0L) { "positionMs must not be negative, was $positionMs" }
        playing = true
        playbackState = Player.STATE_READY
        this.positionMs = positionMs
    }

    /** The current media item played to its end. */
    fun simulateEnded() {
        playing = false
        positionMs = durationMs
        playbackState = Player.STATE_ENDED
        notifyStateChanged(Player.STATE_ENDED)
    }

    /**
     * The stream never becomes ready: it stays in [Player.STATE_BUFFERING]
     * forever. Pair this with `advanceTimeBy(PREPARE_TIMEOUT_MS + 1)` on the
     * test dispatcher to exercise `AudioPlayerManager`'s 45s prepare timeout.
     */
    fun simulateTimeout() {
        playbackState = Player.STATE_BUFFERING
        notifyStateChanged(Player.STATE_BUFFERING)
    }

    /** The player failed. Emits [Player.Listener.onPlayerError]. */
    fun simulateError(error: PlaybackException) {
        playing = false
        playbackState = Player.STATE_IDLE
        notifyPlayerError(error)
    }

    /**
     * Convenience overload for the common "network died" case.
     *
     * @see simulateError
     */
    fun simulateNetworkError(): PlaybackException =
        PlaybackException(
            "fake network failure",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ).also { simulateError(it) }

    /** Low-level pub/sub primitive: notify every listener of a state change. */
    fun notifyStateChanged(state: Int) {
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    /** Low-level pub/sub primitive: notify every listener of an is-playing flip. */
    fun notifyIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
    }

    /** Low-level pub/sub primitive: notify every listener of a player error. */
    fun notifyPlayerError(error: PlaybackException) {
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    companion object {
        /** Duration reported by a freshly-created engine: 10 minutes. */
        const val DEFAULT_DURATION_MS: Long = 600_000L

        /** Mirrors the prepare timeout hardcoded in `AudioPlayerManager`. */
        const val PREPARE_TIMEOUT_MS: Long = 45_000L
    }
}

/**
 * [PlayerFactory] that hands out [FakePlayerEngine]s and remembers every one it
 * created. The manager keeps a single long-lived engine per instance, so tests
 * assert on `current`/`engines.size` that no replacement is ever built mid-
 * session.
 */
class RecordingPlayerFactory(
    private val durationMs: Long = FakePlayerEngine.DEFAULT_DURATION_MS
) : PlayerFactory {

    private val created = mutableListOf<FakePlayerEngine>()

    /** Every engine created so far, oldest first. */
    val engines: List<FakePlayerEngine> get() = created.toList()

    /** The engine currently driven by `AudioPlayerManager`. */
    val current: FakePlayerEngine
        get() = created.lastOrNull() ?: error("No player has been created yet")

    override fun create(context: android.content.Context): Player =
        FakePlayerEngine(durationMs).also { created.add(it) }
}
