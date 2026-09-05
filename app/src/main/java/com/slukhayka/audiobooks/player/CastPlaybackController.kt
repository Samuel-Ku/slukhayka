package com.slukhayka.audiobooks.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastContext
import com.slukhayka.audiobooks.App
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import java.util.Locale
import java.util.concurrent.Executor

/**
 * ADR-0024 (#362): owns the cast session end to end. The receiver NEVER
 * touches the internet — it plays tokenized URLs of the [PlaybackProxy]
 * raised on this phone, and the proxy alone fetches upstream bytes through
 * the shared transport (per-source Referer, privacy route, DoH). While a
 * session is live this controller is the [CastEngineHook] inside
 * [AudioPlayerManager] (one branch point per command, engine boundary only)
 * and its [CastPlayer] becomes the MediaSession player, so the notification,
 * hardware media buttons and the UI keep working unchanged. Wake + Wi-Fi
 * locks keep the proxy alive with the screen off.
 *
 * Honest states (#362 AC): a book whose current chapter has no stream Source
 * cannot cast — the takeover aborts with an explanation, never a mystery;
 * a lost receiver ends the session and the local engine re-prepares PAUSED
 * at the mirrored position (no silent fallback — #363 refines the UX).
 */
class CastPlaybackController(
    private val context: Context,
    private val managerProvider: () -> AudioPlayerManager,
    private val streamUrlHealer: (suspend (String, Int, String) -> String?)? = null,
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val onActiveChanged: (Boolean) -> Unit = {}
) : CastEngineHook, SessionManagerListener<CastSession> {

    private val manager: AudioPlayerManager get() = managerProvider()
    private val fetcher: HttpFetcher = HttpFetcher()

    @Volatile
    private var castPlayer: CastPlayer? = null

    private var proxy: PlaybackProxy? = null

    @Volatile
    private var chapterUrls: List<String?> = emptyList()

    private val healBudgetByChapter = HashMap<Int, Int>()

    private var bookId: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    internal var sessionPlayerSwapper: ((Player?) -> Unit)? = null

    override val isActive: Boolean
        get() = castPlayer != null

    fun bind() {
        val castContext = castContextOrNull() ?: return
        castContext.sessionManager.addSessionManagerListener(this, CastSession::class.java)
    }

    fun isCastAvailable(): Boolean = castContextOrNull() != null

    private fun castContextOrNull(): CastContext? = try {
        if (
            GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        ) {
            CastContext.getSharedInstance(context)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cast unavailable", e)
        null
    }

    override fun onSessionStarted(session: CastSession, sessionId: String) {
        beginCasting(session)
    }

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        beginCasting(session)
    }

    override fun onSessionStarting(session: CastSession) = Unit
    override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
    override fun onSessionEnding(session: CastSession) = Unit
    override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
    override fun onSessionEnded(session: CastSession, error: Int) {
        endCasting()
    }

    private fun beginCasting(session: CastSession) {
        if (isActive) return
        val state = manager.castSnapshot()
        val book = state.currentBook
        val playable = manager.playableTracksForCast()
        val startIndex = state.currentChapterIndex
        val startUrl = playable.getOrNull(startIndex)?.track?.url
        if (book == null || startUrl == null || startIndex !in state.chapters.indices) {
            abortTakeover(session, NO_STREAM_EXPLANATION)
            return
        }
        val wifiAddress = PlaybackProxy(PlaybackProxy.Upstream { _, _ -> null }).wifiBindAddress()
        if (wifiAddress == null) {
            abortTakeover(session, NO_WIFI_EXPLANATION)
            return
        }

        bookId = book.id
        chapterUrls = playable.map { it.track?.url }
        healBudgetByChapter.clear()

        val proxy = PlaybackProxy(upstream = ::upstreamFor, bindAddress = wifiAddress)
        val started = proxy.start()
        if (started == null) {
            abortTakeover(session, PROXY_START_FAILED)
            return
        }
        this.proxy = proxy
        acquireLocks()

        val host = wifiAddress.hostAddress
        if (host == null) {
            abortTakeover(session, NO_WIFI_EXPLANATION)
            return
        }
        val items = state.chapters.mapIndexedNotNull { index, chapter ->
            val url = chapterUrls.getOrNull(index) ?: return@mapIndexedNotNull null
            MediaItem.Builder()
                .setUri(castUri(started, host, index))
                .setMediaId(index.toString())
                .setMimeType(guessMimeType(url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .setTitle(chapter.title)
                        .setArtist(book.author)
                        .setAlbumTitle(book.title)
                        .apply { book.coverImageUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                        .build()
                )
                .build()
        }
        if (items.isEmpty()) {
            endCasting()
            return
        }

        val castContext = castContextOrNull()
        if (castContext == null) {
            endCasting()
            return
        }
        val player = CastPlayer(castContext)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                mirrorFrom(player)
            }
        })
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), state.currentPositionMs)
        player.prepare()
        if (state.isPlaying) player.play()
        player.setPlaybackSpeed(state.playbackSpeed)
        castPlayer = player
        onActiveChanged(true)

        sessionPlayerSwapper?.invoke(player)

        Log.i(TAG, "Casting «${book.title}» ch$startIndex @${state.currentPositionMs}ms")
    }

    private fun abortTakeover(session: CastSession, explanation: String) {
        manager.mirrorCastState { it.copy(lastErrorMsg = explanation) }
        runCatching { castContextOrNull()?.sessionManager?.endCurrentSession(true) }
    }

    private fun endCasting() {
        val player = castPlayer ?: return
        castPlayer = null
        onActiveChanged(false)

        val positionMs = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        val chapterIndex = runCatching { player.currentMediaItemIndex }.getOrDefault(0)
        val speed = runCatching { player.playbackParameters.speed }.getOrDefault(
            manager.castSnapshot().playbackSpeed
        )
        runCatching { player.release() }

        proxy?.stop()
        proxy = null
        releaseLocks()

        sessionPlayerSwapper?.invoke(null)

        val lossMsg = "Пристрій втрачено — натисніть «Продовжити тут», щоб слухати на телефоні."
        manager.mirrorCastState {
            it.copy(isPlaying = false, isBuffering = false, playbackSpeed = speed, lastErrorMsg = lossMsg)
        }
        runCatching { manager.prepareChapter(chapterIndex, positionMs, autoPlay = false) }
        Log.i(TAG, "Cast ended → local paused at ch$chapterIndex @$positionMs")
    }

    private fun upstreamFor(subPath: String?, rangeHeader: String?): PlaybackProxy.UpstreamResponse? {
        val index = subPath?.removePrefix("ch")?.toIntOrNull() ?: return null
        val url = chapterUrls.getOrNull(index) ?: return null
        val headers = baseHeaders(url)

        fun fetch(target: String): HttpFetcher.RangeResponse? =
            fetcher.getRangeStream(
                target,
                if (rangeHeader != null) headers + ("Range" to rangeHeader) else headers
            )

        val first = fetch(url)
        if (first != null) return toUpstreamResponse(first, url)

        val spent = healBudgetByChapter.getOrDefault(index, 0)
        val healer = streamUrlHealer ?: return null
        val id = bookId ?: return null
        if (spent >= StreamHealPolicy.MAX_HEAL_ATTEMPTS) {
            Log.w(TAG, "Upstream gone, heal exhausted ch$index $url")
            return null
        }
        healBudgetByChapter[index] = spent + 1
        val healedUrl = kotlinx.coroutines.runBlocking { healer(id, index, url) }
            ?.takeIf { it != url }
            ?: return null
        chapterUrls = chapterUrls.toMutableList().also { it[index] = healedUrl }
        Log.i(TAG, "Healed ch$index → $healedUrl")
        return fetch(healedUrl)?.let { toUpstreamResponse(it, healedUrl) }
    }

    private fun baseHeaders(url: String): Map<String, String> =
        headersFor(sourceIdForUrl(url), url)

    private fun toUpstreamResponse(
        response: HttpFetcher.RangeResponse,
        url: String
    ): PlaybackProxy.UpstreamResponse =
        PlaybackProxy.UpstreamResponse(
            status = response.status,
            body = response.stream,
            contentLength = response.contentLength,
            contentRange = response.contentRange,
            contentType = guessMimeType(url)
        )

    private fun castUri(started: PlaybackProxy.Started, host: String, index: Int): String =
        "http://$host:${started.port}${started.pathPrefix}/ch$index"

    private fun mirrorFrom(player: Player) {
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        val buffering = runCatching { player.playbackState == Player.STATE_BUFFERING }.getOrDefault(false)
        val position = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        val duration = runCatching { player.duration }.getOrDefault(androidx.media3.common.C.TIME_UNSET)
        val index = runCatching { player.currentMediaItemIndex }.getOrDefault(
            manager.castSnapshot().currentChapterIndex
        )
        manager.mirrorCastState { state ->
            state.copy(
                isPlaying = playing,
                isBuffering = buffering,
                currentPositionMs = position,
                durationMs = duration.takeIf { it > 0 } ?: state.durationMs,
                currentChapterIndex = index.takeIf { it in state.chapters.indices } ?: state.currentChapterIndex
            )
        }
    }

    override fun play() {
        castPlayer?.play()
    }

    override fun pause() {
        castPlayer?.pause()
    }

    override fun seekTo(positionMs: Long) {
        castPlayer?.seekTo(positionMs)
    }

    override fun prepareChapter(chapterIndex: Int, startPositionMs: Long, autoPlay: Boolean) {
        val player = castPlayer ?: return
        if (player.currentMediaItemIndex == chapterIndex) {
            player.seekTo(startPositionMs)
        } else {
            player.seekTo(chapterIndex, startPositionMs)
        }
        if (autoPlay) player.play() else player.pause()
    }

    override fun setPlaybackSpeed(speed: Float) {
        castPlayer?.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        castPlayer?.volume = volume
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cast").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock unavailable", e)
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi")
                .apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock unavailable", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    companion object {
        private const val TAG = "CastController"

        const val NO_STREAM_EXPLANATION =
            "Кастування поки потребує стрім-джерела: у цієї книги немає мережевої доріжки для приймача."
        const val NO_WIFI_EXPLANATION =
            "Кастування працює лише у Wi-Fi мережі разом із телевізором."
        const val PROXY_START_FAILED =
            "Не вдалося підняти проксі відтворення на телефоні."

        fun guessMimeType(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            return when {
                path.endsWith(".m4b") || path.endsWith(".m4a") -> "audio/mp4"
                path.endsWith(".aac") -> "audio/aac"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".flac") -> "audio/flac"
                else -> "audio/mpeg"
            }
        }
    }
}
