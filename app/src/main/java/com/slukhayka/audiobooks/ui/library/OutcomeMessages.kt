package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.imports.LocalImportResult

/**
 * ADR-0008 — the download/import outcome strings as pure functions.
 *
 * The one-shot user-facing messages for offline downloads, folder imports and
 * rescans used to be composed inside [com.slukhayka.audiobooks.ui.MainViewModel] coroutines,
 * where the string rules were untestable. Extracted here — same rules, same
 * Ukrainian strings — so the JVM test suite can pin every branch without
 * Robolectric (prior art: [LibraryModel], [ListenComposer]).
 */
object OutcomeMessages {

    /**
     * The snackbar text for a finished offline-download attempt. `totalChapters
     * == 0` means no audio could be found at all; a partial download reports
     * how many chapters made it to disk; a full one confirms the book is
     * offline-ready.
     */
    fun downloadOutcome(result: OfflineDownloads.OfflineDownloadResult): String = when {
        result.totalChapters == 0 ->
            "Не вдалося знайти аудіо для завантаження. Перевірте з'єднання."
        result.downloadedChapters == 0 ->
            "Не вдалося завантажити книгу. Спробуйте пізніше."
        result.downloadedChapters < result.totalChapters ->
            "Завантажено ${result.downloadedChapters} з ${result.totalChapters} глав"
        else -> "Книгу завантажено для офлайн-прослуховування"
    }

    /** The snackbar text when the download attempt itself threw. */
    fun downloadFailure(): String = "Не вдалося завантажити книгу"

    /**
     * The snackbar text after a confirmed folder-import preview applies.
     * Imported books report their file count; a fully-duplicate folder says so
     * instead of pretending new books arrived; a no-op plan falls back to the
     * neutral completion line.
     */
    fun importOutcome(result: LocalImportResult): String = if (result.booksImported > 0) {
        buildString {
            append("Імпортовано ${result.booksImported} книг (${result.filesImported} файлів)")
            if (result.duplicateFiles > 0) append(" · ${result.duplicateFiles} дублікатів пропущено")
            if (result.skippedFiles > 0) append(" · ${result.skippedFiles} не вдалося прочитати")
        }
    } else if (result.duplicateFiles > 0) {
        "Всі файли вже в бібліотеці (${result.duplicateFiles} дублікатів пропущено)"
    } else {
        "Імпорт завершено"
    }

    /**
     * The snackbar text after a re-scan of every previously imported folder.
     * New chapters/books are reported first; missing, moved and duplicate
     * files append as caveats; an unchanged library says so plainly. Nothing
     * is ever deleted by a re-scan, so the message never claims deletion.
     */
    fun rescanOutcome(totals: LibraryImport.RescanReport): String = buildString {
        append("Пересканування завершено")
        when {
            totals.newChapters > 0 || totals.newBooks > 0 -> {
                append(": +${totals.newChapters} глав")
                if (totals.newBooks > 0) append(" (${totals.newBooks} нових книг)")
            }
            else -> append(" — змін не знайдено")
        }
        if (totals.missingFiles > 0) append(" · ${totals.missingFiles} файлів зникло")
        if (totals.movedFiles > 0) append(" · ${totals.movedFiles} перейменовано")
        if (totals.duplicateFiles > 0) append(" · ${totals.duplicateFiles} дублікатів пропущено")
    }
}
