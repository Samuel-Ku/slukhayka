package com.slukhayka.audiobooks.data.facets

import com.slukhayka.audiobooks.data.catalog.CatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #702 (T4) — регресія запізнілої заяви «жахи»→horror у довіднику джерел.
 *
 * Словник [GenreIdentity] розширюється лише текстами, які джерело реально
 * заявляє на захопленій сторінці (#1053, ADR-0014). «Жахи» потрапив туди
 * запізніло: встановлення, що встигли записати заяву до розширення словника,
 * лежали під хешем і потребували міграції 52→53. Цей тест тримає заяву
 * прикріпленою до її доказів у фікстурах, тож:
 *
 * - нове встановлення отримує `horror` у словнику з першого запуску (без
 *   будь-якої міграції);
 * - видалення заяви зі словника, зникнення фікстури-доказу чи зміна
 *   написання джерела падає ТУТ, а не мовчазно повертає лакуну.
 *
 * Lihtar у захоплених фікстурах цього слова не заявляє, тому докази — лише
 * з 4read і sound-books; вигаданий lihtar-рядок був би вигаданим жанром.
 *
 * #1053 тримає тут другу заяву — «Попаданці» з жанрового індексу chitaka
 * (`chitaka-zhanryi-2026-10-10.html`). Застосунок поки не читає жанрів із
 * chitaka (ні адаптер, ні worker не звуть жодного), тож фікстура пінить саме
 * ЗАЯВУ джерела: щойно адаптер її читатиме, id `portal-fantasy` уже чекає, а
 * зникнення полиці з джерела падає тут, не тихо в коментарі.
 */
class GenreSourceClaimTest {

    /** Корінь репозиторію — так само, як CatalogSeedTest знаходить сід. */
    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(4)
            .firstOrNull { File(it, "gradlew").isFile }
            ?: error("repo root not found above ${start.absolutePath}")
    }

    private fun readRepoFile(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    @Test
    fun `the captured 4read menu still claims жахи and the dictionary keeps horror`() {
        val html = readRepoFile("app/src/test/resources/fixtures/4read-book-7589-2026-09-03.html")

        assertTrue(
            "4read прибрав «Жахи» з меню жанрів (/horror/) — спершу оновіть доказ, потім перегляньте заяву",
            Regex("""<a href="[^"]*/horror/">\s*Жахи\s*</a>""").containsMatchIn(html)
        )

        // Меню так, як його реально читає застосунок (чипи «Жанри» в Explore),
        // все ще віддає заяву — і заява падає на спільний канонічний id.
        val claimed = CatalogParser.parseGenreNav(html).map { it.title }
        assertTrue("меню втратило «Жахи»: $claimed", "Жахи" in claimed)
        assertEquals(
            listOf(NormalizedGenre("horror", "Жахи")),
            GenreIdentity.fromSourceText("Жахи")
        )
    }

    @Test
    fun `the captured sound-books categories still claim жахи`() {
        val html = readRepoFile("web/src/worker/fixtures/soundbooks-categories.html")

        assertTrue(
            "sound-books прибрав «Жахи» з категорій (/zhakhy/) — спершу оновіть доказ, потім перегляньте заяву",
            Regex("""<a href="[^"]*/zhakhy/">\s*Жахи\s*</a>""").containsMatchIn(html)
        )
        assertEquals(
            listOf(NormalizedGenre("horror", "Жахи")),
            GenreIdentity.fromSourceText("Жахи")
        )
    }

    @Test
    fun `a fresh install's dictionary carries horror before any migration runs`() {
        // Запізніла заява мусить жити в СКЛАДЕНОМУ словнику: нове
        // встановлення не має рядків для міграції 53 — єдиний його захист
        // від повторення лакуни це сам запис.
        assertEquals(
            NormalizedGenre("horror", "Жахи"),
            GenreIdentity.canonicalIdentities["horror"]
        )
        assertEquals("horror", GenreIdentity.fromSourceText("жахи").single().id)
    }

    /**
     * #1053 — chitaka справді заявляє полицю «Попаданці», і це заява, а не
     * побажання: жанровий індекс тримає її під Фантастикою окремим піджанром.
     * Доказ — тримований слaйс живої сторінки, тож зникнення полиці з джерела
     * падає тут, а не тихо лишає коментар неправдою.
     */
    @Test
    fun `the captured chitaka genre index still claims Попаданці under Фантастика`() {
        val html = readRepoFile("app/src/test/resources/fixtures/chitaka-zhanryi-2026-10-10.html")

        val parent = Regex("""<span class="zhanr-title">[^<]*Фантастика</span>""").find(html)
        val shelf = Regex(
            """href="https://chitaka\.com\.ua/zhanryi/fantastika/popadancy/"[^>]*title="Попаданці">Попаданці</a>"""
        ).find(html)

        assertTrue(
            "chitaka прибрав блок «Фантастика» з жанрового індексу — спершу оновіть доказ, потім перегляньте заяву",
            parent != null
        )
        assertTrue(
            "chitaka прибрав піджанр «Попаданці» (/zhanryi/fantastika/popadancy/) — спершу оновіть доказ, потім перегляньте заяву",
            shelf != null
        )
        assertTrue(
            "«Попаданці» мусить лишатися ПІД Фантастикою: саме так джерело вкладає полицю",
            parent!!.range.first < shelf!!.range.first
        )
        assertEquals(
            listOf(NormalizedGenre("portal-fantasy", "Попаданці")),
            GenreIdentity.fromSourceText("Попаданці")
        )
    }
}
