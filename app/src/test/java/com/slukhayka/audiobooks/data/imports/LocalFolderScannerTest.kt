package com.slukhayka.audiobooks.data.imports

import android.content.Context
import android.net.Uri
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
        assertEquals("content://fake/solo.mp3", entries.first().uri)
    }

    @Test
    fun `scan keeps each file's document uri as the by-reference address`() {
        val tree = FakeDocumentFile(
            "Books", directory = true,
            children = listOf(
                FakeDocumentFile("Кобзар", directory = true, children = listOf(
                    FakeDocumentFile("01.mp3", directory = false)
                )),
                FakeDocumentFile("solo.mp3", directory = false)
            )
        )

        val entries = LocalFolderScanner.scan(tree, context.contentResolver)

        assertEquals(
            listOf("content://fake/01.mp3", "content://fake/solo.mp3"),
            entries.map { it.uri }
        )
        assertEquals(listOf("Кобзар", null), entries.map { it.parentFolder })
    }

    /**
     * #1374: гілка названої відмови мусить мати тест, інакше наступний
     * рефактор тихо поверне голий NPE.
     *
     * Природний шлях недосяжний: `ContentResolver.openInputStream` — `final`,
     * `ShadowContentResolver` цієї версії не має `setInputStream`, а невідомий
     * URI кидає `FileNotFoundException`, а не вертає `null`. Тому opener
     * підставляється явно — і саме це перевіряємо: потік не відкрився →
     * відмова НАЗИВАЄ документ, а не падає безіменним NPE.
     */
    @Test
    fun `openOrFail names the document when the stream is gone`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Uri.parse("content://com.slukhayka.test/vanished")

        val failure = try {
            LocalFolderScanner.openOrFail(resolver, uri) { _, _ -> null }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue("очікували названу відмову, а не тихий успіх", failure != null)
        // assertTrue не дає smart-cast, тому повідомлення дістаємо явно.
        val message = failure?.message.orEmpty()
        assertTrue(
            "відмова мусить називати документ, а не бути безіменною: «$message»",
            message.contains("no longer readable") && message.contains(uri.toString()),
        )
    }

    @Test
    fun `scan captures SAF size and last-modified beside the address`() {
        val tree = FakeDocumentFile(
            "Books", directory = true,
            children = listOf(
                FakeDocumentFile(
                    "01.mp3",
                    directory = false,
                    sizeBytes = 1_234L,
                    lastModifiedMs = 1_700_000_000_000L
                )
            )
        )

        val entry = LocalFolderScanner.scan(tree, context.contentResolver).single()

        assertEquals(1_234L, entry.sizeBytes)
        assertEquals(1_700_000_000_000L, entry.lastModifiedMs)
    }

    @Test
    fun `an unknown SAF size or timestamp stays unknown, never zero`() {
        val entry = LocalFolderScanner.scan(
            FakeDocumentFile("solo.mp3", directory = false, sizeBytes = 0L, lastModifiedMs = 0L),
            context.contentResolver
        ).single()

        assertTrue(entry.sizeBytes == null)
        assertTrue(entry.lastModifiedMs == null)
    }
}
