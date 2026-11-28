package com.slukhayka.audiobooks.data.recommend

/** Single-flight boundary between a derived vector pass and model installation. */
class EmbeddingPassGate {
    class Ticket internal constructor(internal val generation: Long)
    private var active: Ticket? = null
    private var generation = 0L
    private var pending = false

    @Synchronized fun begin(onStarted: () -> Unit = {}): Ticket? {
        if (active != null) {
            pending = true
            return null
        }
        pending = false
        val ticket = Ticket(generation).also { active = it }
        return try {
            onStarted()
            ticket
        } catch (failure: Throwable) {
            active = null
            throw failure
        }
    }

    /** Callback must only reset backend state and publish an empty snapshot, never load a backend. */
    @Synchronized fun invalidate(clearPublished: () -> Unit) {
        generation++
        if (active != null) pending = true
        clearPublished()
    }

    @Synchronized fun finish(ticket: Ticket, publish: () -> Unit) {
        require(active === ticket) { "A stale ticket cannot finish another pass" }
        try {
            if (ticket.generation == generation) publish()
        } finally {
            active = null
        }
    }

    /** Drain in the caller's finally block, including when publication throws. */
    @Synchronized fun takePendingRerun(): Boolean {
        if (active != null || !pending) return false
        pending = false
        return true
    }
}
