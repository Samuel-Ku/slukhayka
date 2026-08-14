package com.example.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CatalogParser] (spec #8 tickets T5/T8). Fixtures mirror
 * the real poster markup served by 4read.org, captured on 2026-08-06.
 */
class CatalogParserTest {

    /** Real homepage poster with no series. */
    private val plainPoster = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7611-vkradi-mene-zaraz.html" class="poster__link"><div class="poster__title line-clamp">Вкради мене... Зараз!</div></a>
                <div class="poster__subtitle ws-nowrap">
                    Сергій Оріанець
                </div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp" loading="lazy" alt="Сергій Оріанець - ВКРАДИ МЕНЕ... ЗАРАЗ!">
                <div class="has-overlay__icon anim"></div>
            </div>
            <div class="poster__btn-info js-show-info"><span class="fal fa-info-circle"></span>Опис</div>
        </div>
    """.trimIndent()

    /** Real homepage poster with a cycle (series) badge. */
    private val seriesPoster = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7589-neostannij-bij.html" class="poster__link"><div class="poster__title line-clamp">Неостанній бій</div></a>
                <div class="poster__subtitle ws-nowrap">
                    Костянтин Шелест
                </div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-05/medium/4_ks_mt7.webp" loading="lazy" alt="Костянтин Шелест - Неостанній бій">
                <div class="poster__label poster__label--blue">7</div>
                <div class="poster__series anim"><a href="https://4read.org/xfsearch/cikl/%D0%BC%D0%B0%D0%BA%D1%81%D0%B8%D0%BC%20%D1%82%D0%B5%D0%BC%D0%BD%D0%B8%D0%B9/">Максим Темний</a></div>
                <div class="has-overlay__icon anim"></div>
            </div>
        </div>
    """.trimIndent()

    /** Promo post that must be filtered out of the catalogue. */
    private val promoPoster = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/5132-khochesh-slukhaty-audioknygy-bez-reklamy.html" class="poster__link"><div class="poster__title line-clamp">🔥 Хочеш слухати аудіокниги без реклами?</div></a>
                <div class="poster__subtitle ws-nowrap">4read</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/banner.webp" loading="lazy">
            </div>
        </div>
    """.trimIndent()

    @Test
    fun `homepage yields New-releases and Cycles sections`() {
        val html = """
            <html><body>
            $plainPoster
            $seriesPoster
            $promoPoster
            </body></html>
        """.trimIndent()

        val sections = CatalogParser.parseHomepage(html)

        assertEquals(2, sections.size)
        val news = sections.first { it.title == "Новинки" }
        val cycles = sections.first { it.title == "Цикли" }

        // Promo post filtered out: only the two real books remain.
        assertEquals(2, news.books.size)
        assertEquals(1, cycles.series.size)

        val first = news.books.first()
        assertEquals("4read-7611-vkradi-mene-zaraz", first.id)
        assertEquals("Вкради мене... Зараз!", first.title)
        assertEquals("Сергій Оріанець", first.author)
        assertEquals("https://4read.org/uploads/posts/2026-06/medium/vkrady-mene-zaraz.webp", first.coverImageUrl)

        val series = cycles.series.first()
        assertEquals("Максим Темний", series.title)
        assertEquals("https://4read.org/xfsearch/cikl/%D0%BC%D0%B0%D0%BA%D1%81%D0%B8%D0%BC%20%D1%82%D0%B5%D0%BC%D0%BD%D0%B8%D0%B9/", series.url)
        assertEquals("https://4read.org/uploads/posts/2026-05/medium/4_ks_mt7.webp", series.coverImageUrl)
    }

    @Test
    fun `series page parses into books`() {
        val html = """
            <h1>Всі книги із циклу "Максим Темний":</h1>
            $seriesPoster
            $plainPoster
        """.trimIndent()

        val books = CatalogParser.parseSeriesPage(html)

        assertEquals(2, books.size)
        assertTrue(books.any { it.id == "4read-7589-neostannij-bij" })
        assertTrue(books.any { it.id == "4read-7611-vkradi-mene-zaraz" })
    }

    @Test
    fun `empty and malformed pages degrade to empty lists`() {
        assertTrue(CatalogParser.parseHomepage("").isEmpty())
        assertTrue(CatalogParser.parseHomepage("<html><body><p>no posters</p></body></html>").isEmpty())
        assertTrue(CatalogParser.parseSeriesPage("").isEmpty())
    }

    @Test
    fun `html entities are decoded in titles and series names`() {
        val html = """
            <div class="poster has-overlay">
                <div class="poster__desc order-last">
                    <a href="https://4read.org/9999-test.html" class="poster__link"><div class="poster__title line-clamp">Тест &amp; Ще</div></a>
                    <div class="poster__subtitle ws-nowrap">Автор</div>
                </div>
                <div class="poster__img img-responsive">
                    <img src="/uploads/posts/2026-06/medium/x.webp" loading="lazy">
                    <div class="poster__series anim"><a href="https://4read.org/xfsearch/cikl/x/">Сага про Дріззта До&#039;Урдена</a></div>
                </div>
            </div>
        """.trimIndent()

        val sections = CatalogParser.parseHomepage(html)
        assertEquals("Тест & Ще", sections.first().books.first().title)
        assertEquals("Сага про Дріззта До'Урдена", sections.first { it.title == "Цикли" }.series.first().title)
    }
}
