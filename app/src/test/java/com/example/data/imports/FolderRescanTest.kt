package com.example.data.imports

import com.example.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure classifier tests for [FolderRescan] (wayfinder #42): the diff buckets
 * (new / missing / moved / duplicate / unchanged) are decided by content hash
 * alone — never by path or name.
 */
class FolderRescanTest {

    private fun chapter(id: String, title: String, hash: String?) = ChapterEntity(
        id = id,
        bookId = "book",
        chapterIndex = 0,
        title = title,
        durationSeconds = 0L,
        streamUrl = "/local/$id",
        localFilePath = "/local/$id",
        isDownloaded = true,
        contentHash = hash
    )

    private fun file(name: String, folder: String? = "Кобзар", hash: String) =
        FolderRescan.RescanFile(name, folder, hash)

    @Test
    fun `a file whose bytes are unknown is new`() {
        val diff = FolderRescan.computeDiff(
            chapters = listOf(chapter("c1", "01.mp3", "aaa")),
            libraryHashSet = setOf("aaa"),
            scanned = listOf(file("02.mp3", hash = "bbb"))
        )
        assertEquals(listOf("bbb"), diff.newFiles.map { it.contentHash })
        assertTrue(diff.changed)
    }

    @Test
    fun `a stored chapter whose hash is gone from the tree is missing`() {
        val diff = FolderRescan.computeDiff(
            chapters = listOf(chapter("c1", "01.mp3", "aaa"), chapter("c2", "02.mp3", "bbb")),
            libraryHashSet = setOf("aaa", "bbb"),
            scanned = listOf(file("02.mp3", hash = "bbb"))
        )
        assertEquals(listOf("c1"), diff.missingChapters.map { it.id })
        assertEquals(listOf("bbb"), diff.unchangedFiles.map { it.contentHash })
        assertTrue(diff.changed)
    }

    @Test
    fun `same bytes under a different name is moved not new`() {
        val diff = FolderRescan.computeDiff(
            chapters = listOf(chapter("c1", "01.mp3", "aaa")),
            libraryHashSet = setOf("aaa"),
            scanned = listOf(file("глава-перша.mp3", hash = "aaa"))
        )
        assertEquals(listOf("aaa"), diff.movedFiles.map { it.contentHash })
        assertTrue(diff.newFiles.isEmpty())
    }

    @Test
    fun `bytes that live in another library entry are duplicates never copies`() {
        // The book owns only "aaa"; "bbb" exists elsewhere in the library.
        val diff = FolderRescan.computeDiff(
            chapters = listOf(chapter("c1", "01.mp3", "aaa")),
            libraryHashSet = setOf("aaa", "bbb"),
            scanned = listOf(file("02.mp3", hash = "bbb"))
        )
        assertEquals(listOf("bbb"), diff.duplicateFiles.map { it.contentHash })
        assertTrue(diff.newFiles.isEmpty())
    }

    @Test
    fun `an exact match is unchanged and nothing else fires`() {
        val diff = FolderRescan.computeDiff(
            chapters = listOf(chapter("c1", "01.mp3", "aaa")),
            libraryHashSet = setOf("aaa"),
            scanned = listOf(file("01.mp3", hash = "aaa"))
        )
        assertEquals(listOf("aaa"), diff.unchangedFiles.map { it.contentHash })
        assertFalse(diff.changed)
    }

    @Test
    fun `a book with no stored hashes reports every live file as new`() {
        val diff = FolderRescan.computeDiff(
            chapters = emptyList(),
            libraryHashSet = emptySet(),
            scanned = listOf(file("01.mp3", hash = "aaa"), file("02.mp3", hash = "bbb"))
        )
        assertEquals(2, diff.newFiles.size)
        assertEquals(0, diff.missingChapters.size)
    }
}
