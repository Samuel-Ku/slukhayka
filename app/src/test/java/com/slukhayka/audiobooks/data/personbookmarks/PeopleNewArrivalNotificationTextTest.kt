package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1547 — the «Новинки від людей» notification says the truth about the count
 * and speaks the interface language. Before this test the command hardcoded
 * «${count} нові книги», so one new book read «1 нові книги» and EN/PL phones
 * got Ukrainian.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PeopleNewArrivalNotificationTextTest {

    private fun texts(count: Int, people: List<String> = emptyList()): PeopleNewArrivalNotificationText =
        PeopleNewArrivalNotificationText.build(
            resources = ApplicationProvider.getApplicationContext<Context>().resources,
            count = count,
            people = people
        )

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    // --- Ukrainian: the plural forms the old hardcode got wrong --------------

    @Test
    fun `one new book uses the singular form`() {
        assertEquals("1 нова книга", texts(1).title)
    }

    @Test
    fun `a few new books use the few form`() {
        assertEquals("3 нові книги", texts(3).title)
    }

    @Test
    fun `many new books use the many form`() {
        assertEquals("5 нових книг", texts(5).title)
    }

    @Test
    fun `twenty one keeps the singular form`() {
        assertEquals("21 нова книга", texts(21).title)
    }

    // --- Who the alert is about ----------------------------------------------

    @Test
    fun `two people are joined with the localized conjunction`() {
        assertEquals("Від Анна та Богдан", texts(2, listOf("Анна", "Богдан")).body)
    }

    @Test
    fun `one person is named without a conjunction`() {
        assertEquals("Від Анна", texts(1, listOf("Анна")).body)
    }

    @Test
    fun `no names fall back to the localized generic`() {
        assertEquals("Від ваших авторів", texts(1, listOf("", " ")).body)
    }

    @Test
    fun `the action and channel name come from resources too`() {
        val copy = texts(1)
        assertEquals("Переглянути", copy.action)
        assertEquals("Новинки від людей", copy.channelName)
    }

    // --- English and Polish are fully translated ----------------------------

    @Test
    @Config(qualifiers = "en-rUS")
    fun `english notification is english and plural`() {
        assertEquals("1 new book", texts(1).title)
        assertEquals("3 new books", texts(3).title)
        assertEquals("From Anna and Bohdan", texts(2, listOf("Anna", "Bohdan")).body)
        assertEquals("View", texts(1).action)
        assertEquals("New arrivals from people", texts(1).channelName)
        val copy = texts(3, listOf("Anna"))
        listOf(copy.title, copy.body, copy.action, copy.channelName).forEach {
            assertFalse("Ukrainian copy leaked on EN: $it", cyrillic.containsMatchIn(it))
        }
    }

    @Test
    @Config(qualifiers = "pl")
    fun `polish notification is polish and plural`() {
        assertEquals("1 nowa książka", texts(1).title)
        assertEquals("3 nowe książki", texts(3).title)
        assertEquals("5 nowych książek", texts(5).title)
        assertEquals("Od Anna i Bohdan", texts(2, listOf("Anna", "Bohdan")).body)
        assertEquals("Zobacz", texts(1).action)
        assertEquals("Nowości od ludzi", texts(1).channelName)
        val copy = texts(5, listOf("Anna"))
        listOf(copy.title, copy.body, copy.action, copy.channelName).forEach {
            assertFalse("Ukrainian copy leaked on PL: $it", cyrillic.containsMatchIn(it))
        }
    }

    @Test
    fun `the count reached by the worker is what the title says`() {
        val copy = texts(12, listOf("Анна"))
        assertTrue("the title must carry the actual count: ${copy.title}", copy.title.startsWith("12 "))
        assertEquals("12 нових книг", copy.title)
    }
}
