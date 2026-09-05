package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryIdentityGuardTest {
    private fun detail(
        title: String = "Книга",
        author: String = "Автор",
        narrator: String = "Читець",
        language: String = "",
        chapters: List<String> = listOf("1", "2")
    ) = SourceBookDetail(
        title,
        author,
        narrator,
        "https://4read.org/book.html",
        language = language,
        chapters = chapters.map { SourceChapter(it, "https://reasd.org/$it.mp3") }
    )

    @Test fun `accepts exact work edition and chapter topology`() {
        assertTrue(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "", listOf("1", "2"), detail()))
    }

    @Test fun `rejects another work without changing tracks`() {
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "", listOf("1", "2"), detail(title = "Інша книга")))
    }

    @Test fun `rejects another narration and reordered chapters`() {
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "", listOf("1", "2"), detail(narrator = "Інший читець")))
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "", listOf("1", "2"), detail(chapters = listOf("2", "1"))))
    }

    @Test fun `language bearing edition fails closed without matching capture language`() {
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "uk", listOf("1", "2"), detail()))
        assertTrue(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "uk", listOf("1", "2"), detail(language = "uk")))
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Читець", "uk", listOf("1", "2"), detail(language = "pl")))
    }

    @Test fun `legacy catalogue narrator is absent rather than another narration`() {
        assertTrue(RecoveryIdentityGuard.matches("Книга", "Автор", "4read Voice Narrator", "", listOf("1", "2"), detail()))
        assertFalse(RecoveryIdentityGuard.matches("Книга", "Автор", "Інший читець", "", listOf("1", "2"), detail()))
    }
}
