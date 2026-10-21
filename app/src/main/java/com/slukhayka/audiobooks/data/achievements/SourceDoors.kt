package com.slukhayka.audiobooks.data.achievements

/**
 * #701 (US40) — «Усі двері»: whether the listener really went through EVERY
 * registered door.
 *
 * The required set is DERIVED, never listed by hand: it is the registry's own
 * ids minus the scam ones ([com.slukhayka.audiobooks.data.source.SourceRegistry]),
 * so adding a source makes the award harder the moment the registry learns it
 * — exactly the acceptance criterion of #701. An award already earned is never
 * taken back: the evaluator skips ids the store already holds.
 *
 * A scam source is not a door a listener passes honestly — its audio is not
 * the book (ADR-0038) — so it is neither required nor counted.
 *
 * An EMPTY required set answers 0: "nobody told me which doors exist" must not
 * read as "all of them were used", which is the answer that OPENS the award
 * (ADR-0014).
 */
object SourceDoors {

    /** 1 when every required door was used, else 0. */
    fun allUsed(usedTypes: Collection<String>, requiredDoors: Set<String>): Long =
        if (requiredDoors.isNotEmpty() && usedTypes.containsAll(requiredDoors)) 1L else 0L
}
