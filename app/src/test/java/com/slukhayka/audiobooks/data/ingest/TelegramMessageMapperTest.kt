package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #829 — a message becomes the identity the flow already understands. */
class TelegramMessageMapperTest {

    private fun message(text: String, audio: List<TelegramAudio> = emptyList()) =
        TelegramMessage(id = "42", text = text, audio = audio)

    @Test
    fun `the first real line of the post is the title`() {
        val identity = TelegramMessageMapper.identityFrom(
            message("Кобзар\nТарас Шевченко читає")
        )!!

        assertEquals("Кобзар", identity.title)
        assertEquals("Кобзар\nТарас Шевченко читає", identity.description)
    }

    @Test
    fun `an audio file's own title wins over the post text`() {
        val identity = TelegramMessageMapper.identityFrom(
            message(
                "Слухайте!",
                listOf(TelegramAudio(fileName = "01.mp3", title = "Гайдамаки", durationSeconds = 120))
            )
        )!!

        assertEquals("Гайдамаки", identity.title)
    }

    @Test
    fun `an author is taken only when the post NAMES one`() {
        val named = TelegramMessageMapper.identityFrom(
            message("Кобзар\nАвтор: Тарас Шевченко\nЧитає: Іван Франко")
        )!!
        assertEquals("Тарас Шевченко", named.author)
        assertEquals("Іван Франко", named.narrator)

        val guessed = TelegramMessageMapper.identityFrom(message("Кобзар\nсхоже на Шевченка"))!!
        assertNull("prose is not a name", guessed.author)
        assertNull(guessed.narrator)
    }

    @Test
    fun `a post without a usable title is refused, never invented`() {
        assertNull(TelegramMessageMapper.identityFrom(message("")))
        assertNull(TelegramMessageMapper.identityFrom(message("   \n  \n")))
        assertNull(TelegramMessageMapper.identityFrom(message("#тег\n#ще")))
    }

    @Test
    fun `hashtags alone do not become a title`() {
        val identity = TelegramMessageMapper.identityFrom(message("#аудіокнига\nКобзар"))!!

        assertEquals("Кобзар", identity.title)
    }
}
