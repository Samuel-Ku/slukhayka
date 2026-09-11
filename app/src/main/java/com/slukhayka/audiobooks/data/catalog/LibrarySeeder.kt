package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBookDetail

/**
 * Авто-сід медіатеки (spec `2026-09-10-remove-4read-source`): каталог →
 * резолв сторінки → preflight першого треку → імпорт крізь звичайні двері
 * (`LibraryImport.importBookFromSource` — той самий шов, що й тап).
 *
 * Книжка рахується робочою лише після перевіреного стріма: `streamProbe`
 * (в проді — HEAD-префлайт `HttpFetcher.isReachable`) дає позитивний вердикт,
 * і тільки тоді імпорт. Жодного браузера: candidates з лише browser-джерелами
 * пропускаються (двері браузера не відкриваються неявно — CONTEXT.md).
 * Вже відомі твори (dao) пропускаються без запитів — прохід дешевий і
 * повторюваний. Бюджет обмежує прохід; K послідовних провалів зупиняють —
 * бюджет джерела вичерпано, а не каталог скінчився.
 *
 * Pure JVM: усі залежності — шви, тож конвеєр тестується без Room/мережі.
 */
class LibrarySeeder(
    /** The current catalogue union — supplied, never crawled here. */
    private val candidates: suspend () -> List<GlobalSearchResult>,
    /** The adapter seam: null source id → the card contributes nothing. */
    private val adapterFor: (String) -> SourceAdapter?,
    /** The playability proof: true = the stream is reachable. */
    private val streamProbe: suspend (String) -> Boolean,
    /** The already-in-library check (mergeKey) — zero requests. */
    private val known: suspend (String) -> Boolean,
    /** The ordinary import door (idempotent by Edition). */
    private val import: suspend (sourceId: String, detail: SourceBookDetail) -> Unit
) {

    /** Bounds of one pass — small by design; the next pass continues. */
    data class SeedBudget(
        val maxBooks: Int = 20,
        val maxConsecutiveFailures: Int = 4
    )

    /** Honest counts of one pass. */
    data class SeedResult(
        val imported: Int,
        val verified: Int,
        val failed: Int,
        val skippedKnown: Int
    )

    suspend fun seedOnce(budget: SeedBudget = SeedBudget()): SeedResult {
        var imported = 0
        var verified = 0
        var failed = 0
        var skippedKnown = 0
        var consecutiveFailures = 0

        for (card in candidates()) {
            if (imported >= budget.maxBooks) break
            if (consecutiveFailures >= budget.maxConsecutiveFailures) break

            val source = card.sources.firstOrNull {
                it.url.isNotBlank() && SourceAccessPolicy.modeFor(it.sourceId) != SourceAccessMode.BROWSER
            } ?: continue
            if (card.mergeKey.isBlank()) continue
            if (known(card.mergeKey)) {
                skippedKnown++
                continue
            }

            val detail = try {
                adapterFor(source.sourceId)?.fetchBookPage(source.url) ?: continue
            } catch (e: Exception) {
                if (++consecutiveFailures >= budget.maxConsecutiveFailures) break
                failed++
                continue
            }
            val stream = detail.chapters.firstOrNull()?.streamUrl
            if (detail.chapters.isEmpty() || stream.isNullOrBlank() || !streamProbe(stream)) {
                consecutiveFailures++
                failed++
                if (consecutiveFailures >= budget.maxConsecutiveFailures) break
                continue
            }

            import(source.sourceId, detail)
            imported++
            verified++
            consecutiveFailures = 0
        }
        return SeedResult(imported, verified, failed, skippedKnown)
    }
}
