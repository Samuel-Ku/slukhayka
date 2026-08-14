package com.example.data.db

/**
 * Stable diagnosability buckets for the playback-failure ledger
 * (wayfinder #61 Q1, stage-2 S1/S7). A pure function maps a failure's
 * error code name (Media3 `PlaybackException.errorCodeName`, our synthetic
 * codes like PREPARE_TIMEOUT, or an exception class simple name) to one
 * coarse category, so support can group failures without parsing opaque
 * error strings. Pure JVM — no Android APIs, unit-testable alone.
 */
object FailureCategory {
    const val START_FAILED = "START_FAILED"
    const val FILE_UNAVAILABLE = "FILE_UNAVAILABLE"
    const val FILE_CORRUPT = "FILE_CORRUPT"
    const val SAF_LOST = "SAF_LOST"
    const val DURATION_MISMATCH = "DURATION_MISMATCH"
    const val INTERRUPTED = "INTERRUPTED"
    const val SYNC_CONFLICT = "SYNC_CONFLICT"
    const val DOWNLOAD_INTERRUPTED = "DOWNLOAD_INTERRUPTED"
    const val SOURCE_LOST = "SOURCE_LOST"
    const val UNKNOWN = "UNKNOWN"

    /**
     * Maps an error code name to a category. Media3 codes (ERROR_CODE_*)
     * classify by kind; our synthetic codes and the exact codes listed in
     * #61 Q1 pass through to themselves. Anything unrecognized lands in
     * [UNKNOWN] — never throws.
     */
    fun fromErrorCodeName(errorCodeName: String): String = when (errorCodeName) {
        // Synthetic codes that already carry their category.
        SAF_LOST, DURATION_MISMATCH, INTERRUPTED, SYNC_CONFLICT,
        DOWNLOAD_INTERRUPTED, START_FAILED, FILE_UNAVAILABLE, FILE_CORRUPT,
        SOURCE_LOST -> errorCodeName

        // The 45s prepare timeout is a start that never reached READY.
        "PREPARE_TIMEOUT" -> START_FAILED

        // Media3 IO / network / HTTP family — the remote source is unreachable.
        "ERROR_CODE_IO_UNSPECIFIED",
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
        "ERROR_CODE_IO_BAD_HTTP_STATUS",
        "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
        "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED",
        "ERROR_CODE_TIMEOUT",
        "ERROR_CODE_LOADING_FAILED" -> SOURCE_LOST

        // The bytes cannot be found or read where they should be.
        "ERROR_CODE_IO_FILE_NOT_FOUND",
        "ERROR_CODE_IO_NO_PERMISSION",
        "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE" -> FILE_UNAVAILABLE

        // The container / stream cannot be parsed or decoded — corrupt media.
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
        "ERROR_CODE_MANIFEST_PARSING_FAILED",
        "ERROR_CODE_DECODING_FAILED",
        "ERROR_CODE_AUDIO_DECODING_FAILED" -> FILE_CORRUPT

        // The pipeline failed to start (decoder / player / audio track init).
        "ERROR_CODE_DECODER_INIT_FAILED",
        "ERROR_CODE_AUDIO_DECODER_INIT_FAILED",
        "ERROR_CODE_PLAYER_CREATION_ERROR",
        "ERROR_CODE_AUDIO_TRACK_INIT_FAILED" -> START_FAILED

        // An unexpected interrupt while the pipeline was mid-flight.
        "ERROR_CODE_DECODER_QUERY_FAILED",
        "ERROR_CODE_AUDIO_DECODER_QUERY_FAILED",
        "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
        "ERROR_CODE_DETACHED_SURFACE" -> INTERRUPTED

        // Everything else — DRM, remote errors, unknown exception names.
        else -> UNKNOWN
    }
}
