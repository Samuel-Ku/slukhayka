package com.example.data.metadata

import com.example.data.db.ChapterEntity
import com.example.data.source.SourceChapter

/**
 * ADR-0004 — the ONE place Metadata Assertions from sources are applied to
 * library rows. Pure (no DAO, no I/O): claim normalization, the field
 * precedence delta against an existing row, and chapter materialization. The
 * four persistent application sites — explicit import, catalog upsert,
 * on-demand chapter fetch, detail refresh — are thin callers of these rules;
 * none re-derives blank / never-clobber / series / cover behaviour. Per-door
 * insert defaults (id schemes, placeholder author/narrator, description
 * templates) stay at the doors.
 *
 * Domain terms (CONTEXT.md): a **Metadata Assertion** is a provenance-bearing
 * claim supplied by a source page; this module is the future landing spot for
 * **Metadata Override** precedence.
 */
object MetadataAssertions {

    /**
     * Legacy fabricated duration placeholder — a 4:00:00 (14400 s) seed with 5
     * fake chapters — treated as unknown everywhere, including catalog upsert.
     */
    const val LEGACY_SENTINEL_DURATION_SECONDS: Long = 14_400L

    // ---------------------------------------------------------------------
    // Claim normalization
    // ---------------------------------------------------------------------

    /**
     * A claimed duration is unknown when blank, non-positive, or the legacy
     * 14400 s sentinel — such claims never render as real, never clobber a
     * known value.
     */
    fun normalizeDurationSeconds(claimed: Long?): Long? =
        claimed?.takeIf { it > 0L && it != LEGACY_SENTINEL_DURATION_SECONDS }

    /**
     * A claimed text field (author, narrator) is absent when blank or a brand
     * placeholder. Brand-scrub (ADR-0004): legacy placeholder values seeded
     * under the old brand («4read.org», «Аудиокнига 4read.org», «4read Voice
     * Narrator») are treated as absent at WRITE time — safe to match on
     * "4read": no real author/narrator name contains it. Not applied to URLs
     * (cover / series links may legitimately live on a source's own domain).
     */
    fun normalizeClaimedText(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.contains("4read", ignoreCase = true)) return null
        return trimmed
    }

    // ---------------------------------------------------------------------
    // Book delta — field precedence against the existing row
    // ---------------------------------------------------------------------

    /**
     * Never clobber a known duration with an unknown claim: the claimed value
     * wins only when it is real (post-normalization); a null/blank/sentinel
     * claim keeps the existing value.
     */
    fun durationDelta(existingDurationSeconds: Long, claimed: Long?): Long =
        normalizeDurationSeconds(claimed) ?: existingDurationSeconds

    /** The series fields to write, or null when nothing should change. */
    data class SeriesDelta(
        val url: String,
        val title: String?,
        val index: Int?
    )

    /**
     * Series applies only when its URL changed: the incoming URL is the
     * membership signal — no claim (blank URL) or the same URL never
     * overrides the stored series. A blank-title claim with a changed URL
     * still updates the URL and clears nothing else (title/index may be
     * unknown on some pages).
     */
    fun seriesDelta(
        existingSeriesUrl: String?,
        claimedUrl: String?,
        claimedTitle: String?,
        claimedIndex: Int?
    ): SeriesDelta? {
        val url = claimedUrl?.trim().orEmpty()
        if (url.isEmpty() || url == existingSeriesUrl) return null
        return SeriesDelta(
            url = url,
            title = claimedTitle?.trim()?.ifEmpty { null },
            index = claimedIndex
        )
    }

    /**
     * Cover applies only when the claim is non-blank — never clears a stored
     * cover with an absent claim, and (unlike [normalizeClaimedText]) never
     * brand-scrubs: cover URLs may legitimately live on a source's own domain.
     */
    fun coverDelta(claimed: String?): String? =
        claimed?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------------
    // Chapter materialization
    // ---------------------------------------------------------------------

    /**
     * ONE chapter id format (the dash format `<bookId>_ch_<n>`) and ONE title
     * fallback for ALL new books — existing rows stay unmigrated. Duration
     * convention: the claim's real duration survives normalization, anything
     * unknown (blank / 0 / legacy sentinel) becomes 0 (unknown until played).
     */
    fun materializeChapters(
        bookId: String,
        bookTitle: String,
        chapters: List<SourceChapter>
    ): List<ChapterEntity> = chapters.mapIndexed { index, chapter ->
        ChapterEntity(
            id = chapterId(bookId, index),
            bookId = bookId,
            chapterIndex = index,
            title = chapterTitle(chapter.title, index, bookTitle),
            durationSeconds = normalizeDurationSeconds(chapter.durationSeconds) ?: 0L,
            streamUrl = chapter.streamUrl
        )
    }

    /** The dash id format — the single format for all new books. */
    fun chapterId(bookId: String, index: Int): String = "${bookId}_ch_${index + 1}"

    /** The single title fallback for all new books. */
    fun chapterTitle(claimed: String, index: Int, bookTitle: String): String =
        claimed.trim().ifEmpty { "Глава ${index + 1} ($bookTitle)" }
}
