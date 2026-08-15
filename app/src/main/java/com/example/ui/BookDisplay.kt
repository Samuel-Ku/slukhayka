package com.example.ui

import com.example.data.db.AudiobookEntity

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
 */
fun isRealPersonName(name: String): Boolean =
    name.isNotBlank() && !name.contains("4read", ignoreCase = true)

/**
 * #40: the fresh person-books path mirrors the site's own URL shape —
 * `/xfsearch/<kind>/<name>/` — so a tap decodes into exactly the catalogue
 * page the web app links to. Pure JVM so the navigation state is testable
 * without Android.
 */
fun bookPersonPath(kind: String, name: String): String = "/xfsearch/$kind/$name/"
