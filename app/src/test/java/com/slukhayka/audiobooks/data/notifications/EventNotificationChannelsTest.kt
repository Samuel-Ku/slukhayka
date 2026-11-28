package com.slukhayka.audiobooks.data.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.personbookmarks.PeopleNewArrivalNotificationText
import com.slukhayka.audiobooks.data.personbookmarks.PeopleNewArrivalWorker
import com.slukhayka.audiobooks.data.watch.SourceWatchNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1811 — both event channels are localized, described and updated by id. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EventNotificationChannelsTest {

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private fun assertChannels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        SourceWatchNotifier.ensureChannel(context)
        SourceWatchNotifier.ensureChannel(context)
        val source = requireNotNull(
            manager.getNotificationChannel(SourceWatchNotifier.CHANNEL_ID)
        )
        assertEquals(
            context.getString(R.string.source_watch_notification_channel_name),
            source.name.toString()
        )
        assertEquals(
            context.getString(R.string.source_watch_notification_channel_description),
            source.description.toString()
        )
        assertEquals(
            "the same id must be updated, not duplicated",
            1,
            manager.notificationChannels.count { it.id == SourceWatchNotifier.CHANNEL_ID }
        )

        val copy = PeopleNewArrivalNotificationText.build(
            resources = context.resources,
            count = 1,
            people = listOf("Anna")
        )
        PeopleNewArrivalWorker.ensureChannel(context, copy)
        PeopleNewArrivalWorker.ensureChannel(context, copy)
        val people = requireNotNull(
            manager.getNotificationChannel(PeopleNewArrivalWorker.CHANNEL_ID)
        )
        assertEquals(copy.channelName, people.name.toString())
        assertEquals(copy.channelDescription, people.description.toString())
        assertEquals(
            "the same id must be updated, not duplicated",
            1,
            manager.notificationChannels.count { it.id == PeopleNewArrivalWorker.CHANNEL_ID }
        )

        listOf(source.name, source.description, people.name, people.description).forEach {
            assertFalse("Cyrillic leaked into an EN/PL channel: $it", cyrillic.containsMatchIn(it.toString()))
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `english event channels are localized and described`() {
        assertChannels()
    }

    @Test
    @Config(qualifiers = "pl")
    fun `polish event channels are localized and described`() {
        assertChannels()
    }
}
