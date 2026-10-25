package com.slukhayka.audiobooks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1387 — ВИМІР стратегії кваліфікаторів для справжньої польської.
 *
 * Питання, на яке цей тест відповідає: коли на пристрої `pl-PL`, який файл
 * перемагає — `values-pl` (український оверрайд проти Material3) чи
 * `values-pl-rPL` (справжня польська)?
 *
 * `values-pl` мусить ЛИШИТИСЯ українським: він існує, щоб TalkBack не читав
 * польською чужі рядки Material3 (`#1385`). Справжня польська тому потребує
 * КОНКРЕТНІШОГО кваліфікатора, який його переб'є.
 *
 * Це не «перевірка смаку»: доти, доки `pl-rPL` не виміряно, будь-який
 * переклад — лотерея. Якщо цей тест червоний — гіпотеза хибна, і стратегію
 * треба міняти, а не підганяти тест.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "pl-rPL")
class PolishQualifierResolutionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_more_specific_polish_qualifier_beats_the_ukrainian_override() {
        val resolved = context.getString(R.string.lib_year_hero_title, 7)

        assertEquals(
            "на pl-PL мусить перемагати values-pl-rPL (справжня польська), " +
                "а не values-pl (український оверрайд проти Material3)",
            "Mój rok w książkach, 7",
            resolved,
        )
    }
}
