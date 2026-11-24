package com.slukhayka.audiobooks.ui.screens

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1600 — one rating format everywhere.
 *
 * The media-library rating screen used the locale default, so a uk/pl device
 * rendered «4,5» while every other rating surface (collection cards, book
 * detail, reviews) renders «4.5». The decimal dot is the app-wide contract;
 * this test pins it against a comma-decimal default locale.
 */
class LibraryRatingFormatTest {

    private val original = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `the rating keeps a dot on a comma-decimal locale`() {
        Locale.setDefault(Locale.forLanguageTag("uk"))

        assertEquals("4.5", formatRatingAverage(4.46))
        assertEquals("4.0", formatRatingAverage(4.0))
        assertEquals("0.0", formatRatingAverage(0.0))
    }

    @Test
    fun `the rating keeps a dot on a Polish locale too`() {
        Locale.setDefault(Locale.forLanguageTag("pl"))

        assertEquals("4.5", formatRatingAverage(4.5))
    }
}
