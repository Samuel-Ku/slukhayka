package com.example.data.imports

import android.content.Context
import androidx.documentfile.provider.FakeDocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-walk tests for [LocalFolderScanner] (spec #8 Block 4): recursion into
 * sub-directories, extension filtering, and parent-folder attribution. The
 * fake [androidx.documentfile.provider.FakeDocumentFile] tree never touches a
 * ContentResolver — streams are only opened lazily by the importer, never by
 * the scanner itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalFolderScannerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `scan collects audio files recursively and attributes parent folders`() {
        val tree = FakeDocumentFile(
            "Books", directory = true,
            children = listOf(
                FakeDocumentFile("Кобзар", directory = true, children = listOf(
                    FakeDocumentFile("01.mp3", directory = false),
                    FakeDocumentFile("02.mp3", directory = false)
                )),
                FakeDocumentFile("Лісова пісня.mp3", directory = false),
                FakeDocumentFile("readme.txt", directory = false),
                FakeDocumentFile("chapter.ogg", directory = false)
            )
        )

        val entries = LocalFolderScanner.scan(tree, context.contentResolver)

        assertEquals(4, entries.size)

        val folderEntries = entries.filter { it.parentFolder == "Кобзар" }
        assertEquals(2, folderEntries.size)

        val rootEntries = entries.filter { it.parentFolder == null }
        assertEquals(listOf("Лісова пісня.mp3", "chapter.ogg"), rootEntries.map { it.fileName })
    }

    @Test
    fun `scan attributes the full relative path so same-named folders stay distinct`() {
        val tree = FakeDocumentFile(
            "Books", directory = true,
            children = listOf(
                FakeDocumentFile("SeriesA", directory = true, children = listOf(
                    FakeDocumentFile("Кобзар", directory = true, children = listOf(
                        FakeDocumentFile("01.mp3", directory = false)
                    ))
                )),
                FakeDocumentFile("SeriesB", directory = true, children = listOf(
                    FakeDocumentFile("Кобзар", directory = true, children = listOf(
                        FakeDocumentFile("01.mp3", directory = false)
                    ))
                ))
            )
        )

        val entries = LocalFolderScanner.scan(tree, context.contentResolver)

        assertEquals(2, entries.size)
        assertEquals("SeriesA/Кобзар", entries[0].parentFolder)
        assertEquals("SeriesB/Кобзар", entries[1].parentFolder)
    }

    @Test
    fun `scan skips unsupported extensions and empty directories`() {
        val tree = FakeDocumentFile(
            "Books", directory = true,
            children = listOf(
                FakeDocumentFile("book.flac", directory = false),
                FakeDocumentFile("notes.txt", directory = false),
                FakeDocumentFile("audio.m4b", directory = false),
                FakeDocumentFile("empty", directory = true)
            )
        )

        val entries = LocalFolderScanner.scan(tree, context.contentResolver)

        assertEquals(listOf("audio.m4b"), entries.map { it.fileName })
    }

    @Test
    fun `scan of a file root returns just that file`() {
        val single = FakeDocumentFile("solo.mp3", directory = false)
        val entries = LocalFolderScanner.scan(single, context.contentResolver)
        assertEquals(1, entries.size)
        assertEquals("solo.mp3", entries.first().fileName)
        assertTrue(entries.first().parentFolder == null)
    }
}
