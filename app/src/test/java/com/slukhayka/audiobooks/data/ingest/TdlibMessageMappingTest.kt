package com.slukhayka.audiobooks.data.ingest

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #829 — the pure TDLib → domain mapping, on the JVM.
 *
 * Only `TdApi` data classes are touched: building a real `Client` would load
 * the JNI library, which is a device concern (`TelegramLoginSpikeTest`).
 */
class TdlibMessageMappingTest {

    private fun linkInfo(content: TdApi.MessageContent, id: Long = 42L): TdApi.MessageLinkInfo {
        val message = TdApi.Message().apply {
            this.id = id
            this.chatId = -1_004_476_157_917L
            this.content = content
        }
        return TdApi.MessageLinkInfo().apply {
            this.chatId = -1_004_476_157_917L
            this.isPublic = true
            this.message = message
        }
    }

    @Test
    fun `a text post maps its id and body`() {
        val content = TdApi.MessageText(TdApi.FormattedText("Кобзар", emptyArray()), null, null)

        val mapped = linkInfo(content, id = 7L).toTelegramMessage()!!

        assertEquals("7", mapped.id)
        assertEquals("Кобзар", mapped.text)
        assertTrue(mapped.audio.isEmpty())
    }

    @Test
    fun `an audio content maps every audio field`() {
        val audio = TdApi.Audio().apply {
            fileName = "01.mp3"
            title = "Гайдамаки"
            performer = "Іван Франко"
            duration = 321
        }
        val content = TdApi.MessageAudio(audio, TdApi.FormattedText("Слухайте", emptyArray()))

        val mapped = linkInfo(content).toTelegramMessage()!!

        assertEquals("the caption is the post's words", "Слухайте", mapped.text)
        assertEquals(1, mapped.audio.size)
        assertEquals("01.mp3", mapped.audio[0].fileName)
        assertEquals("Гайдамаки", mapped.audio[0].title)
        assertEquals("Іван Франко", mapped.audio[0].performer)
        assertEquals(321, mapped.audio[0].durationSeconds)
    }

    @Test
    fun `a document is audio too and keeps its file name`() {
        val document = TdApi.Document().apply {
            fileName = "kobzar.mp3"
            mimeType = "audio/mpeg"
        }
        val content = TdApi.MessageDocument(document, TdApi.FormattedText("Кобзар", emptyArray()))

        val mapped = linkInfo(content).toTelegramMessage()!!

        assertEquals("Кобзар", mapped.text)
        assertEquals(1, mapped.audio.size)
        assertEquals("kobzar.mp3", mapped.audio[0].fileName)
        assertEquals("a document has no title of its own", null, mapped.audio[0].title)
        assertEquals(0, mapped.audio[0].durationSeconds)
    }

    @Test
    fun `content without audio maps to no audio`() {
        val mapped = linkInfo(TdApi.MessageText(TdApi.FormattedText("текст", emptyArray()), null, null))
            .toTelegramMessage()!!

        assertTrue(mapped.audio.isEmpty())
    }

    @Test
    fun `a link that resolves to no readable message is unavailable`() {
        assertNull(TdApi.MessageLinkInfo().toTelegramMessage())
    }

    @Test
    fun `every membership status maps, restricted included even without the isMember flag`() {
        assertEquals(MemberStatus.MEMBER, TdApi.ChatMemberStatusMember().toMemberStatus())
        assertEquals(MemberStatus.ADMINISTRATOR, TdApi.ChatMemberStatusAdministrator().toMemberStatus())
        assertEquals(MemberStatus.CREATOR, TdApi.ChatMemberStatusCreator().toMemberStatus())
        assertEquals(MemberStatus.RESTRICTED, TdApi.ChatMemberStatusRestricted().toMemberStatus())
        assertEquals(MemberStatus.BANNED, TdApi.ChatMemberStatusBanned().toMemberStatus())
        assertEquals(MemberStatus.LEFT, TdApi.ChatMemberStatusLeft().toMemberStatus())
    }

    @Test
    fun `no status at all is unknown, not a membership`() {
        assertEquals(MemberStatus.UNKNOWN, (null as TdApi.ChatMemberStatus?).toMemberStatus())
    }
}
