package com.slukhayka.audiobooks.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [FailureCategory] (wayfinder #61 Q1, stage-2 S1).
 * Only external behavior: the error-code-to-category mapping and its
 * stability (string constants, never throws).
 */
class FailureCategoryTest {

    @Test
    fun `network and http failures are SOURCE_LOST`() {
        for (code in listOf(
            "ERROR_CODE_IO_UNSPECIFIED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
            "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED",
            "ERROR_CODE_TIMEOUT",
            "ERROR_CODE_LOADING_FAILED"
        )) {
            assertEquals("$code must be SOURCE_LOST", FailureCategory.SOURCE_LOST, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `missing or unreadable files are FILE_UNAVAILABLE`() {
        for (code in listOf(
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NO_PERMISSION",
            "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"
        )) {
            assertEquals("$code must be FILE_UNAVAILABLE", FailureCategory.FILE_UNAVAILABLE, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `parse and decode failures are FILE_CORRUPT`() {
        for (code in listOf(
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
            "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
            "ERROR_CODE_MANIFEST_PARSING_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_AUDIO_DECODING_FAILED"
        )) {
            assertEquals("$code must be FILE_CORRUPT", FailureCategory.FILE_CORRUPT, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `startup and init failures are START_FAILED`() {
        assertEquals(FailureCategory.START_FAILED, FailureCategory.fromErrorCodeName("PREPARE_TIMEOUT"))
        for (code in listOf(
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_AUDIO_DECODER_INIT_FAILED",
            "ERROR_CODE_PLAYER_CREATION_ERROR",
            "ERROR_CODE_AUDIO_TRACK_INIT_FAILED"
        )) {
            assertEquals("$code must be START_FAILED", FailureCategory.START_FAILED, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `mid-flight interrupts are INTERRUPTED`() {
        for (code in listOf(
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_AUDIO_DECODER_QUERY_FAILED",
            "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED",
            "ERROR_CODE_DETACHED_SURFACE"
        )) {
            assertEquals("$code must be INTERRUPTED", FailureCategory.INTERRUPTED, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `synthetic category codes pass through to themselves`() {
        for (code in listOf(
            FailureCategory.SAF_LOST,
            FailureCategory.DURATION_MISMATCH,
            FailureCategory.SYNC_CONFLICT,
            FailureCategory.DOWNLOAD_INTERRUPTED
        )) {
            assertEquals(code, FailureCategory.fromErrorCodeName(code))
        }
    }

    @Test
    fun `unknown codes and exception names fall back to UNKNOWN and never throw`() {
        for (input in listOf(
            "",
            "ERROR_CODE_DRM_UNSUPPORTED",
            "IOException",
            "HttpTimeoutException",
            "UNKNOWN",
            "some random string"
        )) {
            assertEquals("$input must be UNKNOWN", FailureCategory.UNKNOWN, FailureCategory.fromErrorCodeName(input))
        }
    }
}
