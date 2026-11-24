package com.slukhayka.audiobooks.player

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.media.MediaSessionManager.RemoteUserInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * #1710 — the #1700 controller gate is pinned against the real
 * [PlaybackService], not only against the pure policy.
 *
 * [MediaControllerAccessPolicyTest] proves the admission rules and
 * [PlaybackServiceLegacyStartTest] proves the legacy-intent predicate, but
 * neither fails if `PlaybackService.onGetSession` is reverted to
 * `= mediaSession`: the exported service would hand the queue, metadata and
 * play/pause/seek to any third-party app on the device while the pure tests
 * stay green. These tests construct the service and call the real gate.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceControllerGateTest {

    private lateinit var controller: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService

    @Before
    fun setUp() {
        controller = Robolectric.buildService(PlaybackService::class.java)
        // The gate only needs a real MediaSession, not the app-scoped ExoPlayer
        // and its graph: the inert player keeps this test about admission.
        controller.get().mediaSessionFactory = { context ->
            MediaSession.Builder(context, inertPlayer()).build()
        }
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun ownUidControllerReceivesTheSession() {
        assertNotNull(service.onGetSession(ownUidController()))
    }

    @Test
    fun unknownThirdPartyControllerReceivesNoSession() {
        // Proves the service really has a session, so the null below is the
        // gate's verdict and not a missing-session accident.
        assertNotNull(service.onGetSession(ownUidController()))

        assertNull(service.onGetSession(thirdPartyController()))
    }

    @Test
    fun legacyMediaButtonStartAdmitsSessionOnlyForTheHandshake() {
        // Outside the handshake the synthetic legacy UID (-1) is rejected.
        assertNull(service.onGetSession(legacyController()))
        assertEquals(emptyList<MediaSession>(), service.sessions)

        service.onStartCommand(Intent(Intent.ACTION_MEDIA_BUTTON), 0, 1)

        // Positive half: the handshake reached addSession, which only happens
        // when onGetSession admitted the legacy controller during the
        // synchronous super.onStartCommand call.
        assertEquals("legacy handshake must add the session", 1, service.sessions.size)
        // try/finally restored the flag: the same UID is rejected again.
        assertNull(service.onGetSession(legacyController()))
        assertNull(service.onGetSession(thirdPartyController()))
    }

    private fun ownUidController(): MediaSession.ControllerInfo {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return controllerInfo(
            packageName = context.packageName,
            pid = Process.myPid(),
            uid = Process.myUid(),
        )
    }

    private fun thirdPartyController(): MediaSession.ControllerInfo =
        controllerInfo(packageName = "com.example.attacker", pid = 4242, uid = 4242)

    private fun legacyController(): MediaSession.ControllerInfo =
        controllerInfo(
            packageName = "androidx.media3.session.MediaSessionService",
            pid = -1,
            uid = MediaControllerAccessPolicy.LEGACY_CONTROLLER_UID,
        )

    private fun controllerInfo(
        packageName: String,
        pid: Int,
        uid: Int,
    ): MediaSession.ControllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        RemoteUserInfo(packageName, pid, uid),
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
        /* trusted = */ false,
        Bundle.EMPTY,
    )

    /**
     * A [Player] that MediaSession can wrap without an ExoPlayer: it answers
     * the state queries MediaSession makes while building the session and
     * advertises no playback commands. Every other member is inert.
     */
    @Suppress("UNCHECKED_CAST")
    private fun inertPlayer(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getApplicationLooper" -> Looper.getMainLooper()
            "canAdvertiseSession" -> true
            "getAvailableCommands" -> Player.Commands.EMPTY
            "isCommandAvailable" -> false
            "getPlayerError" -> null
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getRepeatMode" -> Player.REPEAT_MODE_OFF
            "getShuffleModeEnabled" -> false
            "getVideoSize" -> VideoSize.UNKNOWN
            "getDeviceInfo" -> DeviceInfo.UNKNOWN
            "getPlayWhenReady" -> false
            "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
            "getPlaybackState" -> Player.STATE_IDLE
            "isPlaying", "isLoading", "isDeviceMuted" -> false
            "getSeekBackIncrement",
            "getSeekForwardIncrement",
            "getMaxSeekToPreviousPosition" -> 0L
            "getTrackSelectionParameters" -> TrackSelectionParameters.DEFAULT
            "getCurrentTracks" -> Tracks.EMPTY
            "getCurrentCues" -> CueGroup.EMPTY_TIME_ZERO
            "getMediaMetadata", "getPlaylistMetadata" -> MediaMetadata.EMPTY
            "getCurrentTimeline" -> Timeline.EMPTY
            "getAudioAttributes" -> AudioAttributes.DEFAULT
            "getVolume" -> 0f
            "getDeviceVolume" -> 0
            "getCurrentPosition",
            "getDuration",
            "getBufferedPosition",
            "getTotalBufferedDuration",
            "getContentPosition",
            "getContentDuration",
            "getContentBufferedPosition",
            "getCurrentLiveOffset" -> 0L
            "getBufferedPercentage" -> 0
            "getCurrentMediaItem" -> null
            "getMediaItemCount" -> 0
            "getCurrentMediaItemIndex", "getCurrentPeriodIndex", "getCurrentWindowIndex" -> 0
            "toString" -> "InertSessionPlayer"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    } as Player

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Void.TYPE -> Unit
        else -> null
    }
}
