package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `poster with a cycle badge carries its series metadata on the book`() {
        val sections = CatalogParser.parseHomepage("<html><body>$seriesPoster</body></html>")

        val book = sections.first().books.first { it.id == "4read-7589-neostannij-bij" }
        assertEquals("Максим Темний", book.seriesTitle)
        assertEquals("https://4read.org/xfsearch/cikl/%D0%BC%D0%B0%D0%BA%D1%81%D0%B8%D0%BC%20%D1%82%D0%B5%D0%BC%D0%BD%D0%B8%D0%B9/", book.seriesUrl)
        assertEquals(7, book.seriesIndex)
    }

    @Test
    fun `poster without a cycle carries no series metadata`() {
        val sections = CatalogParser.parseHomepage("<html><body>$plainPoster</body></html>")

        val book = sections.first().books.first()
        assertNull(book.seriesTitle)
        assertNull(book.seriesUrl)
        assertNull(book.seriesIndex)
    }

    @Test
    fun `malformed or absent volume badge yields a null index`() {
        val html = """
            <div class="poster has-overlay">
                <div class="poster__desc order-last">
                    <a href="https://4read.org/1000-test.html" class="poster__link"><div class="poster__title line-clamp">Книга</div></a>
                    <div class="poster__subtitle ws-nowrap">Автор</div>
                </div>
                <div class="poster__img img-responsive">
                    <img src="/uploads/posts/2026-06/medium/x.webp" loading="lazy">
                    <div class="poster__label poster__label--blue">seven</div>
                    <div class="poster__series anim"><a href="https://4read.org/xfsearch/cikl/c/">Цикл</a></div>
                </div>
            </div>
        """.trimIndent()

        val sections = CatalogParser.parseHomepage(html)
        val book = sections.first().books.first()
        assertEquals("Цикл", book.seriesTitle)
        assertNull("non-numeric badge must not crash the parser", book.seriesIndex)
    }

    @Test
    fun `series page books also carry their series metadata`() {
        val html = """<html><body>$seriesPoster</body></html>"""

        val books = CatalogParser.parseSeriesPage(html)
        val book = books.first { it.id == "4read-7589-neostannij-bij" }
        assertEquals("Максим Темний", book.seriesTitle)
        assertEquals(7, book.seriesIndex)
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
    fun `genre nav sidebar parses into absolute genre links`() {
        val html = """
            <aside class="cols__right">
                <div class="sb js-this-in-mobile-menu">
                    <div class="sb__title"><span class="fal fa-book"></span>Аудіокниги жанру:</div>
                    <ul class="sb__content sb__nav">
                        <li><a href="/kazka/">Казка</a></li>
                        <li><a href="/fentezi/">Фентезі</a></li>
                        <li><a href="/dytlit/">Дитячі</a></li>
                        <li><a href="/zno-ukrajinska-literatura.html">ЗНО</a></li>
                        <li><a href="/addnews.html"><i class="fal fa-pencil-square-o" style="color:red"></i> Додати книгу</a></li>
                    </ul>
                </div>
            </aside>
        """.trimIndent()

        val genres = CatalogParser.parseGenreNav(html)

        // "Додати книгу" embeds an <i> icon and ЗНО is a static works list
        // (no poster grid) — both must be skipped.
        assertEquals(3, genres.size)
        assertEquals("Казка", genres[0].title)
        assertEquals("https://4read.org/kazka/", genres[0].url)
        assertEquals("Фентезі", genres[1].title)
        assertEquals("https://4read.org/fentezi/", genres[1].url)
        assertEquals("Дитячі", genres[2].title)
        assertEquals("https://4read.org/dytlit/", genres[2].url)
    }

    @Test
    fun `related books section parses into books`() {
        val html = """
            <section class="sect pmovie__related carou">
                <h2 class="sect__title sect__header"><span>Можливо,</span> Тебе зацікавить:</h2>
                <div class="sect__content grid-items">
                    $plainPoster
                    $seriesPoster
                </div>
            </section>
        """.trimIndent()

        val related = CatalogParser.parseRelatedBooks(html)

        assertEquals(2, related.size)
        assertEquals("4read-7611-vkradi-mene-zaraz", related[0].id)
        assertEquals("Вкради мене... Зараз!", related[0].title)
        assertEquals("4read-7589-neostannij-bij", related[1].id)
    }

    @Test
    fun `related books degrade to empty on absent section`() {
        assertTrue(CatalogParser.parseRelatedBooks("").isEmpty())
        assertTrue(CatalogParser.parseRelatedBooks("<html><body><p>no related</p></body></html>").isEmpty())
        assertTrue(CatalogParser.parseRelatedBooks("<section class=\"sect pmovie__related\"></section>").isEmpty())
    }

    @Test
    fun `top 100 linek cards parse into ranked books with real duration`() {
        val html = """
            <div class="sect__content count-items">
                <div class="linek d-flex ai-center has-overlay card">
                    <div class="linek__img img-fit-cover"><img src="/uploads/posts/2026-02/medium/x.webp" loading="lazy"></div>
                    <div class="linek__desc flex-grow-1">
                        <a href="https://4read.org/6945-dzho-aberkrombi-chorti.html"><div class="linek__title ws-nowrap">Чорти - Джо Аберкромбі</div></a>
                        <div class="linek__meta ws-nowrap"><span>Триває:</span> 21:42:42</div>
                    </div>
                </div>
                <div class="linek d-flex ai-center has-overlay card">
                    <div class="linek__img img-fit-cover"><img src="/uploads/posts/2026-01/medium/y.webp" loading="lazy"></div>
                    <div class="linek__desc flex-grow-1">
                        <a href="https://4read.org/7001-vkradi-mene-zaraz.html"><div class="linek__title ws-nowrap">Вкради мене... Зараз! - Сергій Оріанець</div></a>
                        <div class="linek__meta ws-nowrap"><span>Триває:</span> 8:05:00</div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val books = CatalogParser.parseTop100(html)

        assertEquals(2, books.size)
        val first = books[0]
        assertEquals("4read-6945-dzho-aberkrombi-chorti", first.id)
        // "Title - Author" split at the LAST separator.
        assertEquals("Чорти", first.title)
        assertEquals("Джо Аберкромбі", first.author)
        assertEquals(21 * 3600L + 42 * 60L + 42L, first.totalDurationSeconds)
        assertEquals("https://4read.org/uploads/posts/2026-02/medium/x.webp", first.coverImageUrl)
        // A title that itself contains " - " keeps it intact.
        assertEquals("Вкради мене... Зараз!", books[1].title)
        assertEquals("Сергій Оріанець", books[1].author)
        assertEquals(8 * 3600L + 5 * 60L, books[1].totalDurationSeconds)
    }

    @Test
    fun `people list parses narrators and authors with book counts`() {
        val readers = """
            <h1>Усі виконавці:</h1>
            <div style="font-size:16px!important;"><ul>
                <li><a href="/xfsearch/chitaet/Ада Роговцева/" target="_blank">Ада Роговцева - 23 книги</a></li>
                <li><a href="/xfsearch/chitaet/Аліна Лукащук/" target="_blank">Аліна Лукащук - 8 книг</a></li>
                <li><a href="/xfsearch/chitaet/Аза Власова/" target="_blank">Аза Власова - 1 книга</a></li>
            </ul></div>
        """.trimIndent()

        val people = CatalogParser.parsePeopleList(readers)

        assertEquals(3, people.size)
        assertEquals("Ада Роговцева", people[0].name)
        assertEquals("/xfsearch/chitaet/Ада Роговцева/", people[0].path)
        assertEquals(23, people[0].bookCount)
        assertEquals(1, people[2].bookCount)
    }

    @Test
    fun `popular sidebar block parses into books with alt-author and duration`() {
        val html = """
            <aside class="cols__right">
                <div class="sb sb--mt">
                    <div class="sb__title"><span class="fal fa-trophy"></span>Популярне</div>
                    <div class="sb__content sb__grid">
                        <a class="ftop-item d-flex ai-center has-overlay" href="https://4read.org/7894-zhan-kristof-granzhe-pasazhir.html">
                            <div class="ftop-item__img img-fit-cover">
                                <img src="/uploads/posts/2026-07/medium/pasazhyr.webp" loading="lazy" alt="Жан-Крістоф Ґранже - Пасажир">
                                <div class="has-overlay__icon anim"></div>
                            </div>
                            <div class="ftop-item__desc flex-grow-1">
                                <div class="ftop-item__title poster__title line-clamp">Пасажир</div>
                                <div class="ftop-item__meta poster__subtitle line-clamp">Світова література / Детектив / Роман</div>
                                <div class="ftop-item__meta poster__subtitle line-clamp"><span class="fal fa-clock"></span> 24:54:14</div>
                            </div>
                        </a>
                    </div>
                </div>
            </aside>
        """.trimIndent()

        val books = CatalogParser.parsePopularBooks(html)

        assertEquals(1, books.size)
        assertEquals("Пасажир", books[0].title)
        assertEquals("Жан-Крістоф Ґранже", books[0].author)
        assertEquals(24 * 3600L + 54 * 60L + 14L, books[0].totalDurationSeconds)
        assertEquals("https://4read.org/uploads/posts/2026-07/medium/pasazhyr.webp", books[0].coverImageUrl)
    }

    @Test
    fun `homepage with popular block yields a third section`() {
        val html = """
            <html><body>
            <main>$plainPoster</main>
            <aside class="cols__right">
                <div class="sb sb--mt">
                    <div class="sb__title"><span class="fal fa-trophy"></span>Популярне</div>
                    <div class="sb__content sb__grid">
                        <a class="ftop-item d-flex ai-center has-overlay" href="https://4read.org/7894-zhan-kristof-granzhe-pasazhir.html">
                            <div class="ftop-item__img img-fit-cover"><img src="/uploads/posts/2026-07/medium/pasazhyr.webp" alt="Жан-Крістоф Ґранже - Пасажир"></div>
                            <div class="ftop-item__desc flex-grow-1">
                                <div class="ftop-item__title poster__title line-clamp">Пасажир</div>
                                <div class="ftop-item__meta poster__subtitle line-clamp"><span class="fal fa-clock"></span> 24:54:14</div>
                            </div>
                        </a>
                    </div>
                </div>
            </aside>
            </body></html>
        """.trimIndent()

        val sections = CatalogParser.parseHomepage(html)

        val popular = sections.first { it.title == "Популярне" }
        assertEquals("Пасажир", popular.books.first().title)
        assertEquals(24 * 3600L + 54 * 60L + 14L, popular.books.first().totalDurationSeconds)
    }

    @Test
    fun `top100 and people lists degrade to empty on malformed html`() {
        assertTrue(CatalogParser.parsePopularBooks("").isEmpty())
        assertTrue(CatalogParser.parsePopularBooks("<html><body><p>no popular</p></body></html>").isEmpty())
        assertTrue(CatalogParser.parseTop100("").isEmpty())
        assertTrue(CatalogParser.parseTop100("<html><body><p>no linek</p></body></html>").isEmpty())
        assertTrue(CatalogParser.parsePeopleList("").isEmpty())
        assertTrue(CatalogParser.parsePeopleList("<html><body><p>no people</p></body></html>").isEmpty())
    }

    @Test
    fun `genre nav degrades to empty on malformed html`() {
        assertTrue(CatalogParser.parseGenreNav("").isEmpty())
        assertTrue(CatalogParser.parseGenreNav("<html><body><p>no nav</p></body></html>").isEmpty())
        assertTrue(CatalogParser.parseGenreNav("<ul class=\"sb__content sb__nav\"></ul>").isEmpty())
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
