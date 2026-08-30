package com.slukhayka.audiobooks.data.downloads

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNotificationActionCoordinatorTest {

    @Test
    fun `notification action reaches application work without a ViewModel`() = runTest {
        val handled = mutableListOf<NotificationAction>()
        val coordinator = DownloadNotificationActionCoordinator(this) { handled += it }

        coordinator.dispatch(NotificationAction.Continue("paused-book"))
        advanceUntilIdle()

        assertEquals(listOf(NotificationAction.Continue("paused-book")), handled)
    }
}
