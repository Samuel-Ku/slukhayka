package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookRecommendationTextTest {
    @Test
    fun `work text cleans source noise and includes rich metadata without narrator`() {
        val text = BookRecommendationText.build(
            title = "Дюна — Аудіокнига слухати онлайн",
            author = "Френк   Герберт",
            genres = "Фантастика",
            series = "Хроніки Дюни",
            effectiveDescription = "<p>Пустельна планета &amp; прянощі.</p>"
        )
        assertTrue(text.contains("Дюна"))
        assertTrue(text.contains("Френк Герберт"))
        assertTrue(text.contains("Хроніки Дюни"))
        assertTrue(text.contains("Пустельна планета & прянощі."))
        assertFalse(text.contains("слухати онлайн", ignoreCase = true))
        assertFalse(text.contains("<p>"))
    }
}
