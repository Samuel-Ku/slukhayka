package com.example.data.rebrand

/**
 * spec-20 T3 (#124) — the pure string rules that make stored book rows
 * brand-neutral. Applied at write time so new inserts never carry branded
 * placeholders, and mirrored by the one-time startup SQL cleanup
 * ([com.example.data.db.AudiobookDao.scrubLegacyPlaceholders]) which applies
 * exactly these rules to legacy rows. Pure JVM, zero Android dependencies —
 * the rules are pinned by fixture tests.
 *
 * Internal identifiers (sourceId «4read», the «4read-slug» id scheme, URLs
 * as the book's own source address) are NOT scrubbed — they are entities,
 * not branding (spec-20 decision 4). Only display-facing fields are cleaned.
 */
object PlaceholderScrub {

    /** The brand token that marks a legacy placeholder. */
    const val BRAND = "4read"

    /** A field carrying the brand is a placeholder — blank it. */
    fun author(value: String): String = if (containsBrand(value)) "" else value

    fun narrator(value: String): String = if (containsBrand(value)) "" else value

    fun genre(value: String): String = if (containsBrand(value)) "" else value

    /**
     * Removes the branded description templates and the 4read.org URLs that
     * older rows carried as provenance, keeping whatever meaningful part is
     * left (e.g. «Джерело: <slug>.html»). Rule set mirrors the startup SQL.
     */
    fun description(value: String): String = value
        .replace("Аудіокнига з каталогу 4read.org. ", "")
        .replace("Аудиокнига с портала 4read.org. ", "")
        .replace("Аудіокнига з джерела 4read. ", "")
        .replace("Книга знайдена на порталі 4read.org за запитом \"", "")
        .replace("\". Джерело: ", ". Джерело: ")
        .replace(Regex("""https?://4read\.org/"""), "")
        .trim()

    fun containsBrand(value: String): Boolean = value.contains(BRAND, ignoreCase = true)
}