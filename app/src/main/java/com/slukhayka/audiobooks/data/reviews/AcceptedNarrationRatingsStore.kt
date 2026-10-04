package com.slukhayka.audiobooks.data.reviews

/** Observes successful listener writes only, never public or historical ratings. */
class AcceptedNarrationRatingsStore(private val delegate: NarrationRatingsStore,
    private val onAccepted: suspend (NarrationRating) -> Unit) : NarrationRatingsStore by delegate {
    override suspend fun putRating(rating: NarrationRating): Boolean {
        val accepted = delegate.putRating(rating)
        if (accepted) onAccepted(rating)
        return accepted
    }
}
