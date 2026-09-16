package com.slukhayka.audiobooks.data.ingest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0050 / spec-53 (#829) — the account-bound Telegram seam over a fake:
 * only an explicit action connects, the session is local and revocable, and a
 * missing session or membership is an honest refusal carrying the group's
 * join deep link.
 */
class TelegramMediaFetcherTest {

    private class FakeFetcher(
        private var state: TelegramSessionState = TelegramSessionState.Absent,
        private val tracks: List<TelegramTrack> = listOf(
            TelegramTrack(index = 0, title = "Частина 1", sizeBytes = 1_000L, mimeType = "audio/mpeg"),
            TelegramTrack(index = 1, title = "Частина 2", sizeBytes = 2_000L, mimeType = "audio/mpeg")
        )
    ) : TelegramMediaFetcher {
        var connectCalls = 0
        var disconnectCalls = 0

        override suspend fun session(): TelegramSessionState = state

        override suspend fun connect(): TelegramSessionState {
            connectCalls++
            state = TelegramSessionState.Connected
            return state
        }

        override suspend fun disconnect() {
            disconnectCalls++
            state = TelegramSessionState.Absent
        }

        override suspend fun fetch(link: String): TelegramFetchResult =
            TelegramSessionPolicy.refusalFor(state) ?: TelegramFetchResult.Ok(tracks)
    }

    @Test
    fun `a session never appears without an explicit connect`() = runBlocking {
        val fetcher = FakeFetcher()

        assertEquals(TelegramSessionState.Absent, fetcher.session())
        assertEquals("reading the state is not a login", 0, fetcher.connectCalls)

        val refused = fetcher.fetch("https://t.me/slukhayka/573/42")
        assertTrue(refused is TelegramFetchResult.Refused)
        assertEquals(
            TelegramSessionPolicy.REASON_NO_SESSION,
            (refused as TelegramFetchResult.Refused).reason
        )
        assertEquals("https://t.me/slukhayka", refused.joinDeepLink)
    }

    @Test
    fun `connect is explicit and disconnect drops the session`() = runBlocking {
        val fetcher = FakeFetcher()

        assertEquals(TelegramSessionState.Connected, fetcher.connect())
        assertEquals(1, fetcher.connectCalls)
        assertEquals(TelegramSessionState.Connected, fetcher.session())

        fetcher.disconnect()
        assertEquals(1, fetcher.disconnectCalls)
        assertEquals(TelegramSessionState.Absent, fetcher.session())
    }

    @Test
    fun `a non-member is refused with the join deep link`() = runBlocking {
        val fetcher = FakeFetcher(state = TelegramSessionState.NotMember)

        val refused = fetcher.fetch("https://t.me/slukhayka/573/42") as TelegramFetchResult.Refused

        assertEquals(TelegramSessionPolicy.REASON_NOT_MEMBER, refused.reason)
        assertEquals("https://t.me/slukhayka", refused.joinDeepLink)
        assertTrue("a refusal is never an empty success", refused.joinDeepLink.isNotBlank())
    }

    @Test
    fun `a connected account fetches the post's tracks in order`() = runBlocking {
        val fetcher = FakeFetcher(state = TelegramSessionState.Connected)

        val result = fetcher.fetch("https://t.me/slukhayka/573/42")

        assertTrue(result is TelegramFetchResult.Ok)
        assertEquals(listOf(0, 1), (result as TelegramFetchResult.Ok).tracks.map { it.index })
        assertEquals(listOf("Частина 1", "Частина 2"), result.tracks.map { it.title })
    }

    @Test
    fun `the join deep link comes from the source registry, not from code`() {
        assertEquals("https://t.me/slukhayka", TelegramSessionPolicy.joinDeepLink())
        assertNull(TelegramSessionPolicy.refusalFor(TelegramSessionState.Connected))
    }
}
