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
