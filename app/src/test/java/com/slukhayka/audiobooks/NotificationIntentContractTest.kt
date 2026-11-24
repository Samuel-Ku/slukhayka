package com.slukhayka.audiobooks

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1732 / ADR-0052 §6/§7 — the notification extras are one contract.
 *
 * Every producer builds its tap through [NotificationIntents]; the test feeds
 * that exact Intent into [routeNotificationIntent], which is the activity's
 * real reader (`MainActivity.handleDownloadIntent` applies its result). A key
 * renamed on either side therefore leaves the route empty and fails here,
 * instead of shipping a notification that opens the wrong screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationIntentContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a download tap routes to its book detail`() {
        val route = routeNotificationIntent(NotificationIntents.bookDetail(context, "book-1"))

        assertEquals(NotificationRoute(bookId = "book-1"), route)
    }

    @Test
    fun `the playback notification routes to the player`() {
        val route = routeNotificationIntent(NotificationIntents.openPlayer(context))

        assertEquals(NotificationRoute(openPlayer = true), route)
    }

    @Test
    fun `people new routes to the named person`() {
        val route = routeNotificationIntent(
            NotificationIntents.peopleNew(context, name = "Олена", role = PersonRole.NARRATOR)
        )

        assertEquals(
            NotificationRoute(
                openPeopleNew = true,
                peopleNewName = "Олена",
                peopleNewRole = PersonRole.NARRATOR
            ),
            route
        )
    }

    @Test
    fun `people new without an identity still opens the feed`() {
        val route = routeNotificationIntent(
            NotificationIntents.peopleNew(context, name = null, role = null)
        )

        assertEquals(NotificationRoute(openPeopleNew = true), route)
    }

    @Test
    fun `a malformed role degrades to the people feed instead of a crash`() {
        val intent = NotificationIntents.peopleNew(context, "Олена", PersonRole.AUTHOR)
            .putExtra(MainActivity.EXTRA_PEOPLE_NEW_ROLE, "NOT_A_ROLE")

        val route = routeNotificationIntent(intent)

        assertTrue(route.openPeopleNew)
        assertEquals("Олена", route.peopleNewName)
        assertNull(route.peopleNewRole)
    }

    @Test
    fun `a source work routes its import identity`() {
        val identity = KnownBookIdentity(
            title = "Твір", author = "Автор", narrator = "Читець"
        )

        val route = routeNotificationIntent(
            NotificationIntents.sourceWork(
                context,
                sourceId = "source-1",
                sourceUrl = "https://example.test/audio",
                identity = identity
            )
        )

        assertEquals(
            NotificationRoute(
                sourceImport = PendingSourceImport(
                    sourceId = "source-1",
                    sourceUrl = "https://example.test/audio",
                    identity = identity
                )
            ),
            route
        )
    }

    @Test
    fun `a source work without an identity still imports by live fetch`() {
        val route = routeNotificationIntent(
            NotificationIntents.sourceWork(
                context,
                sourceId = "source-1",
                sourceUrl = "https://example.test/audio",
                identity = null
            )
        )

        assertEquals(
            NotificationRoute(
                sourceImport = PendingSourceImport(
                    sourceId = "source-1",
                    sourceUrl = "https://example.test/audio",
                    identity = null
                )
            ),
            route
        )
    }

    @Test
    fun `a source target missing its url is dropped, as the activity already did`() {
        val route = routeNotificationIntent(
            NotificationIntents.sourceWork(
                context,
                sourceId = "source-1",
                sourceUrl = "",
                identity = null
            )
        )

        assertNull(route.sourceImport)
    }

    @Test
    fun `an intent without routing extras is a no-op`() {
        val route = routeNotificationIntent(Intent(context, MainActivity::class.java))

        assertEquals(NotificationRoute(), route)
    }
}
