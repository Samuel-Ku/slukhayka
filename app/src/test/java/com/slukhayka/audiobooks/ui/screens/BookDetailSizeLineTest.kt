package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1628 — a bare «невідомо» under «Додати до добірки» was a label-less value
 * that read as a broken UI. The size line now states only a FACT; the mapping
 * is pure, so the unknown cases are pinned without a composed screen.
 */
class BookDetailSizeLineTest {

    private fun line(
        hasBytesProgress: Boolean = false,
        completedChapters: Int = 0,
        totalChapters: Int = 0,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
        estimatedTotalBytes: Long? = null,
        estimatedApproximate: Boolean = false
    ) = bookDetailSizeLine(
        hasBytesProgress = hasBytesProgress,
        completedChapters = completedChapters,
        totalChapters = totalChapters,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        estimatedTotalBytes = estimatedTotalBytes,
        estimatedApproximate = estimatedApproximate
    )

    @Test
    fun `no progress and no estimate hides the line instead of a bare unknown`() {
        assertEquals(BookDetailSizeLine.Hidden, line())
    }

    @Test
    fun `an estimate without a whole megabyte hides the line`() {
        assertEquals(BookDetailSizeLine.Hidden, line(estimatedTotalBytes = null))
        assertEquals(BookDetailSizeLine.Hidden, line(estimatedTotalBytes = 0L))
        // Sub-megabyte estimates would render as «0 МБ» — a non-fact.
        assertEquals(BookDetailSizeLine.Hidden, line(estimatedTotalBytes = 1L))
        assertEquals(
            BookDetailSizeLine.Hidden,
            line(estimatedTotalBytes = 1024L * 1024L - 1L)
        )
    }

    @Test
    fun `a real estimate renders in whole megabytes`() {
        assertEquals(
            BookDetailSizeLine.Estimated(mb = 120L, approximate = false),
            line(estimatedTotalBytes = 120L * 1024L * 1024L)
        )
        assertEquals(
            BookDetailSizeLine.Estimated(mb = 90L, approximate = true),
            line(estimatedTotalBytes = 90L * 1024L * 1024L, estimatedApproximate = true)
        )
    }

    @Test
    fun `live progress keeps its chapters and exact byte totals`() {
        assertEquals(
            BookDetailSizeLine.Downloading(
                completedChapters = 2,
                totalChapters = 10,
                downloadedBytes = 3L * 1024L * 1024L,
                totalBytes = 15L * 1024L * 1024L
            ),
            line(
                hasBytesProgress = true,
                completedChapters = 2,
                totalChapters = 10,
                downloadedBytes = 3L * 1024L * 1024L,
                totalBytes = 15L * 1024L * 1024L
            )
        )
    }

    @Test
    fun `live progress with an unknown total is still a fact and keeps the line`() {
        val unknownTotal = line(
            hasBytesProgress = true,
            completedChapters = 1,
            totalChapters = 4,
            downloadedBytes = 7L * 1024L * 1024L,
            totalBytes = null
        )
        assertEquals(
            BookDetailSizeLine.Downloading(
                completedChapters = 1,
                totalChapters = 4,
                downloadedBytes = 7L * 1024L * 1024L,
                totalBytes = null
            ),
            unknownTotal
        )
    }
}
