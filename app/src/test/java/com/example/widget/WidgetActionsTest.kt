package com.example.widget

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTestDefaults
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.example.player.PlaybackService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Spec-17 (#110): the widget's actions must act on the *session* (in place,
 * no app launch). Exercises the real path: [ActionCallback.onAction] →
 * [SessionCommandSender] → MediaController → MediaSession in PlaybackService.
 *
 * Robolectric mechanics: the shadow delivers `onServiceConnected(null, null)`
 * by default, so the test feeds the real session-manager binder from a built
 * [PlaybackService] through [org.robolectric.shadows.ShadowApplication]. The
 * suspend connect/action work runs on a worker thread while the test thread
 * idles the (PAUSED) main looper that delivers the binder callbacks.
 *
 * Scope (see docs/phone-test.md): the session wraps the real ExoPlayer
 * (App.playerManager), so transport *state* (playWhenReady, item index) is
 * asserted — reaching READY/real progress requires a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetActionsTest {

    private lateinit var context: Context
    private lateinit var serviceController: ServiceController<PlaybackService>
    private val glanceId: GlanceId = GlanceAppWidgetUnitTestDefaults.glanceId()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val binder = serviceController.get().onBind(
            Intent(context, PlaybackService::class.java)
                .setAction("androidx.media3.session.MediaSessionService")
        )
        requireNotNull(binder) { "session-manager binder unavailable" }
        shadowOf(context.applicationContext as Application).setComponentNameAndServiceForBindService(
            ComponentName(context, PlaybackService::class.java),
            binder,
        )
    }

    @After
    fun tearDown() {
        serviceController.destroy()
    }

    @Test
    fun `play-pause action toggles the session from paused to playing`() = withSession { controller ->
        assertFalse("fresh player must start paused", controller.playWhenReady)
        SessionCommandSender.send(controller) { if (it.isPlaying) it.pause() else it.play() }
        settle { controller.playWhenReady }
        assertTrue("action must play in place", controller.playWhenReady)
    }

    @Test
    fun `play-pause action toggles the session from playing to paused`() = withSession { controller ->
        SessionCommandSender.send(controller) { it.play() }
        settle { controller.playWhenReady }
        assertTrue(controller.playWhenReady)
        SessionCommandSender.send(controller) { if (it.isPlaying) it.pause() else it.play() }
        settle { !controller.playWhenReady }
        assertFalse("action must pause in place", controller.playWhenReady)
    }

    @Test
    fun `next action moves to the next chapter`() = withSession { controller ->
        SessionCommandSender.send(controller) {
            it.setMediaItems(
                listOf(MediaItem.fromUri("https://example.test/1.mp3"), MediaItem.fromUri("https://example.test/2.mp3"))
            )
            it.prepare()
        }
        settle { controller.currentMediaItemIndex == 0 }

        SessionCommandSender.send(controller) { it.seekToNextMediaItem() }
        settle { controller.currentMediaItemIndex == 1 }
        assertEquals(1, controller.currentMediaItemIndex)
    }

    @Test
    fun `previous action moves to the previous chapter`() = withSession { controller ->
        SessionCommandSender.send(controller) {
            it.setMediaItems(
                listOf(MediaItem.fromUri("https://example.test/1.mp3"), MediaItem.fromUri("https://example.test/2.mp3"))
            )
            it.prepare()
        }
        SessionCommandSender.send(controller) { it.seekToNextMediaItem() }
        settle { controller.currentMediaItemIndex == 1 }

        SessionCommandSender.send(controller) { it.seekToPreviousMediaItem() }
        settle { controller.currentMediaItemIndex == 0 }
        assertEquals(0, controller.currentMediaItemIndex)
    }

    /**
     * Runs [block] against the real session.
     *
     * media3 verifies every controller call against the main looper
     * (`Util.getCurrentOrMainLooper`), so the suspend block runs on the test
     * thread — which IS the Robolectric main thread. While the block is
     * suspended (the actions' internal connect), a pump thread keeps the
     * paused main looper idle so the binder callbacks that complete the
     * connections can run. [settle] then waits for the session's replies to
     * be mirrored into the controller before asserting.
     */
    private fun withSession(block: suspend (MediaController) -> Unit) {
        var controller: MediaController? = null
        var error: Throwable? = null
        val connector = Thread {
            try {
                controller = runBlocking { connect(context) }
            } catch (t: Throwable) {
                error = t
            }
        }
        connector.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (controller == null && error == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        connector.join(10_000)
        val connected = controller ?: run {
            error?.let { throw it }
            error("session not reachable")
        }
        val pumping = AtomicBoolean(true)
        val pump = Thread {
            while (pumping.get()) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        pump.start()
        try {
            runBlocking { withTimeout(60_000) { block(connected) } }
        } finally {
            pumping.set(false)
            pump.join(5_000)
            connected.release()
        }
    }

    /**
     * The controller mirrors session state only once the session's replies
     * arrive on the main looper (drained by the pump thread) — polls until
     * [condition] holds or the deadline passes.
     */
    private suspend fun settle(condition: () -> Boolean) {
        repeat(1200) {
            if (condition()) return
            delay(25)
        }
    }
}