package com.slukhayka.audiobooks.data.ingest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #829 — the session's own rules, on the JVM: a fake [TdlibClient] stands in
 * for TDLib, so no JNI library and no real account are involved.
 */
class TdLibTelegramSessionTest {

    /**
     * Records what was asked and in which order — membership must be about the
     * logged-in LISTENER, in the group the join link points at.
     */
    private class FakeTdlibClient(
        var userId: Long? = 7L,
        var chatId: Long? = -1_004_476_157_917L,
        var status: MemberStatus? = MemberStatus.MEMBER,
        var post: TelegramMessage? = null
    ) : TdlibClient {

        val calls = mutableListOf<String>()
        var askedUsername: String? = null
        var askedChatId: Long? = null
        var askedUserId: Long? = null
        var askedLink: String? = null

        override suspend fun currentUserId(): Long? {
            calls += "me"
            return userId
        }

        override suspend fun publicChatId(username: String): Long? {
            calls += "chat"
            askedUsername = username
            return chatId
        }

        override suspend fun memberStatus(chatId: Long, userId: Long): MemberStatus? {
            calls += "member"
            askedChatId = chatId
            askedUserId = userId
            return status
        }

        override suspend fun message(link: String): TelegramMessage? {
            calls += "link"
            askedLink = link
            return post
        }
    }

    private fun session(client: FakeTdlibClient) = TdLibTelegramSession(client)

    // ---- membership ----

    @Test
    fun `a member of the group is let in`() = runTest {
        assertTrue(session(FakeTdlibClient(status = MemberStatus.MEMBER)).isMember())
    }

    @Test
    fun `administrator, owner and restricted count as membership`() = runTest {
        val memberships = listOf(MemberStatus.ADMINISTRATOR, MemberStatus.CREATOR, MemberStatus.RESTRICTED)

        for (status in memberships) {
            assertTrue("$status is still in the group", session(FakeTdlibClient(status = status)).isMember())
        }
    }

    @Test
    fun `banned and left are refusals`() = runTest {
        for (status in listOf(MemberStatus.BANNED, MemberStatus.LEFT)) {
            assertFalse("$status is not a membership", session(FakeTdlibClient(status = status)).isMember())
        }
    }

    @Test
    fun `an unrecognised status is a refusal, never a silent yes`() = runTest {
        assertFalse(session(FakeTdlibClient(status = MemberStatus.UNKNOWN)).isMember())
        assertFalse(session(FakeTdlibClient(status = null)).isMember())
    }

    @Test
    fun `no session means not a member`() = runTest {
        val client = FakeTdlibClient(userId = null)

        assertFalse(session(client).isMember())
        assertEquals("nothing is asked about a chat without a session", listOf("me"), client.calls)
    }

    @Test
    fun `a group that cannot be resolved means not a member`() = runTest {
        val client = FakeTdlibClient(chatId = null)

        assertFalse(session(client).isMember())
        assertEquals(listOf("me", "chat"), client.calls)
    }

    @Test
    fun `a missing membership record means not a member`() = runTest {
        val client = FakeTdlibClient(status = null)

        assertFalse(session(client).isMember())
        assertEquals(listOf("me", "chat", "member"), client.calls)
    }

    @Test
    fun `membership asks about the listener in the group the join link points at`() = runTest {
        val client = FakeTdlibClient(userId = 7L, chatId = -1_004_476_157_917L)

        assertTrue(session(client).isMember())

        assertEquals(listOf("me", "chat", "member"), client.calls)
        assertEquals(
            TelegramMembershipPolicy.GROUP_URL.substringAfterLast('/'),
            client.askedUsername
        )
        assertEquals(-1_004_476_157_917L, client.askedChatId)
        assertEquals("the listener's own record, not the client's", 7L, client.askedUserId)
    }

    // ---- reading a post ----

    @Test
    fun `a post without audio still carries its id and text`() = runTest {
        val client = FakeTdlibClient(post = TelegramMessage(id = "42", text = "Кобзар"))

        val message = session(client).message("https://t.me/slukhayka/42")!!

        assertEquals("42", message.id)
        assertEquals("Кобзар", message.text)
        assertTrue(message.audio.isEmpty())
        assertEquals("the link is passed through untouched", "https://t.me/slukhayka/42", client.askedLink)
    }

    @Test
    fun `a post with several audio files keeps all of them in order`() = runTest {
        val client = FakeTdlibClient(
            post = TelegramMessage(
                id = "42",
                text = "Кобзар",
                audio = listOf(
                    TelegramAudio(fileName = "01.mp3", title = "Частина 1", performer = "Іван Франко", durationSeconds = 120),
                    TelegramAudio(fileName = "02.mp3", title = "Частина 2", performer = "Іван Франко", durationSeconds = 131)
                )
            )
        )

        val message = session(client).message("https://t.me/slukhayka/42")!!

        assertEquals(2, message.audio.size)
        assertEquals("01.mp3", message.audio[0].fileName)
        assertEquals("Частина 1", message.audio[0].title)
        assertEquals(120, message.audio[0].durationSeconds)
        assertEquals("02.mp3", message.audio[1].fileName)
        assertEquals("Частина 2", message.audio[1].title)
        assertEquals(131, message.audio[1].durationSeconds)
    }

    @Test
    fun `an unreadable link yields null, never a stub`() = runTest {
        val client = FakeTdlibClient(post = null)

        assertNull(session(client).message("https://t.me/slukhayka/42"))
    }
}
