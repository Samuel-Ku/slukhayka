package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.imports.LocalCopyMigration
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
     * offline-ready. When chapters were shared/reused via dedup (spec-37 T2),
     * the message reports the honest breakdown.
     */
    fun downloadOutcome(result: OfflineDownloads.OfflineDownloadResult): String {
        val totalOk = result.downloadedChapters + result.sharedChapters + result.reusedChapters
        return when {
            result.totalChapters == 0 ->
                "Не вдалося знайти аудіо для завантаження. Перевірте з'єднання."
            totalOk == 0 ->
                // #741: no 4read browser-recovery advice any more.
                "Не вдалося завантажити книгу. Спробуйте пізніше."
            totalOk < result.totalChapters -> {
                val base = "Завантажено $totalOk з ${result.totalChapters} глав"
                val extras = mutableListOf<String>()
                if (result.sharedChapters > 0) extras.add("${result.sharedChapters} спільно")
                if (result.reusedChapters > 0) extras.add("${result.reusedChapters} повторно")
                if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
            }
            else -> {
                val base = "Книгу завантажено для офлайн-прослуховування"
                val extras = mutableListOf<String>()
                if (result.sharedChapters > 0) extras.add("${result.sharedChapters} спільно")
                if (result.reusedChapters > 0) extras.add("${result.reusedChapters} повторно")
                if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
            }
        }
    }

    /** The snackbar text when the download attempt itself threw. */
    fun downloadFailure(): String = "Не вдалося завантажити книгу"

    /**
     * The snackbar text after a confirmed folder-import preview applies.
     * Imported books report their file count; a fully-duplicate folder says so
     * instead of pretending new books arrived; a no-op plan falls back to the
     * neutral completion line.
     */
    fun importOutcome(result: LocalImportResult): String {
        if (result.booksImported == 0 && result.filesImported == 0 && result.duplicateFiles > 0 && result.skippedFiles == 0) {
            return "Всі файли вже в бібліотеці (${result.duplicateFiles} дублікатів пропущено)"
        }
        return buildString {
            append(when {
                result.booksImported > 0 -> "Імпортовано ${result.booksImported} книг (${result.filesImported} файлів)"
                result.filesImported > 0 -> "Додано ${result.filesImported} файлів до наявних книг"
                else -> "Імпорт завершено"
            })
            if (result.duplicateFiles > 0) append(" · ${result.duplicateFiles} дублікатів пропущено")
            if (result.skippedFiles > 0) append(" · ${result.skippedFiles} файлів не вдалося додати")
        }
    }

    /**
     * #1230 — the outcome of «відновити доступ». Identity is proven by exact
     * file bytes only; when no picked file matches, the message says nothing
     * changed and points at the explicit narration choice instead of implying
     * the book was restored.
     */
    fun localAccessRecovery(report: com.slukhayka.audiobooks.data.imports.LocalAccessRecoveryReport): String {
        if (!report.identityProven) {
            return "Ці файли не належать книзі за вмістом — нічого не змінено. Іншу начитку виберіть явно."
        }
        return buildString {
            append("Доступ відновлено: ${report.relinkedTracks} файлів цієї книги")
            if (report.unprovenFiles > 0) append(" · ${report.unprovenFiles} чужих файлів не додано")
            if (report.skippedFiles > 0) append(" · ${report.skippedFiles} не вдалося прочитати")
        }
    }

    /**
     * #1185 T4 — the one-time copy-migration report: how much disk the pass
     * freed and how many copies it had to keep. The pass cannot know WHY a copy
     * was kept (a dead grant, unmatched bytes, a single-file import, a
     * rolled-back transaction), so the message never guesses the cause.
     */
    fun localCopyMigration(report: LocalCopyMigration.Report): String = buildString {
        append("Звільнено ${formatFreedBytes(report.freedBytes)}: копії локальних книг переїхали на оригінальні теки")
        if (report.keptTracks > 0) append(" · ${report.keptTracks} копій лишено")
    }

    private fun formatFreedBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 МБ"
        bytes >= 1024L * 1024L -> java.lang.String.format(
            java.util.Locale.UK,
            "%.1f МБ",
            bytes / 1024.0 / 1024.0
        )
        else -> "${(bytes / 1024.0).toInt()} КБ"
    }

    /** #1230 — the recovery door itself failed (no report was produced). */
    fun localAccessRecoveryFailed(): String =
        "Не вдалося відновити доступ — виберіть файл або теку заново."

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
                // #615 — a re-scan never reorders a stored book: new chapters
                // go after the existing ones even if their filenames sort
                // earlier, and the listener is told exactly that.
                if (totals.newChapters > 0 && totals.newBooks == 0) append(" — додано в кінець")
            }
            totals.structuralChangeRejected ->
                append(": структурну зміну відхилено — підтвердьте новий перелік розділів")
            totals.skippedFiles > 0 -> append(": перевірено частково")
            else -> append(" — змін не знайдено")
        }
        if (totals.structuralChangeRejected && (totals.newChapters > 0 || totals.newBooks > 0)) {
            append(" · структурну зміну відхилено — підтвердьте новий перелік розділів")
        }
        if (totals.missingFiles > 0) append(" · ${totals.missingFiles} файлів зникло")
        if (totals.movedFiles > 0) append(" · ${totals.movedFiles} перейменовано")
        if (totals.duplicateFiles > 0) append(" · ${totals.duplicateFiles} дублікатів пропущено")
        if (totals.skippedFiles > 0) append(" · ${totals.skippedFiles} файлів не вдалося перевірити")
    }
}
