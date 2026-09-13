package com.slukhayka.audiobooks.player

/**
 * #528 — the whole force of the stream-body guard, as a loop over two
 * lambdas.
 *
 * It is separated from the Media3 wrapper that calls it so the rule can be
 * proven as plain JVM logic: no player, no sockets, no Android types. The
 * wrapper only supplies «open a body» and «drop a body».
 *
 * The substitution is intermittent, so one more knock on the same URL usually
 * returns the real file; after [maxAttempts] knocks the body is refused rather
 * than played. A refused body is always closed first — an ad we rejected must
 * not keep a stream open.
 */
internal object StreamBodyGuard {

    /** The opening request plus one more knock — never a loop. */
    const val MAX_OPEN_ATTEMPTS = 2

    /**
     * @param uri the stream URI, carried into the failure for the log.
     * @param open opens one body and returns its resolved length.
     * @param close drops the currently open body.
     * @param expectation classifies a length; null accepts the body.
     * @return the accepted body's length.
     * @throws SubstitutedStreamException when every attempt was refused.
     */
    fun accept(
        uri: String?,
        open: () -> Long,
        close: () -> Unit,
        expectation: (Long) -> Pair<Long, Long>?,
        maxAttempts: Int = MAX_OPEN_ATTEMPTS
    ): Long {
        var attempt = 1
        while (true) {
            val length = open()
            val (expected, observed) = expectation(length) ?: return length
            close()
            if (attempt >= maxAttempts) {
                throw SubstitutedStreamException(expected, observed, uri)
            }
            attempt++
        }
    }
}
