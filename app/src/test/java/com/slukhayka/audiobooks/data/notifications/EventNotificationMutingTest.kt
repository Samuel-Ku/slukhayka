package com.slukhayka.audiobooks.data.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.personbookmarks.PeopleNewArrivalWorker
import com.slukhayka.audiobooks.data.watch.SourceWatchNotifier
import com.slukhayka.audiobooks.data.watch.SourceWatchPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ADR-0052 §1–3 — the two event posters consult the app-level mute at their
 * posting seam: a disabled family posts nothing, an enabled one posts exactly
 * what it posted before. Muting is a post gate, never a feature switch: the
 * watch and the bookmark stay, and the notification cursor still advances so
 * re-enabling does not dump a stale backlog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EventNotificationMutingTest {

    private lateinit var app: App

    private val mergeKey = "тестова книга|тестовий автор"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(true)
    }

    private fun activeNotifications(): Int =
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .activeNotifications.size

    private fun appearance(): SourceWatchPolicy.Appearance = SourceWatchPolicy.Appearance(
        workIds = listOf(WORK_ID),
        workId = WORK_ID,
        appearedByMergeKey = mapOf(mergeKey to setOf(SOURCE_ID)),
        appearedSourceIds = setOf(SOURCE_ID),
        verdicts = emptyList(),
        next = SourceWatchPolicy.SeenState(
            watched = mapOf(mergeKey to WORK_ID),
            seen = emptyMap()
        )
    )

    private fun seedWatchedWork() = runBlocking {
        app.audiobookDao.upsertWork(
            WorkEntity(
                id = WORK_ID,
                mergeKey = mergeKey,
                title = "Тестова книга",
                author = "Тестовий автор"
            )
        )
        app.audiobookDao.upsertWorkSource(
            WorkSourceEntity(
                id = "ws-$WORK_ID",
                workId = WORK_ID,
                sourceId = SOURCE_ID,
                sourceUrl = "https://sound-books.net/test"
            )
        )
        app.sourceWatchStore.watch(mergeKey, WORK_ID)
    }

    @Test
    fun `muted source appearance posts nothing and keeps the watch`() = runBlocking {
        app.notificationPreferences.setSourceAppearanceEnabled(false)
        seedWatchedWork()

        SourceWatchNotifier.notifyAppearance(app, appearance())

        assertEquals("a muted family must not post", 0, activeNotifications())
        assertTrue(
            "muting the noise must not drop the watch (ADR-0052 §3)",
            app.sourceWatchStore.isWatched(mergeKey)
        )
    }

    @Test
    fun `enabled source appearance posts`() = runBlocking {
        app.notificationPreferences.setSourceAppearanceEnabled(true)
        seedWatchedWork()

        SourceWatchNotifier.notifyAppearance(app, appearance())

        assertEquals("the enabled family keeps its old behaviour", 1, activeNotifications())
    }

    @Test
    fun `muted evaluation still advances seen-state so re-enabling has no backlog`() = runBlocking {
        app.notificationPreferences.setSourceAppearanceEnabled(false)
        app.sourceWatchStore.watch(mergeKey, WORK_ID)

        val appearance = SourceWatchNotifier.evaluateAndNotify(
            app = app,
            state = SourceWatchPolicy.SeenState(watched = mapOf(mergeKey to WORK_ID)),
            catalog = emptyList(),
            mappingVerdicts = listOf(SourceWatchPolicy.MappingVerdict(mergeKey, SOURCE_ID))
        )

        assertNotNull("the appearance still exists for the Work page", appearance)
        assertEquals("nothing was posted", 0, activeNotifications())
        assertEquals(
            "the appearance is recorded, so unmuting does not deliver stale news",
            setOf(SOURCE_ID),
            app.sourceWatchStore.seenFor(mergeKey)
        )
    }

    @Test
    fun `muted people arrivals post nothing but advance the notification cursor`() = runBlocking {
        val key = seedPersonArrival()
        app.notificationPreferences.setPeopleNewArrivalsEnabled(false)

        PeopleNewArrivalWorker.notifyIfNeeded(app)

        assertEquals("a muted family must not post", 0, activeNotifications())
        val bookmark = app.audiobookDao.getPersonBookmark(key.role.storageValue, key.id)
        assertNotNull("muting must not remove the bookmark", bookmark)
        assertEquals(
            "the cursor advances, so unmuting shows only genuinely new arrivals",
            1,
            bookmark!!.lastNotifiedCount
        )
        assertEquals(
            "the in-app new-arrival badge reads lastSeenAt, so muting never hides it",
            0L,
            bookmark.lastSeenAt
        )
    }

    @Test
    fun `enabled people arrivals post`() = runBlocking {
        val key = seedPersonArrival()
        app.notificationPreferences.setPeopleNewArrivalsEnabled(true)

        PeopleNewArrivalWorker.notifyIfNeeded(app)

        assertEquals("the enabled family keeps its old behaviour", 1, activeNotifications())
        assertEquals(
            1,
            app.audiobookDao.getPersonBookmark(key.role.storageValue, key.id)!!.lastNotifiedCount
        )
    }

    private suspend fun seedPersonArrival(): PersonBookmarkKey {
        val author = "Тестовий автор новинок"
        val identity = app.personBookmarks.identity(PersonRole.AUTHOR, author)
        app.audiobookDao.upsertWork(
            WorkEntity(
                id = PEOPLE_WORK_ID,
                mergeKey = "нова книжка|тестовий автор новинок",
                title = "Нова книжка",
                author = author,
                addedAt = System.currentTimeMillis()
            )
        )
        app.audiobookDao.upsertPersonBookmark(
            PersonBookmarkEntity(
                kind = PersonRole.AUTHOR.storageValue,
                id = identity.id,
                displayName = author,
                normalizedName = identity.normalizedName
            )
        )
        return PersonBookmarkKey(PersonRole.AUTHOR, identity.id)
    }

    private companion object {
        const val WORK_ID = "work-notification-mute"
        const val PEOPLE_WORK_ID = "work-people-arrival"
        const val SOURCE_ID = "soundbooks"
    }
}
