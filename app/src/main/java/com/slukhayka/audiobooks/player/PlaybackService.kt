package com.slukhayka.audiobooks.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.diagnostics.DiagnosticPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background playback host (audit CRITICAL finding PERF-002 / PERF-021:
 * "No foreground service / MediaSessionService — on Android 8+ (especially
 * 12+) background audio is killed within minutes").
 *
 * The service wraps the application-scoped player from [App.playerManager] in
 * a [MediaSession]. It deliberately does NOT own the player: the player lives
 * in [AudioPlayerManager] for the process lifetime, so the UI keeps driving it
 * directly (the existing `playerState` StateFlow remains the single source of
 * truth for Compose). Media3's `MediaSessionService` handles the rest:
 *
 *  - moves this service into the foreground and shows the media notification
 *    with transport controls when playback starts (`onUpdateNotification`);
 *  - handles Bluetooth/wired headset media buttons through the session;
 *  - exposes the session to Android Auto / lock-screen / Wear devices via the
 *    `androidx.media3.session.MediaSessionService` intent filter.
 *
 * Known trade-off (documented in docs/audits/...): because the player is not
 * owned by the service, an explicit "swipe app away" that kills the process
 * ends playback. The critical daily-use scenario — app backgrounded, screen
 * off, process alive — is fixed, because the foreground service keeps the
 * process alive for the whole listening session.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * MediaButtonReceiver starts this service with a legacy, session-less
     * ACTION_MEDIA_BUTTON intent; MediaSessionService then asks [onGetSession]
     * with Media3's synthetic legacy controller (UID -1).  The flag is true
     * only for the synchronous super call that performs that handshake, so a
     * third-party bind with UID -1 cannot use the same door.
     */
    private var legacyMediaButtonControllerAllowed = false

    // #805 — the resumption callback resolves the freshest progress row through
    // the database, so the session's future is completed off the session's
    // binder thread. Main.immediate keeps the player itself on the application
    // thread, like every other playback caller.
    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        App.instance.crashContextTracker.updatePlaybackService(DiagnosticPlaybackService.STARTED)
        val sessionActivity = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                // ADR-0052 §6 — the service (playback) notification's tap
                // opens the full player, not merely the app.
                putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, App.instance.playerManager.player)
            .setSessionActivity(sessionActivity)
            .setCallback(PlaybackResumptionCallback())
            .build()
        // ADR-0024 (#362): while a cast session is live, the CastPlayer owns
        // the session (notification + media buttons drive the RECEIVER); when
        // it ends, the local player takes back over. A null argument means
        // «restore local».
        App.instance.castController.sessionPlayerSwapper = { remoteOrNull ->
            mediaSession?.player = remoteOrNull ?: App.instance.playerManager.player
        }
    }

    /**
     * The service is exported for Media3's system-facing controller discovery
     * (notification shade, Bluetooth media buttons, Android Auto/Wear).  That
     * intent filter alone must not hand the session to every third-party app:
     * a [MediaSession] controller can read the queue/metadata and send
     * play/pause/seek commands.  Media3's default callback accepts every
     * controller, so the gate lives here, before the session is returned.
     *
     * Own process, known media controllers and system apps (which covers
     * SystemUI, Bluetooth and OEM variants of Auto/Wear) keep access; unknown
     * third-party UIDs receive null and cannot connect.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isLegacyMediaButtonStart(intent)) return super.onStartCommand(intent, flags, startId)
        legacyMediaButtonControllerAllowed = true
        return try {
            super.onStartCommand(intent, flags, startId)
        } finally {
            legacyMediaButtonControllerAllowed = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = mediaSession ?: return null
        return if (isTrustedController(controllerInfo)) session else null
    }

    private fun isTrustedController(controllerInfo: MediaSession.ControllerInfo): Boolean =
        MediaControllerAccessPolicy.isTrusted(
            ownUid = Process.myUid(),
            callerUid = controllerInfo.uid,
            callerPackages = packageManager.getPackagesForUid(controllerInfo.uid)
                ?.toList()
                .orEmpty(),
            isSystemPackage = ::isSystemPackage,
            legacyMediaButtonController = legacyMediaButtonControllerAllowed,
        )

    private fun isSystemPackage(packageName: String): Boolean =
        try {
            packageManager.getApplicationInfo(packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    override fun onDestroy() {
        App.instance.crashContextTracker.updatePlaybackService(DiagnosticPlaybackService.STOPPED)
        resumeScope.cancel()
        // Release only the session; the shared player stays with App.playerManager.
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /**
     * #805 — media resumption.
     *
     * After the system kills the process, Media3 rebuilds this session around
     * the shared player, which is empty: a play command from the notification
     * shade then has nothing to resume, so the app showed «Не вдалося
     * відновити відтворення» and «Повторити» had nothing to retry.
     *
     * Media3 asks the app for the queue through this callback. The load rides
     * the SAME path the widget and the in-app «Продовжити слухати» tile use
     * ([PlaybackResume]), so the manager's state — book, chapters, position,
     * heal budget, progress saving — stays coherent; handing Media3 raw media
     * items would leave `playerState` empty. The values handed back are built by
     * [PlaybackResume], which is unit-tested without a live session.
     *
     * The returned items are what the manager just put on the player; Media3
     * sets them again for the resumption, and that second prepare is the price
     * of letting the manager own the queue.
     */
    private inner class PlaybackResumptionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            resumeScope.launch {
                result.set(
                    PlaybackResume.itemsFor(
                        playerManager = App.instance.playerManager,
                        libraryEntries = App.instance.libraryEntries,
                        playableFor = { bookId -> App.instance.sourceCatalog.getPlayableChapters(bookId) },
                        player = session.player
                    )
                )
            }
            return result
        }
    }

    companion object {
        /**
         * Intent that starts this service and makes Media3 attach the session
         * (the default `onStartCommand` treats `Intent.ACTION_PLAY` as a media
         * action and calls `onGetSession` + `addSession`).
         */
        fun playIntent(context: Context): Intent =
            Intent(context, PlaybackService::class.java)
                // Legacy media action Media3's DefaultActionFactory recognizes
                // (it routes the intent to onGetSession + attaches the session).
                .setAction("android.intent.action.PLAY")
    }
}

/**
 * Controller admission policy for [PlaybackService].
 *
 * Pure for JVM tests: the service supplies the caller UID, the packages that
 * UID owns and the system-app predicate.  A UID that owns any system package
 * is accepted because OEM media controllers (SystemUI, Bluetooth, Auto, Wear)
 * are pre-installed and their package names vary between devices.
 */
internal object MediaControllerAccessPolicy {

    private val KNOWN_MEDIA_CONTROLLERS = setOf(
        "com.android.systemui",
        "com.android.bluetooth",
        "com.google.android.projection.gearhead",
        "com.google.android.wearable.app",
        "com.google.android.googlequicksearchbox",
    )

    const val LEGACY_CONTROLLER_UID: Int = -1

    fun isTrusted(
        ownUid: Int,
        callerUid: Int,
        callerPackages: List<String>,
        isSystemPackage: (String) -> Boolean,
        legacyMediaButtonController: Boolean = false,
    ): Boolean {
        if (callerUid == ownUid) return true
        if (legacyMediaButtonController && callerUid == LEGACY_CONTROLLER_UID) return true
        return callerPackages.any { packageName ->
            packageName in KNOWN_MEDIA_CONTROLLERS || isSystemPackage(packageName)
        }
    }
}

/**
 * MediaButtonReceiver delivers the legacy media-button handshake as an
 * ACTION_MEDIA_BUTTON intent with **no data URI**.  The same action with a
 * session URI is the notification-transport path, which MediaSessionService
 * resolves without calling [PlaybackService.onGetSession].
 */
internal fun isLegacyMediaButtonStart(intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_MEDIA_BUTTON && intent.data == null
