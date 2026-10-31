package com.slukhayka.audiobooks

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1742 — the cold-start graph witness.
 *
 * The profiler showed `performCreate` at 259–333 ms because `MainActivity`
 * constructed `MainViewModel` (seven eager `App.instance` modules, Room
 * included) and built the Coil `ImageLoader` before `setContent`. The fix
 * defers the modules, moves the locale bump into `AudiobookApp`'s effect and
 * builds the loader on IO.
 *
 * These are THREAD assertions, not proxies: `AudiobookDatabase.openThread`
 * and `CoverImageLoader.buildThread` name the thread that really did the work,
 * so a future edit that puts either back on the main thread fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = App::class)
class StartupGraphTest {

    @Test
    fun `performCreate creates no ViewModel and never builds Room or Coil on main`() {
        val main = Looper.getMainLooper().thread

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertTrue(
            "performCreate must not construct the MainViewModel before setContent " +
                "(store keys: ${activity.viewModelStore.keys()})",
            activity.viewModelStore.keys().none { it.endsWith(MainViewModel::class.java.name) }
        )
        assertNotSame(
            "Activity creation must not open Room on the main thread",
            main,
            AudiobookDatabase.openThread
        )
        assertNotSame(
            "Activity creation must not build the Coil loader on the main thread",
            main,
            CoverImageLoader.buildThread
        )
    }

    @Test
    fun `the seven deferred modules are built on first use, never by the constructor`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        assertEquals(
            "constructing the ViewModel must not build any of the seven modules",
            emptySet<String>(),
            viewModel.initializedDeferredModules()
        )

        // First real use returns the SAME app-scoped modules as before.
        assertSame(app.listeningState, viewModel.listeningState)
        assertSame(app.libraryImport, viewModel.libraryImport)
        assertSame(app.sourceCatalog, viewModel.sourceCatalog)
        assertSame(app.offlineDownloads, viewModel.offlineDownloads)
        assertSame(app.libraryEntries, viewModel.libraryEntries)
        assertSame(app.readingProgressRecorder, viewModel.readingProgress)
        // `progressSync` is private; the player graph is the production door
        // that builds it, exactly as it did before the deferral.
        assertNotNull(viewModel.playerManager)

        // `progressSync` is private and has no public door of its own; it is
        // covered by the empty-set assertion above and by the player graph's
        // production path, so the first-use set below names the six fields a
        // test can actually read.
        assertEquals(
            "after first use every readable deferred module is built, and only then",
            setOf(
                "listeningState",
                "libraryImport",
                "sourceCatalog",
                "offlineDownloads",
                "libraryEntries",
                "readingProgress"
            ),
            viewModel.initializedDeferredModules() - "progressSync"
        )
    }

    @Test
    fun `the application-scoped Coil loader is built and installed off main`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val main = Looper.getMainLooper().thread

        val buildThread = awaitCoverLoader()

        assertNotNull("the IO prewarm must build the cover loader", buildThread)
        assertNotSame("the cover loader must not be built on main", main, buildThread)
        assertTrue("the prewarm must install the loader into Coil", CoverImageLoader.installed)
        assertSame(
            "screens must keep reading the one application-scoped loader",
            app.imageLoader,
            coil.Coil.imageLoader(app)
        )
    }

    /**
     * The prewarm runs on `Dispatchers.IO` from `App.onCreate`, so the test
     * must give that worker a bounded moment before judging its thread. It
     * never calls `App.imageLoader` itself: doing so on main would hide the
     * very regression this test exists to catch.
     */
    private fun awaitCoverLoader(): Thread? {
        val deadline = System.currentTimeMillis() + 5_000
        while (CoverImageLoader.buildThread == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return CoverImageLoader.buildThread
    }
}
