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

    @Test fun `title entity equivalence stays anchored to the existing work key`() {
        val plain = "Проєкт \"Аве Марія\""
        val encoded = "Проєкт &quot;Аве Марія&quot;"
        val plainKey = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(plain, "Автор")
        val encodedKey = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(encoded, "Автор")
        for (stored in listOf(plain, encoded, "Проєкт &amp;quot;Аве Марія&amp;quot;")) {
            val storedKey = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(stored, "Автор")
            for (captured in listOf(plain, encoded, "Проєкт &#34;Аве Марія&#34;")) {
                assertTrue(RecoveryIdentityGuard.matchesStoredWorkKey(stored, "Автор", storedKey, detail(title = captured)))
                assertTrue(RecoveryIdentityGuard.matchesStoredWorkKey(stored, "Автор", plainKey, detail(title = captured)))
                assertTrue(RecoveryIdentityGuard.matches(stored, "Автор", "Читець", "", listOf("1", "2"), detail(title = captured)))
            }
        }
        assertTrue(RecoveryIdentityGuard.matchesStoredWorkKey(plain, "Автор", encodedKey, detail(title = encoded)))
        assertFalse(RecoveryIdentityGuard.matchesStoredWorkKey(encoded, "Автор", "unrelated|key", detail(title = encoded)))
        assertFalse(RecoveryIdentityGuard.matchesStoredWorkKey(encoded, "Автор", plainKey, detail(title = plain, author = "Інший автор")))
        assertFalse(RecoveryIdentityGuard.matchesStoredWorkKey(encoded, "Автор", plainKey, detail(title = "Інша книга")))
        assertFalse(RecoveryIdentityGuard.matches(encoded, "Автор", "Читець", "", listOf("1", "2"), detail(title = plain, narrator = "Інший читець")))
        assertFalse(RecoveryIdentityGuard.matches(encoded, "Автор", "Читець", "uk", listOf("1", "2"), detail(title = plain, language = "en")))
        assertFalse(RecoveryIdentityGuard.matches(encoded, "Автор", "Читець", "", listOf("1", "2"), detail(title = plain, chapters = listOf("2", "1"))))
    }

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
