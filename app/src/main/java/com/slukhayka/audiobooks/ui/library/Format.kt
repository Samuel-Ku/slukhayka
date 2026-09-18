package com.slukhayka.audiobooks.ui.library

import java.util.Locale

/**
 * Spec-27 (#184) — the Ukrainian plural helper, applied wherever a count
 * renders with a noun («1 книга», «2 книги», «5 книг»). Pure JVM so the
 * rules are unit-testable without Android.
 *
 * Ukrainian plural rules (nominative/accusative forms passed by the caller):
 *  1 → one; 2–4 → few; 5–20 → many; 21 → one again; 11–14 → many even
 * though they end in 1–4.
 *
 * @param one  the singular form («книга» / «завантажену книгу»)
 * @param few  the 2–4 form («книги» / «завантажені книги»)
 * @param many the 5+ form («книг» / «завантажених книг»)
 */
fun ukPlural(n: Int, one: String, few: String, many: String): String {
    val n100 = n % 100
    val n10 = n % 10
    return when {
        n100 in 11..14 -> many
        n10 == 1 -> one
        n10 in 2..4 -> few
        else -> many
    }
}

/**
 * «2,3 ГБ» / «350 МБ» — a human byte size with the Ukrainian decimal comma.
 * Values under 1 GB render whole MB; anything from 1 GB up renders one
 * decimal place in GB (the size the clear-cache confirm dialog quotes).
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 МБ"
    val gb = bytes / (1024.0 * 1024 * 1024)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.1f ГБ", gb).replace('.', ',')
    } else {
        "${bytes / (1024 * 1024)} МБ"
    }
}

/**
 * #899 — the download manager's memory summary: what the downloads occupy on
 * this device and what is still free. The occupied half is the same number
 * the destructive delete quotes ([formatBytes] of the audio cache); the free
 * half is the volume's own available space. Pure so the manager's «зайнято /
 * вільно» line is testable without a screen.
 */
fun downloadMemorySummaryText(occupiedBytes: Long, freeBytes: Long, offlineBookCount: Int): String {
    val books = ukPlural(offlineBookCount, "аудіокнига", "аудіокниги", "аудіокниг")
    return "${formatBytes(occupiedBytes)} зайнято · ${formatBytes(freeBytes)} вільно · " +
        "$offlineBookCount $books офлайн"
}

/**
 * #899 — one queue row's detail line: how big the copy on disk is and how far
 * the download got. A book whose source exposes no tracks shows only its size
 * («розділів не знайдено» would be a guess about the source, not a fact the
 * queue holds). Genitive after «з»: 1 → «розділу», else «розділів».
 */
fun downloadQueueDetailText(downloadedChapters: Int, totalChapters: Int, bytesOnDisk: Long): String {
    val size = formatBytes(bytesOnDisk)
    if (totalChapters <= 0) return size
    val chapters = if (totalChapters == 1) "розділу" else "розділів"
    return "$size · $downloadedChapters з $totalChapters $chapters"
}

/**
 * #899 — the exact scope of «Прибрати завершені»: the action deletes files,
 * so the dialog quotes how many finished downloads it removes.
 */
fun removeCompletedConfirmText(bookCount: Int): String {
    val downloads = ukPlural(
        bookCount,
        one = "завершене завантаження",
        few = "завершені завантаження",
        many = "завершених завантажень"
    )
    return "Прибрати $bookCount $downloads? Завантажені файли буде видалено з пристрою."
}

/**
 * Spec-27 (#184) BUG-001 — the exact-scope copy of the clear-cache confirm
 * dialog: how many downloaded books and how much space the action removes,
 * and what survives. Pure so the acceptance criterion («діалог з точною
 * цифрою обсягу») is testable without a screen.
 */
fun clearCacheConfirmText(bookCount: Int, bytes: Long): String {
    val books = ukPlural(
        bookCount,
        one = "завантажену книгу",
        few = "завантажені книги",
        many = "завантажених книг"
    )
    return "Видалити $bookCount $books, ${formatBytes(bytes)}? " +
        "Книги залишаться в медіатеці — доведеться завантажити знову."
}
