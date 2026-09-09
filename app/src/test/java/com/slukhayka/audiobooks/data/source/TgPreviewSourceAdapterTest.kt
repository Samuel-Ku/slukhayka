package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0035 / #606 — the Telegram public-preview adapter, fixture-tested with
 * REAL posts harvested from `t.me/s/stivenkingua` (2026-09-08; the only one
 * of the first ten channels with a public preview — the rest return
 * «Preview unavailable», documented honestly in
 * `docs/phone-test/tg-preview-playback-verdict.md`). The RED prototype
 * verdict is pinned here: chapters are ALWAYS empty (the preview exposes no
 * audio — «Слухати» is a private-invite button), and the seam test proves
 * the parse rides [SourceAdapter.parseCapturedPage] — no downcast anywhere.
 */
class TgPreviewSourceAdapterTest {

    // Post 168 — «Джералдова гра»: NO author prefix (author stays empty,
    // never invented), narrator claim without a space after the colon, and
    // the REAL private-invite «Слухати» button (the verdict evidence).
    private val postNoAuthor = """
        <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message text_not_supported_wrap js-widget_message" data-post="stivenkingua/168" data-view="eyJjIjotMTYxOTQ3OTYxMCwicCI6MTY4LCJ0IjoxNzg4ODkwMzc1LCJoIjoiNWZlZmNhODNhYzgyYmMxNjI5In0">
        <a class="tgme_widget_message_photo_wrap 5199618504284379935 1210630523_460004127" href="https://t.me/stivenkingua/168" style="width:600px;background-image:url('https://cdn4.telesco.pe/file/toB28JlfV70CeaLfyu7beVrO3E0pyvIKeVvI-vX5IvucJrOmNhYqpyBC0E-cTjTteND3qrRAqxVTYlMUG8_fyBp0Ey3U1A1jCj-S8lJXU7EQrt86XrNtbF1MCk2uS9p8CsLhql91nPGxW2PDCTgzZGvkK_aoFW8ljVy-Nr3bqGMln_dHY28vzYvxzaUIeS7wGvJb6tKTWq2HiET4NxD2_VQYsg5D7qkNXO3RN_mIl4syrszqPNOChAHMnDO8q_e8KFYfWyw4DYTYPuX8s3q7cKm7BYS-e4mG3p5szGE8vk994IiAiUOKz2N5a4w7U7l55F41xTt38XDcy6BCUzCxyA.jpg')">
          <div class="tgme_widget_message_photo" style="padding-top:100%"></div>
        </a>
        <div class="tgme_widget_message_text js-message_text" dir="auto">Джералдова гра<br/><br/><i class="emoji" style="background-image:url('//telegram.org/img/emoji/40/F09F948A.png')"><b>🔊</b></i>Читає:Alex Nekrasov<br/><br/>Опис:<br/>Цей відпочинок у віддаленому літньому будиночку мав стати ідеальним для подружжя Джералда та Джессі. Але пристрасна ніч раптом закінчилася... смертю чоловіка. Джессі залишається прикутою наручниками до ліжка в порожньому, ізольованому від світу будинку, поруч із мертвим Джералдом. Однак самотньою вона буде недовго. Найтемніші страхи та спогади прийдуть до її понівеченої, розщепленої свідомості. А ще прийде він — моторошний чоловік, що дивитиметься на неї з темного кутка кімнати. Хто він — привид чи схиблений убивця? Плід кошмарних дитячих спогадів Джессі, що повернувся познущатися з неї знову? І яку ціну має заплатити вона, щоб позбутися фантомів памʼяті, загрозливих гостей та невблаганних металевих наручників?</div>
        <a class="tgme_widget_message_inline_button url_button" href="https://t.me/+khrCMdYLrqNmM2I6" target="_blank" rel="noopener" onclick="return confirm('Open this link?\n\n'+this.href);"><span class="tgme_widget_message_inline_button_text" dir="auto">Слухати</span></a>
        </div></div>
    """.trimIndent()

    // Post 167 — «Стівен Кінг - Дівчинка, яка любила Тома Ґордона»: the
    // author-prefix pattern, the narrator inside an <a> anchor, and an
    // HTML-encoded apostrophe (&#39;) in the description.
    private val postWithAuthor = """
        <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message text_not_supported_wrap js-widget_message" data-post="stivenkingua/167" data-view="eyJjIjotMTYxOTQ3OTYxMCwicCI6MTY3LCJ0IjoxNzg4ODkwMzc1LCJoIjoiMDFlNDRhMzQ3MDc5MjcxNGFiIn0">
        <a class="tgme_widget_message_photo_wrap blured 5321049582068241511 1238903399_460002407" href="https://t.me/stivenkingua/167" style="width:367px;background-image:url('https://cdn4.telesco.pe/file/FWaCjDYGBcP1-AvphbeMruVZrgs7HrqYfi2pe9pH8eWQLyQtayP1te0d_iyNycNWAjR5Q1fs77-FMFvhP2VXrpUrK9IpdS6It6ZGryn2SekWso4BleJ94J-QkJ05UZRq33gyZVhKBmSOZuUS-icYd4XPOpWRoApqGKF2ikmF0U6FXxqSBdVIYwJ6p-pdikJzwAks3O9c6q-v64Is5CPVsht43k1xmnGvju-vslaxkaQQYiIH8hiCHrizKPlsJbpNeJLbZbC_oWbw27u_SbEOMxKueq3hvH5DCz0mx4_yTIhMdWxiuJpNeGRHPqM9VAMcGK3grZflH9wkxk6ZtQY7jQ.jpg')">
          <div class="tgme_widget_message_photo" style="width:82.657657657658%;padding-top:133.33333333333%"></div>
        </a>
        <div class="tgme_widget_message_text js-message_text" dir="auto">Стівен Кінг - Дівчинка, яка любила Тома Ґордона<br/><br/><i class="emoji" style="background-image:url('//telegram.org/img/emoji/40/F09F948A.png')"><b>🔊</b></i>Читає: <a href="https://www.youtube.com/@%D0%90%D1%83%D0%B4%D1%96%D0%BE%D0%BA%D0%BD%D0%B8%D0%B3%D0%B8%D1%83%D0%BA%D1%80%D0%B0%D1%97%D0%BD%D1%81%D1%8C%D0%BA%D0%BE%D1%8E%D0%B2%D1%96%D0%B4%D0%9C%D0%B0%D0%BA%D1%81%D0%B0" target="_blank" rel="noopener" onclick="return confirm('Open this link?\n\n'+this.href);">Макс Шлапак</a><br/><br/>Опис:<br/>Дев&#39;ятирічна Тріша Макфарленд вирушає у кількагодинний похід разом із матір&#39;ю і старшим братом. Втомлена їхніми постійними сварками, дівчинка вирішує трохи відійти від рідних... Однак повернутися на потрібну стежку їй уже не вдається. Тріша лишається сама посеред лісу. Щоб заспокоїтися, вона налаштовує плеєр на трансляції бейсбольних матчів і стежить за грою свого кумира Тома Ґордона. Та сигнал починає слабшати. А той, хто залишив у густому темному лісі слід із забитих тварин і понівечених дерев, наближається до неї. Він спостерігає і чекає...</div>
        <a class="tgme_widget_message_inline_button url_button" href="https://t.me/+sdYcQ4nlEwE5ZTcy" target="_blank" rel="noopener" onclick="return confirm('Open this link?\n\n'+this.href);"><span class="tgme_widget_message_inline_button_text" dir="auto">Слухати</span></a>
        </div></div>
    """.trimIndent()

    // The parse rides the SOURCE-ADAPTER SEAM (ADR-0006): no door ever
    // downcasts to this class.
    private val adapter: SourceAdapter = TgPreviewSourceAdapter()

    private val postNoAuthorUrl = "https://t.me/stivenkingua/168"
    private val postWithAuthorUrl = "https://t.me/stivenkingua/167"

    @Test
    fun `parses a post without an author prefix - author is never invented`() {
        val detail = runBlocking { adapter.parseCapturedPage(postNoAuthor, postNoAuthorUrl) }!!
        assertEquals("Джералдова гра", detail.title)
        assertEquals("", detail.author)
        assertEquals("Alex Nekrasov", detail.narrator)
        assertEquals(postNoAuthorUrl, detail.url)
        assertTrue(detail.coverImageUrl!!.startsWith("https://cdn4.telesco.pe/file/"))
        assertTrue(detail.description.startsWith("Джералдова гра"))
        assertTrue(detail.description.contains("Опис:"))
        assertEquals("uk", detail.language)
    }

    @Test
    fun `parses a post with the author prefix via the channel pattern`() {
        val detail = runBlocking { adapter.parseCapturedPage(postWithAuthor, postWithAuthorUrl) }!!
        assertEquals("Дівчинка, яка любила Тома Ґордона", detail.title)
        assertEquals("Стівен Кінг", detail.author)
        assertEquals("Макс Шлапак", detail.narrator)
        assertTrue("HTML entities decode", detail.description.contains("Дев'ятирічна"))
        assertEquals(postWithAuthorUrl, detail.url)
    }

    @Test
    fun `the RED verdict - chapters are always empty, never fabricated`() {
        val noAuthor = runBlocking { adapter.parseCapturedPage(postNoAuthor, postNoAuthorUrl) }!!
        val withAuthor = runBlocking { adapter.parseCapturedPage(postWithAuthor, postWithAuthorUrl) }!!
        assertTrue(noAuthor.chapters.isEmpty())
        assertTrue(withAuthor.chapters.isEmpty())
        assertEquals(null, noAuthor.totalDurationSeconds)
    }

    @Test
    fun `a page without the requested post is a miss, never a different post`() {
        // The /s/ page renders the newest posts — an old post id absent from
        // the page must NOT parse as some other post.
        val oldPostUrl = "https://t.me/stivenkingua/99"
        assertNull(runBlocking { adapter.parseCapturedPage(postNoAuthor, oldPostUrl) })
    }

    @Test
    fun `a channel link without a post id parses the first block`() {
        val channelUrl = "https://t.me/s/stivenkingua"
        val detail = runBlocking { adapter.parseCapturedPage(postWithAuthor + postNoAuthor, channelUrl) }!!
        assertEquals("Дівчинка, яка любила Тома Ґордона", detail.title)
    }

    @Test
    fun `a non-telegram page is not mine`() {
        val fourRead = "<html><body><div>Книга</div></body></html>"
        assertNull(runBlocking { adapter.parseCapturedPage(fourRead, "https://4read.org/book") })
        assertNull(runBlocking { adapter.parseCapturedPage("", postNoAuthorUrl) })
        assertNull(runBlocking { adapter.parseCapturedPage(postNoAuthor, "https://example.com/not-tg") })
    }

    @Test
    fun `the source has no browsable catalog surface`() {
        assertEquals(SourceIds.TELEGRAM, adapter.sourceId)
        assertTrue(runBlocking { adapter.search("кінг") }.isEmpty())
        assertTrue(runBlocking { adapter.fetchNew(10) }.isEmpty())
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}