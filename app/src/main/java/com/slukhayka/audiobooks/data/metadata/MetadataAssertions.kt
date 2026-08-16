package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.source.SourceChapter

/**
 * ADR-0004 — the ONE place Metadata Assertions from sources are applied to
 * library rows. Pure (no DAO, no I/O): claim normalization, the field
 * precedence delta against an existing row, and materialization. The
 * persistent application sites — explicit import, catalog upsert,
 * on-demand chapter fetch, detail refresh — are thin callers of these rules;
 * none re-derives blank / never-clobber / series / cover behaviour. Per-door
 * insert defaults (id schemes, placeholder author/narrator, description
 * templates) stay at the doors.
 *
 * ADR-0007 — Editions own Chapters, Sources own tracks: this module
 * materializes BOTH lists. The logical chapter list belongs to the Edition
 * (first Source only); the physical tracks belong to each Source. Input is
 * re-keyed from bookId to editionId/sourceId. Chapter → track is 1:1 by
 * index today (documented in ADR-0007); per-source chapter topology is
 * future work.
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

    /**
     * Spec-24 T1 — the curated Ukrainian SEO title suffixes stripped from the
     * END of any claimed title. Some sources append marketing phrases
     * («аудіокнига слухати онлайн», «слухати онлайн»…) to every title; they
     * render everywhere — catalog cards, book page, player — so they are
     * scrubbed at the write path (the one metadata-assertions seam, ADR-0004)
     * and by the one-time startup pass ([StoredTitleScrub]). Longest phrase
     * first so «Книга - аудіокнига слухати онлайн» cuts the whole suffix,
     * not just «слухати онлайн».
     */
    val SEO_TITLE_PHRASES: List<String> = listOf(
        "аудіокнига слухати онлайн",
        "слухати онлайн безкоштовно",
        "аудіокнига українською",
        "аудіокнига онлайн",
        "слухати онлайн"
    ).sortedByDescending { it.length }

    /**
     * The separators an SEO suffix can sit behind: ` - `, ` — `, ` ( `, `, `,
     * `|`. Stripped (with surrounding whitespace) once a phrase has been cut,
     * so the remainder of a scrubbed title is clean.
     */
    private val TRAILING_SEPARATOR = Regex("""(?:\s*-\s*|\s*[—–]\s*|\s*\(\s*|,\s*|\s*\|\s*)+$""")

    /**
     * Strips the curated [SEO_TITLE_PHRASES] from the END of a claimed title,
     * case-insensitive and across separators (a phrase in parentheses ends
     * with the closing `)`, which is cut with it); trims. If nothing is left
     * after the cut the ORIGINAL title is kept — the scrub never produces a
     * blank title. Idempotent: a second application matches nothing.
     */
    fun normalizeTitle(claimed: String?): String {
        val original = claimed?.trim().orEmpty()
        var title = original
        var changed = true
        while (changed) {
            changed = false
            for (phrase in SEO_TITLE_PHRASES) {
                // The phrase, tolerating a closing paren after it (the ` (`
                // separator case: «Пасажир (аудіокнига онлайн)») — matched
                // with the source's own casing so the cut length is exact.
                val match = Regex(
                    Regex.escape(phrase) + """\s*\)?\s*$""",
                    RegexOption.IGNORE_CASE
                ).find(title)
                if (match != null) {
                    title = TRAILING_SEPARATOR.replace(title.dropLast(match.value.length), "").trim()
                    changed = true
                    break
                }
            }
        }
        return title.ifBlank { original }
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
    // Materialization (ADR-0007: Edition chapters + Source tracks)
    // ---------------------------------------------------------------------

    /** Both materialized lists of one source import. */
    data class MaterializedChapters(
        val chapters: List<ChapterEntity>,
        val tracks: List<SourceTrackEntity>
    )

    /**
     * ONE chapter id format (the dash format `<bookId>_ch_<n>`) and ONE title
     * fallback for ALL new books — existing rows stay unmigrated. Duration
     * convention: the claim's real duration survives normalization, anything
     * unknown (blank / 0 / legacy sentinel) becomes 0 (unknown until played).
     *
     * The logical chapter list belongs to the Edition ([editionId], first
     * Source only); the physical tracks belong to the importing [sourceId].
     */
    fun materializeChaptersAndTracks(
        editionId: String,
        sourceId: String,
        bookId: String,
        bookTitle: String,
        chapters: List<SourceChapter>
    ): MaterializedChapters {
        val chapterList = chapters.mapIndexed { index, chapter ->
            ChapterEntity(
                id = chapterId(bookId, index),
                bookId = bookId,
                editionId = editionId,
                chapterIndex = index,
                title = chapterTitle(chapter.title, index, bookTitle),
                durationSeconds = normalizeDurationSeconds(chapter.durationSeconds) ?: 0L
            )
        }
        return MaterializedChapters(chapters = chapterList, tracks = materializeTracks(sourceId, chapters))
    }

    /**
     * The physical tracks of ONE Source (ADR-0007): one row per chapter of
     * the source that imported it, paired 1:1 by index with the Edition's
     * logical chapter list. A track carries the concrete URL only — download
     * state and local copies land on the track rows later.
     */
    fun materializeTracks(sourceId: String, chapters: List<SourceChapter>): List<SourceTrackEntity> =
        chapters.mapIndexed { index, chapter ->
            SourceTrackEntity(
                id = trackId(sourceId, index),
                sourceId = sourceId,
                trackIndex = index,
                url = chapter.streamUrl
            )
        }

    /** The dash id format — the single format for all new books. */
    fun chapterId(bookId: String, index: Int): String = "${bookId}_ch_${index + 1}"

    /** The track id format — the source-scoped analogue of the chapter id. */
    fun trackId(sourceId: String, index: Int): String = "${sourceId}_tr_${index + 1}"

    /** The single title fallback for all new books. */
    fun chapterTitle(claimed: String, index: Int, bookTitle: String): String =
        claimed.trim().ifEmpty { "Глава ${index + 1} ($bookTitle)" }
}
