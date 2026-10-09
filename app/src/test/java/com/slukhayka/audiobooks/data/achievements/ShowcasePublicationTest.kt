package com.slukhayka.audiobooks.data.achievements

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.achievements.achievementNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #705 (T7) — what a public profile is allowed to learn.
 *
 * The ticket says the showcase is published «лише за явним вибором людини» and
 * that «публікуються лише обрані нагороди». This is the list those sentences
 * are about, so this is where they are tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShowcasePublicationTest {

    /** A stand-in catalogue: names for the ids it knows, null for the rest. */
    private val names = mapOf(
        "first_book" to "Нагорода: Перша книга",
        "books_25" to "Нагорода: Прочитано 25 книг",
        "night_watch" to "Нагорода: Нічний дозор",
        "comeback" to "Нагорода: Повернення"
    )
    private val nameOf: (String) -> String? = { names[it] }

    @Test
    fun `nothing pinned publishes nothing`() {
        assertEquals(emptyList<ShowcaseAwardSnapshot>(), ShowcasePublication.of(emptyList(), nameOf))
    }

    @Test
    fun `only the pinned awards are published, in the listener's order`() {
        val published = ShowcasePublication.of(listOf("books_25", "first_book"), nameOf)

        assertEquals(listOf("books_25", "first_book"), published.map { it.id })
        assertEquals(
            listOf("Нагорода: Прочитано 25 книг", "Нагорода: Перша книга"),
            published.map { it.name }
        )
    }

    /**
     * The cap. The showcase holds three; a fourth pin evicts the oldest in the
     * store, and this list must not be a second, weaker door past that limit.
     */
    @Test
    fun `a fourth award is not published`() {
        val published = ShowcasePublication.of(
            listOf("first_book", "books_25", "night_watch", "comeback"),
            nameOf
        )

        assertEquals(ShowcasePublication.MAX_PUBLISHED, published.size)
        assertEquals(listOf("first_book", "books_25", "night_watch"), published.map { it.id })
    }

    /**
     * An id this build cannot name is DROPPED, never published with a
     * placeholder: a public profile must not show an award it cannot describe.
     */
    @Test
    fun `an award this build cannot name is not published`() {
        val published = ShowcasePublication.of(listOf("first_book", "from_a_newer_version"), nameOf)

        assertEquals(listOf("first_book"), published.map { it.id })
    }

    @Test
    fun `a showcase of only unknown ids publishes nothing`() {
        assertTrue(ShowcasePublication.of(listOf("a", "b"), nameOf).isEmpty())
    }

    /**
     * The REAL catalogue through the REAL naming — the same resolver the screen
     * and the notice use — so a published award reads in the profile exactly as
     * it reads in the app.
     *
     * My first version of this test resolved an id to ITSELF, which was
     * circular and proved nothing; it passed while asserting no name at all.
     */
    @Test
    fun `every real award publishes under a real name, never the generic fallback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val generic = context.getString(R.string.achievement_awarded_generic)
        val realNameOf: (String) -> String? = { id ->
            AchievementCatalog.definitions
                .firstOrNull { it.id == id }
                ?.let { achievementNotice(context, id) }
        }

        // The RESOLVER is what must cover the whole catalogue — the published
        // list is capped at three by design, so asserting on it could never say
        // anything about award number four.
        val resolved = AchievementCatalog.definitions.map { realNameOf(it.id) }

        assertTrue("кожна нагорода каталогу мусить мати назву",
            resolved.none { it == null })
        assertTrue("жодна не мусить упасти в загальний фолбек",
            resolved.none { it == generic })

        // And the capped list still works end to end on real ids.
        val published = ShowcasePublication.of(AchievementCatalog.definitions.map { it.id }, realNameOf)
        assertEquals(ShowcasePublication.MAX_PUBLISHED, published.size)
        assertTrue("опубліковане мусить бути назване", published.none { it.name == generic })
    }
}
