package com.example.ui.library

import com.example.data.downloads.OfflineDownloads
import com.example.data.imports.LibraryImport
import com.example.data.imports.LocalImportResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the ADR-0008 outcome messages (no Robolectric). The
 * matrices below pin every branch of the download / import / rescan strings
 * table-style — the same strings the ViewModel's orchestration used before
 * the extraction, so behaviour is byte-identical (#153/#155).
 */
class OutcomeMessagesTest {

    // --- download outcomes (table-driven matrix) ---------------------------

    /**
     * The four download branches — no audio / every chapter failed / partial /
     * complete — as one table, so a new outcome can never be added without
     * pinning its string.
     */
    @Test
    fun `download outcome matrix`() {
        val cases = listOf(
            // (downloadedChapters, totalChapters, expected)
            Triple(0, 0, "Не вдалося знайти аудіо для завантаження. Перевірте з'єднання."),
            Triple(0, 12, "Не вдалося завантажити книгу. Спробуйте пізніше."),
            Triple(7, 12, "Завантажено 7 з 12 глав"),
            Triple(1, 1, "Книгу завантажено для офлайн-прослуховування"),
            Triple(12, 12, "Книгу завантажено для офлайн-прослуховування")
        )
        cases.forEach { (downloaded, total, expected) ->
            assertEquals(
                "downloadOutcome(downloaded=$downloaded, total=$total)",
                expected,
                OutcomeMessages.downloadOutcome(
                    OfflineDownloads.OfflineDownloadResult(downloaded, total)
                )
            )
        }
    }

    @Test
    fun `download - failure string is stable`() {
        assertEquals("Не вдалося завантажити книгу", OutcomeMessages.downloadFailure())
    }

    // --- import outcomes (table-driven matrix) ------------------------------

    private data class ImportCase(
        val books: Int,
        val files: Int,
        val skipped: Int,
        val duplicates: Int,
        val expected: String
    )

    @Test
    fun `import outcome matrix`() {
        val cases = listOf(
            ImportCase(
                3, 25, 2, 4,
                "Імпортовано 3 книг (25 файлів) · 4 дублікатів пропущено · 2 не вдалося прочитати"
            ),
            ImportCase(1, 5, 0, 0, "Імпортовано 1 книг (5 файлів)"),
            ImportCase(0, 0, 0, 6, "Всі файли вже в бібліотеці (6 дублікатів пропущено)"),
            ImportCase(0, 0, 0, 0, "Імпорт завершено")
        )
        cases.forEach { (books, files, skipped, duplicates, expected) ->
            val result = LocalImportResult(
                booksImported = books,
                filesImported = files,
                skippedFiles = skipped,
                duplicateFiles = duplicates
            )
            assertEquals(
                "importOutcome(books=$books, files=$files, skipped=$skipped, duplicates=$duplicates)",
                expected,
                OutcomeMessages.importOutcome(result)
            )
        }
    }

    // --- rescan outcomes (table-driven matrix) ------------------------------

    private fun rescan(
        newChapters: Int = 0,
        newBooks: Int = 0,
        missingFiles: Int = 0,
        movedFiles: Int = 0,
        duplicateFiles: Int = 0
    ) = LibraryImport.RescanReport(
        treeUri = "content://tree",
        newChapters = newChapters,
        newBooks = newBooks,
        missingFiles = missingFiles,
        movedFiles = movedFiles,
        duplicateFiles = duplicateFiles
    )

    private data class RescanCase(
        val newChapters: Int,
        val newBooks: Int,
        val missing: Int,
        val moved: Int,
        val duplicates: Int,
        val expected: String
    )

    @Test
    fun `rescan outcome matrix`() {
        val cases = listOf(
            RescanCase(
                4, 1, 2, 1, 3,
                "Пересканування завершено: +4 глав (1 нових книг) · 2 файлів зникло · 1 перейменовано · 3 дублікатів пропущено"
            ),
            RescanCase(2, 0, 0, 0, 0, "Пересканування завершено: +2 глав"),
            RescanCase(0, 0, 0, 0, 0, "Пересканування завершено — змін не знайдено"),
            RescanCase(0, 0, 1, 0, 0, "Пересканування завершено — змін не знайдено · 1 файлів зникло"),
            RescanCase(0, 0, 0, 2, 0, "Пересканування завершено — змін не знайдено · 2 перейменовано")
        )
        cases.forEach { (newChapters, newBooks, missing, moved, duplicates, expected) ->
            val totals = rescan(
                newChapters = newChapters,
                newBooks = newBooks,
                missingFiles = missing,
                movedFiles = moved,
                duplicateFiles = duplicates
            )
            assertEquals(
                "rescanOutcome(newChapters=$newChapters, newBooks=$newBooks, missing=$missing, moved=$moved, duplicates=$duplicates)",
                expected,
                OutcomeMessages.rescanOutcome(totals)
            )
        }
    }
}
