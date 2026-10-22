package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.SourceRegistry

/**
 * Author label for the UI. The repository seeds several placeholder authors
 * for books without a real one ("4read.org", "Аудиокнига 4read.org");
 * rendering them under every title makes screens repeat "4read" over and
 * over. Blank any of them here — the source badge / pill already tells the
 * user where the book came from. Safe to match on "4read": no real author
 * name contains it.
 */
val AudiobookEntity.displayAuthor: String
    get() = author.takeUnless { it.contains("4read", ignoreCase = true) }.orEmpty()

/**
 * Narrator label for the UI. Same scrub as [displayAuthor]: the repository
 * seeds fabricated narrators ("4read Voice Narrator") for books without a
 * real one — rendering them is noise, so blank them here. #40 renders the
 * narrator as a tappable person link when this is non-blank and as plain
 * text otherwise.
 */
val AudiobookEntity.displayNarrator: String
    get() = narrator.takeIf { isRealPersonName(it) }.orEmpty()

/**
 * #40 decision on person links: a person name is a real navigable name only
 * when it is present and not the fabricated fallback; the page otherwise
 * shows the line as plain text. Mirrors the scrub of [displayAuthor].
 *
 * #1382 — the placeholder is `<sourceId> narrator` (e.g. «audiobookmp3
 * narrator»), created by the cross-source search when a source claims no
 * narrator at all. It MUST stay in the data: it feeds the edition-id hash
 * (`hash(mergeKey|narrator|language)`, R1/#508), so dropping it would change
 * rendition identity. It must simply never be DISPLAYED as a person.
 *
 * The previous rule blanked ANY name ending with « narrator». That was a
 * substring guess about the data, not a fact about it: a real name that
 * happened to end that way vanished from the book page — and because the page
 * hides the whole narrator row (with its bookmark button) when the name is
 * blank, it vanished without a trace and without a failing test.
 *
 * The precise rule is [isPlaceholderNarrator]: the prefix before « narrator»
 * must be an id this build actually registers as a source. A real name is
 * never a registered source id, so it can no longer be swallowed.
 */
fun isRealPersonName(name: String): Boolean =
    name.isNotBlank() &&
        !name.contains("4read", ignoreCase = true) &&
        !isPlaceholderNarrator(name)

/**
 * #1382 — true only for the fabricated `<registered source id> narrator`.
 *
 * Deliberately stricter than "ends with « narrator»": a name is a placeholder
 * only when its prefix is a known source id, so an unknown/renamed source (or
 * a person) is never silently blanked. A source id that is not registered is
 * not invented here either — an unknown placeholder stays visible rather than
 * disappearing, which is the whole point of the ticket.
 */
fun isPlaceholderNarrator(name: String): Boolean {
    val suffix = " narrator"
    if (!name.endsWith(suffix, ignoreCase = true)) return false
    return name.dropLast(suffix.length) in SourceRegistry.ids()
}

/**
 * #40: the fresh person-books path mirrors the site's own URL shape —
 * `/xfsearch/<kind>/<name>/` — so a tap decodes into exactly the catalogue
 * page the web app links to. Pure JVM so the navigation state is testable
 * without Android.
 */
fun bookPersonPath(kind: String, name: String): String = "/xfsearch/$kind/$name/"
