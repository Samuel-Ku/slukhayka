package com.slukhayka.audiobooks.data.bibliography

/**
 * ADR-0053 §2 / #858 (T5) — the external-bibliography CHAIN: Open Library
 * primary, Google Books fallback by ISBN.
 *
 * ## When the fallback fires — and when it deliberately does not
 *
 * The trigger is EXACTLY one outcome: [BibliographyOutcome.Found] with an
 * EMPTY list, i.e. Open Library positively stated that it does not know this
 * ISBN. That is the ticket's own wording («коли Open Library видання не
 * знає») and it is a KNOWLEDGE gap.
 *
 * [BibliographyOutcome.Deferred] (the politeness budget is resting) and
 * [BibliographyOutcome.Unavailable] (the fetch failed, the body was blank or
 * unparseable) are INFRASTRUCTURE states, and they propagate unchanged. The
 * reasoning, so the next reader does not "fix" it:
 *
 *  1. **Provenance.** [BibliographyOutcome] carries no field naming which base
 *     answered. If a failed Open Library call fell through to Google Books, the
 *     surface would show a card as if the base had answered while the base it
 *     was configured to ask never did — the honest-state rule of ADR-0039/0040
 *     («чесний частковий стан, ніколи не фабрикація») forbids exactly that
 *     silent substitution. Both states exist so a screen can say WHICH truth it
 *     shows, and the chain must not erase the difference.
 *  2. **The spec names a knowledge gap, not an outage.** Nothing in #853/#858
 *     asks for "Open Library down → try Google Books". Turning a failure into
 *     a different base's answer is a new product decision, not a fallback.
 *  3. **Cost.** A fallback on failure would fire at exactly the moment the
 *     network or a base is unhealthy, doubling requests and spending the
 *     worker's shared Google Books daily quota on a guess. A fallback on a
 *     positive "unknown" is cacheable for the full 24 h TTL and bounded.
 *  4. **The rhythm stays one.** Each door keeps its own declared profile and
 *     its own host bucket; the chain never classifies or re-times anything.
 *
 * If a future surface wants "try both on failure", it can call
 * [GoogleBooksBibliography] explicitly and show the provenance itself — the
 * chain must not decide that silently.
 *
 * Only the ISBN door is composed: #858 scopes Google Books to the ISBN
 * fallback («Google Books лише fallback за ISBN»), while OL's `search`/`work`
 * doors stay the primary's alone.
 */
class BibliographyChain(
    private val primary: OpenLibraryBibliography,
    /**
     * The ISBN fallback. Null while no Web Transport origin is configured —
     * the provider cannot exist without one (#858 hides the Google Books key
     * behind that worker, and a keyless GB answers 429), so an unconfigured
     * install has an honest primary-only chain rather than a fabricated base.
     */
    private val fallback: GoogleBooksBibliography? = null
) {

    /**
     * One `title`-independent ISBN lookup: the primary's real answer when it
     * has one, the fallback's when (and only when) the primary stated absence,
     * and the primary's honest Deferred/Unavailable otherwise.
     */
    suspend fun isbn(raw: String): BibliographyOutcome<List<BibliographyCandidate>> {
        val primaryOutcome = primary.isbn(raw)
        if (primaryOutcome !is BibliographyOutcome.Found) return primaryOutcome
        if (primaryOutcome.value.isNotEmpty()) return primaryOutcome
        return fallback?.isbn(raw) ?: primaryOutcome
    }

    /**
     * #857 (T4) — the search door of the corner: a title and/or author the
     * listener typed, answered by the PRIMARY alone.
     *
     * There is deliberately no fallback here, and that is the scoped decision
     * rather than an omission: #858 confines Google Books to the ISBN fallback,
     * because without a key it answers 429. A search that quietly fell through
     * to a keyless GB would spend the listener's wait on a request that cannot
     * succeed, so an honest primary-only answer is the correct composition.
     *
     * A partial answer under a spent budget stays partial
     * ([BibliographyOutcome.Deferred]/`Unavailable`), never a fabricated or
     * silently widened list — the ADR-0040 rule the provider already follows.
     */
    suspend fun search(
        query: String,
        limit: Int = OpenLibraryBibliography.DEFAULT_LIMIT
    ): BibliographyOutcome<List<BibliographyCandidate>> = primary.search(query, limit)
}
