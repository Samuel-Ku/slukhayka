package com.slukhayka.audiobooks.data.authors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthorSearchRequestTest {
    @Test
    fun `ordinary lookup failure hides the author block`() = runBlocking {
        assertEquals(
            emptyList<AuthorSummary>(),
            authorMatchesOrEmpty("леся") { error("Room temporarily unavailable") }
        )
    }

    @Test
    fun `cancelled stale lookup remains cancelled`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                authorMatchesOrEmpty("старий запит") { throw CancellationException("new query") }
            }
        }
    }
}
