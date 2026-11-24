package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.slukhayka.audiobooks.R

/**
 * F12 of the 2026-10-28 device audit — the direction forbids caps (§3.2:
 * «Капс заборонений; найдрібніший кегль не несе найгучніший регістр»).
 *
 * The Library «Продовжити» eyebrow was 11 sp CAPS *and* used the graphic lamp
 * colour (F4). The source already shipped the string in caps and the composable
 * called `.uppercase()` on top of it, so the loudest register sat on the
 * smallest size. The label is sentence case now, in every language the app
 * ships — a translation that shouts would bring the defect back.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryContinueLabelTest {

    @Test
    @Config(qualifiers = "uk-rUA")
    fun `the Ukrainian eyebrow is sentence case`() {
        assertSentenceCase(RuntimeEnvironment.getApplication().getString(R.string.lib_continue_label))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `the English eyebrow is sentence case`() {
        assertSentenceCase(RuntimeEnvironment.getApplication().getString(R.string.lib_continue_label))
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun `the Polish eyebrow is sentence case`() {
        assertSentenceCase(RuntimeEnvironment.getApplication().getString(R.string.lib_continue_label))
    }

    private fun assertSentenceCase(label: String) {
        assertNotEquals(
            "«$label» is shouted in caps; the direction forbids caps",
            label.uppercase(),
            label,
        )
        assertEquals(
            "«$label» must be sentence case: first letter capital, the rest lowercase",
            label.lowercase().replaceFirstChar { it.titlecase() },
            label,
        )
    }
}
