package com.slukhayka.audiobooks.data.collective

/**
 * #522 / ADR-0028 — the transport seam of the collective catalogue lane. It
 * speaks the pure [CollectiveCardPublication] contract, so the Firestore
 * adapter stays a thin transport and the delta logic is unit-testable without
 * Firebase. Every method is best-effort: a miss, a failure or a corrupt
 * document contributes nothing and never throws.
 */
interface CollectiveCardStore {

    /** Publishes one verified card; a rejected/invalid card is a no-op. */
    suspend fun putCard(card: CollectiveCardPublication) = Unit

    /** Bounded ordered remote delta page; applying it belongs to the sync lane. */
    suspend fun getCardsPage(after: CollectiveCursor?, limit: Int): CollectivePage =
        CollectivePage(emptyList(), null)
}
