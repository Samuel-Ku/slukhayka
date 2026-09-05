package com.slukhayka.audiobooks.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * Turns an explicit Play gesture into exactly one automatic recovery pass.
 * Recovery failures cannot claim again, so browser recovery never loops.
 * A factual playback-start callback may arm the book for a later network drop.
 */
class AutomaticPlaybackRecoveryGate {
    private val armedBookIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * A new asynchronous start supersedes the previous player session. While
     * its chapters/source are being resolved, an error left by that previous
     * session must not spend the new gesture's recovery allowance.
     */
    fun beginAttempt(bookId: String) {
        if (bookId.isNotBlank()) armedBookIds.clear()
    }

    fun arm(bookId: String) {
        if (bookId.isNotBlank()) armedBookIds += bookId
    }

    fun claimFailure(bookId: String): Boolean = armedBookIds.remove(bookId)
}
