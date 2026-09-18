package com.slukhayka.audiobooks.data.ingest

/**
 * #829 — the honest refusal. When the fetcher cannot work, the listener gets a
 * REASON and a way to fix it, never an empty screen (ADR-0014: no fabricated
 * state, and the ticket's own words: «чесна відмова з deep link на доєднання, а
 * не порожнеча»).
 */
object TelegramMembershipPolicy {

    /** The community group's public join link — the deep link we hand over. */
    const val GROUP_URL = "https://t.me/slukhayka"

    enum class Reason {
        /** No Telegram session on this device: the listener has not logged in. */
        NO_SESSION,

        /** Logged in, but the listener is not in the group yet. */
        NOT_A_MEMBER
    }

    /** What the surface must say, and where to send the listener to fix it. */
    data class Refusal(val reason: Reason, val joinUrl: String)

    /**
     * @param sessionReady whether a local Telegram session exists on this device
     * @param isMember whether THAT account is in the community group
     * @return the refusal to show, or null when the fetcher may proceed
     */
    fun refusalFor(sessionReady: Boolean, isMember: Boolean): Refusal? = when {
        !sessionReady -> Refusal(Reason.NO_SESSION, GROUP_URL)
        !isMember -> Refusal(Reason.NOT_A_MEMBER, GROUP_URL)
        else -> null
    }
}
