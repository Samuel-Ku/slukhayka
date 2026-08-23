package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random

/**
 * Spec-40 #275 (t1) — the generated default nickname («Слухач-%04d»). Pure:
 * the randomness comes from the injected generator, so a seeded Random makes
 * the result deterministic for tests (and reproducible across processes if
 * ever needed). A collision between two listeners is harmless — nicknames
 * are display-only and editable.
 */
object Nicknames {

    /** Nicknames count upward from «Слухач-0000» to «Слухач-9999». */
    const val RANGE = 10_000

    fun generate(random: Random = Random.Default): String {
        val number = random.nextInt(RANGE)
        // Manual zero-padding, NOT "%04d": String.format renders localized
        // (non-ASCII) digits under several device locales.
        return "Слухач-" + number.toString().padStart(4, '0')
    }
}
