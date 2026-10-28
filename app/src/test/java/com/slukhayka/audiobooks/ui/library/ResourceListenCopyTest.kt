package com.slukhayka.audiobooks.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1478 — every shelf word the ListenComposer renders comes from resources for
 * the CURRENT locale. On a Polish phone the shelves must be Polish, not the
 * Ukrainian hardcodes the composer used to bake in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResourceListenCopyTest {

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private fun copy(): ResourceListenCopy =
        ResourceListenCopy(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun blockIds(): List<ListenComposer.BlockId> = ListenComposer.BlockId.entries

    private fun card(seriesIndex: Int?): LibraryBook = LibraryBook(
        book = AudiobookEntity(
            id = "b1",
            title = "Книга",
            author = "Автор",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = ""
        ).also { it.seriesIndex = seriesIndex },
        progress = null,
        cumulativePositionSeconds = 0L,
        totalDurationSeconds = 3600L
    )

    // --- Ukrainian keeps the exact shipped wording ---------------------------

    @Test
    fun `ukrainian copy keeps the shipped wording`() {
        val copy = copy()
        assertEquals("Продовжити слухати", copy.title(ListenComposer.BlockId.HERO))
        assertEquals("Майже дочитали", copy.title(ListenComposer.BlockId.ALMOST_DONE))
        assertEquals("Поверніться", copy.title(ListenComposer.BlockId.RETURN))
        assertEquals("Продовжити серію", copy.title(ListenComposer.BlockId.NEXT_IN_SERIES))
        assertEquals("Готово до поїздки", copy.title(ListenComposer.BlockId.TRAVEL))
        assertEquals("Щось коротке", copy.title(ListenComposer.BlockId.SHORT))
        assertEquals("Улюблені автори", copy.title(ListenComposer.BlockId.FAVORITE_AUTHORS))
        assertEquals("Нещодавно додані", copy.title(ListenComposer.BlockId.RECENTLY_ADDED))
        assertEquals("Прослухані", copy.title(ListenComposer.BlockId.FINISHED))
        assertEquals("До кінця 2 год", copy.almostDoneReason(7_200L))
        assertEquals("Ви слухали 1 день тому", copy.returnReason(1))
        assertEquals("Ви слухали 3 дні тому", copy.returnReason(3))
        assertEquals("Ви слухали 5 днів тому", copy.returnReason(5))
        assertEquals("Наступний том: Сага", copy.nextVolumeReason("Сага"))
        assertEquals("Завантажено · працює офлайн", copy.offlineReadyReason())
        assertEquals("менше години прослуховування", copy.shortReason(1_800L))
        assertEquals("~2 год прослуховування", copy.shortReason(7_200L))
        assertEquals("Ви часто слухаєте цього автора", copy.favoriteAuthorReason())
        assertEquals("Додано цього тижня", copy.addedThisWeekReason())
        assertEquals("Завершено: 2", copy.finishedReason(2))
        assertEquals("Частина 2", copy.nextSeriesPartCaption(card(2)))
        assertEquals("Наступна частина", copy.nextSeriesPartCaption(card(null)))
    }

    // --- Other locales are fully translated (no Ukrainian leak) -------------

    @Test
    @Config(qualifiers = "en")
    fun `english shelf copy is fully english`() {
        val copy = copy()
        assertEquals("Continue listening", copy.title(ListenComposer.BlockId.HERO))
        blockIds().forEach { id ->
            val title = copy.title(id)
            assertTrue("title for $id is blank", title.isNotBlank())
            assertFalse("title for $id leaks Cyrillic: $title", cyrillic.containsMatchIn(title))
        }
        assertNoCyrillic(copy.almostDoneReason(7_200L))
        assertNoCyrillic(copy.returnReason(3))
        assertNoCyrillic(copy.nextVolumeReason("Saga"))
        assertNoCyrillic(copy.offlineReadyReason())
        assertNoCyrillic(copy.shortReason(7_200L))
        assertNoCyrillic(copy.favoriteAuthorReason())
        assertNoCyrillic(copy.addedThisWeekReason())
        assertNoCyrillic(copy.finishedReason(2))
        assertNoCyrillic(copy.nextSeriesPartCaption(card(2)))
    }

    @Test
    @Config(qualifiers = "pl")
    fun `polish shelf copy is fully polish`() {
        val copy = copy()
        assertEquals("Słuchaj dalej", copy.title(ListenComposer.BlockId.HERO))
        blockIds().forEach { id ->
            val title = copy.title(id)
            assertTrue("title for $id is blank", title.isNotBlank())
            assertFalse("title for $id leaks Cyrillic: $title", cyrillic.containsMatchIn(title))
        }
        assertNoCyrillic(copy.almostDoneReason(7_200L))
        assertNoCyrillic(copy.returnReason(3))
        assertNoCyrillic(copy.returnReason(5))
        assertNoCyrillic(copy.nextVolumeReason("Saga"))
        assertNoCyrillic(copy.offlineReadyReason())
        assertNoCyrillic(copy.shortReason(7_200L))
        assertNoCyrillic(copy.favoriteAuthorReason())
        assertNoCyrillic(copy.addedThisWeekReason())
        assertNoCyrillic(copy.finishedReason(2))
        assertNoCyrillic(copy.nextSeriesPartCaption(card(2)))
    }

    private fun assertNoCyrillic(value: String) {
        assertFalse("Ukrainian copy leaked on a non-uk locale: $value", cyrillic.containsMatchIn(value))
    }
}
