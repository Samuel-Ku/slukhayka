package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ADR-0009 — the metadata row of the split book row. `audiobooks` is ONE
 * concept now: the per-row metadata of the user's copy (title/author/narrator
 * mirror the Work, cover, genre, durations, local-file tree). The two other
 * concepts the row used to fuse left in the v15 contract step:
 *  - the **Work** (mergeKey, series, workId) lives in `works`;
 *  - the **Library Entry** (isFavorite, createdAt, downloadProgress) lives in
 *    `library_entries`;
 *  - the per-book **preferred speed** lives on the Listening State row
 *    (`playback_progress`, keyed by the Edition).
 *
 * The moved fields stay on the Kotlin class as `@Ignore` READ-ONLY
 * projections: every DAO read joins `works` / `library_entries` /
 * `playback_progress` and fills them, so the UI (LibraryModel, ListenComposer,
 * screens, the player) keeps reading the same shaped row while the persisted
 * columns are gone. Writes never touch them here — they go to the owning
 * tables. `narrator` mirrors the EDITION's narrator (ADR-0010): the Work has
 * no narrator — the row is the user's copy of one rendition, and the
 * narrator rides on the audiobooks/editions pair, never on `works`.
 */
@Entity(tableName = "audiobooks")
data class AudiobookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val coverDrawableRes: Int,
    val coverImageUrl: String? = null,
    val genre: String,
    val sourceUrl: String,
    val isDownloaded: Boolean = false,
    val totalDurationSeconds: Long = 0L,
    val totalChapters: Int = 0,
    val rating: Float = 4.9f,
    // SAF tree URI of the local folder this book was imported from (wayfinder
    // #48): kept so a future rescan can re-read chapter metadata without
    // asking the user to pick the folder again. Null for streamed 4read books
    // and single-file imports.
    val sourceTreeUri: String? = null
) {
    // --- ADR-0009 projections (columns left in the v15 contract step) -----
    // The audiobooks ROW no longer carries these columns; every DAO read of
    // this entity joins the owning tables (`works`, `library_entries`,
    // `playback_progress`) and fills these @Ignore `var` projections, so the
    // UI keeps reading one shaped row. They are never persisted here — Room
    // needs them settable (var) to hydrate them from the join, and they are
    // excluded from data-class equality/copy by design (they are projections,
    // not identity).

    /** 4read series (cycle) metadata (spec-9 T1) — read from `works`. */
    @Ignore var seriesTitle: String? = null
    @Ignore var seriesUrl: String? = null
    @Ignore var seriesIndex: Int? = null
    /** Per-book playback speed (wayfinder #26) — read from the Listening
     *  State row (`playback_progress`); null means "use the global default". */
    @Ignore var preferredSpeed: Float? = null
    /** When the entry entered the library (wayfinder #39) — read from
     *  `library_entries`; drives the "recently added" sort. */
    @Ignore var createdAt: Long = System.currentTimeMillis()
    /** The Library Entry concerns — read from `library_entries`. */
    @Ignore var isFavorite: Boolean = false
    @Ignore var downloadProgress: Float = 0f
    /** The Work identity (mergeKey, workId) — read from `works` via
     *  `library_entries.workId`. */
    @Ignore var mergeKey: String = ""
    @Ignore var workId: String? = null
}

/**
 * ADR-0009 — one Library Entry row per `audiobooks` row: the user-copy
 * concerns of the split book row (isFavorite, createdAt, downloadProgress).
 * The Entry anchors the library row to its Work ([workId] = `works.id` for
 * mergeable identities; the book's own id for blank-key/local books that have
 * no Works row), so every read can join audiobooks → library_entries → works.
 */
@Entity(tableName = "library_entries", indices = [Index("workId")])
data class LibraryEntryEntity(
    // = audiobooks.id — one entry per library row.
    @PrimaryKey val id: String,
    // The Work id this entry belongs to: `works.id` when the book has a
    // mergeable identity, else the book's own id (no works row exists).
    val workId: String,
    val isFavorite: Boolean = false,
    // When the entry entered the library (wayfinder #39): drives the
    // "recently added" sort. Migration 14->15 backfills existing rows with
    // the audiobooks.createdAt they carried.
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    val downloadProgress: Float = 0f
)

@Entity(tableName = "listening_stats")
data class ListeningStatEntity(
    @PrimaryKey val dateIso: String,
    val listenedSeconds: Long = 0L
)

/**
 * One ordered logical subdivision of an Edition (ADR-0007). Positions and
 * bookmarks anchor to [editionId] + [chapterIndex], independent of how any
 * Source divides its files. The PHYSICAL playback data (stream URL, local
 * copy, content hash) moved out of this row to [SourceTrackEntity] — a
 * chapter row never changes on download (the track rows do).
 *
 * [bookId] is kept during the expand phase (the bookId columns contract in a
 * later ticket); new code reads through [editionId].
 */
@Entity(tableName = "chapters", indices = [Index("bookId"), Index("editionId")])
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val durationSeconds: Long,
    // The domain Edition this logical chapter belongs to (ADR-0007).
    val editionId: String? = null
)

/**
 * One physical playback track of a Source (ADR-0007). A Source owns its
 * tracks: the concrete stream URLs / local copies through which one Edition
 * may be played. Chapter → track is 1:1 by [trackIndex] today (all live
 * sources); per-source chapter topology is future work (documented in
 * ADR-0007).
 */
@Entity(tableName = "source_tracks", indices = [Index("sourceId")])
data class SourceTrackEntity(
    // Deterministic per (source, index): `$sourceId_tr_${index+1}` — the
    // chapter-row analogue of the `_ch_` chapter ids.
    @PrimaryKey val id: String,
    // The owning [SourceEntity.id] — the primary source row of the Edition.
    val sourceId: String,
    val trackIndex: Int,
    // The concrete playable URL (or the copied local file path for local
    // sources, where it doubles as both url and localFilePath).
    val url: String,
    val localFilePath: String? = null,
    // SHA-256 of the copied local file (wayfinder #48): lets re-imports of
    // the same file be detected and skipped without duplicating storage.
    val contentHash: String? = null,
    val isDownloaded: Boolean = false
)

/**
 * One playable source of an Edition (ADR-0007). The row is re-parented from
 * `bookId` to `editionId` (the bookId column is kept during the expand
 * phase); ids are recomputed deterministically as `$type-$editionId`.
 * `streamOnly` gates the download action per the T1 verdicts; local imports
 * carry type "local" with a blank url.
 */
@Entity(tableName = "sources", indices = [Index("bookId"), Index("editionId")])
data class SourceEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    // The domain Edition this source can play (ADR-0007). Deterministic
    // backfill: one edition per book, so every source of a book shares it.
    val editionId: String? = null,
    val type: String,
    val url: String,
    val streamOnly: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    // wayfinder #42: the folder-scan baseline of a local source — a hash over
    // its tracks' (name, contentHash) pairs. Null until the folder has been
    // scanned once; a re-scan diffs the live tree against this fingerprint.
    val lastScanFingerprint: String? = null
)

/**
 * A listener bookmark anchored to one Edition (ADR-0007). [bookId] is kept
 * during the expand phase; [editionId] is the key new code reads and writes.
 * The chapterIndex/title anchors are unchanged — the Edition's logical
 * chapter list is the anchor, not any source's track numbering.
 */
@Entity(tableName = "bookmarks", indices = [Index("bookId"), Index("editionId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    // The domain Edition this bookmark anchors to (ADR-0007).
    val editionId: String? = null,
    val chapterIndex: Int,
    val chapterTitle: String,
    val timestampSeconds: Long,
    val note: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Listening State of one Edition (ADR-0007). Re-keyed from (bookId,
 * sourceKey) to [editionId]: progress belongs to the rendition, not to the
 * source that happened to play it — switching sources mid-book keeps the
 * position. [bookId] is kept during the expand phase so legacy book-scoped
 * reads still resolve.
 */
@Entity(tableName = "playback_progress", indices = [Index("bookId")])
data class PlaybackProgressEntity(
    // The domain Edition id (ADR-0007) — one row per rendition.
    @PrimaryKey val editionId: String,
    val bookId: String,
    val currentChapterIndex: Int = 0,
    val currentPositionSeconds: Long = 0L,
    val lastListenedAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    // Wall-clock epoch of the last pause (wayfinder #25): drives the smart
    // rewind on resume — the longer the pause, the further back playback
    // rewinds. Null once the rewind has been applied.
    val lastPausedAtEpochMs: Long? = null,
    // Per-book playback speed (wayfinder #26, ADR-0009): the Listening State
    // row carries the preference (CONTEXT.md places playback preferences in
    // Listening State, keyed by Edition). Null means "use the global default"
    // from PlaybackSettings. Migration 14->15 backfills it from the
    // audiobooks.preferredSpeed it used to live on.
    val preferredSpeed: Float? = null
)

/**
 * One discrete listening transition (spec-16, wayfinder #53). The
 * [PlaybackProgressEntity] row stays the authoritative "where am I now"
 * read model; this log is history for undo, future sync and listening
 * intelligence. Append-only and capped (see PlaybackEventPolicy) — the state
 * row is never reconstructed from it.
 *
 * Only discrete transitions are recorded (RESUME, PAUSE, SEEK ≥ 5 min,
 * CHAPTER_CHANGE, TIMER_STOP, COMPLETED, RELISTEN, SOURCE_SWITCH); periodic
 * position ticks and sub-threshold seeks are noise and never land here.
 * `fromPositionSeconds` is set only for SEEK / SOURCE_SWITCH — the
 * pre-jump position an undo returns to. `deviceId` stays "" until sync
 * (wayfinder #56) lands.
 *
 * ADR-0007: the `sourceKey` column is HISTORY — it stays in the schema, but
 * new rows write "" (progress/bookmarks re-keyed to the Edition, the source
 * is no longer part of the listening identity).
 */
@Entity(
    tableName = "playback_events",
    indices = [Index("bookId"), Index("sourceKey"), Index("timestamp")]
)
data class PlaybackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    // ADR-0007: kept as history; new rows write "".
    val sourceKey: String = "",
    // Stable String kind — see PlaybackEventKind. Strings, not enum ordinals,
    // so a future sync does not depend on enum declaration order.
    val kind: String,
    val chapterIndex: Int = 0,
    val positionSeconds: Long = 0L,
    // The pre-transition position, only for SEEK / SOURCE_SWITCH (undo).
    val fromPositionSeconds: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = ""
)

/** Stable String kinds of [PlaybackEventEntity] (spec-16). */
object PlaybackEventKind {
    const val RESUME = "RESUME"
    const val PAUSE = "PAUSE"
    const val SEEK = "SEEK"
    const val CHAPTER_CHANGE = "CHAPTER_CHANGE"
    const val TIMER_STOP = "TIMER_STOP"
    const val COMPLETED = "COMPLETED"
    const val RELISTEN = "RELISTEN"
    // ADR-0007: kept as a recorded KIND of the past (the player no longer
    // emits it — progress is Edition-keyed, so switching sources is not a
    // listening-state transition).
    const val SOURCE_SWITCH = "SOURCE_SWITCH"
}

/**
 * Durable tombstone of a deleted library book (wayfinder #55 Q8, stage-2 S1).
 * The 4read catalogue re-lists deleted books on every sync, so without a
 * durable marker the next homepage/series sync would resurrect a book the
 * user removed. This table replaces the in-memory `deletedCatalogBookIds`
 * set — a delete survives restarts. A tombstone is removed only when the
 * user explicitly imports the book again (search, WebView, local import) —
 * never by a catalogue sync.
 *
 * ADR-0007: tombstone identity stays WORK-keyed — deleting a Work blocks
 * re-import of every Edition and Source of it.
 */
@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val bookId: String,
    val deletedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playback_failures", indices = [Index("bookId")])
data class PlaybackFailureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val bookId: String,
    val chapterIndex: Int,
    // Media3 error.errorCodeName (e.g. ERROR_CODE_IO_UNSPECIFIED), or our own
    // synthetic codes for non-ExoPlayer failures (PREPARE_TIMEOUT, UNKNOWN).
    val errorCodeName: String,
    // The stream URL or local file path being played when the failure hit.
    val streamUrl: String,
    // Player state mode snapshot at failure time (IDLE/BUFFERING/READY/ENDED).
    val audioEngineMode: String,
    // Diagnosability category (wayfinder #61 Q1, stage-2 S1): a stable
    // coarse bucket derived from the Media3 error code by a pure function
    // (START_FAILED / FILE_UNAVAILABLE / FILE_CORRUPT / SAF_LOST /
    // DURATION_MISMATCH / INTERRUPTED / SYNC_CONFLICT /
    // DOWNLOAD_INTERRUPTED / SOURCE_LOST). Null until the derived classifier
    // lands (S7); additive so the ledger keeps accepting pre-category rows.
    val category: String? = null
)

/** Stable String kinds of a [CorrectionEntity] (wayfinder #54 Q9, stage-2 S1). */
object CorrectionKind {
    const val MERGE = "MERGE"
    const val SPLIT = "SPLIT"
    const val NEVER_MATCH = "NEVER_MATCH"
    const val FIELD = "FIELD"
    // Spec-26 T9 (#183): the user's «wrong universe» complaint pins this
    // kind; its `value` carries the reported (cached) universe id, and the
    // re-resolution verdict either replaces it or clears the complaint.
    const val WRONG_UNIVERSE = "WRONG_UNIVERSE"
}

/** Origin of a [CorrectionEntity] (wayfinder #54 Q9) — user-made outranks derived. */
object CorrectionOrigin {
    const val USER_MADE = "USER_MADE"
    const val DERIVED = "DERIVED"
}

/**
 * Synced correction memory (wayfinder #54 Q9, stage-2 S1). The strongest
 * identity memory: a user's explicit merge / split / never-match / field
 * override outranks any machine evidence, survives re-imports, and syncs
 * with the account (LWW per #53/#56). NEVER_MATCH suppresses candidate
 * generation and auto-linking between the pair even on an exact key match.
 * Keyed per Work (`mergeKey`), one row per (kind, value) pair.
 */
@Entity(
    tableName = "corrections",
    indices = [Index("mergeKey"), Index("updatedAt")],
    primaryKeys = ["mergeKey", "kind", "value"]
)
data class CorrectionEntity(
    // The Work this correction pins (a MergeKey — the pinned identity, #54).
    val mergeKey: String,
    // CorrectionKind: MERGE / SPLIT / NEVER_MATCH / FIELD.
    val kind: String,
    // NEVER_MATCH: the other mergeKey of the forbidden pair; MERGE: the
    // target mergeKey; FIELD: a `field=value` edit. Empty where not needed.
    val value: String = "",
    // CorrectionOrigin: USER_MADE outranks DERIVED regardless of timestamp.
    val origin: String = CorrectionOrigin.USER_MADE,
    val updatedAt: Long = System.currentTimeMillis(),
    // Device/user that made the edit; "" until sync (wayfinder #56) lands.
    val updatedBy: String = ""
)

/**
 * A book series / cycle (wayfinder #57 Q1, stage-2 S1). First-class Work-level
 * entity: a series owns an ordered membership (`series_members`), the poster
 * parse of 4read keeps writing the card fields, and the import path upserts
 * the series + membership. `nextInSeries` generalizes over this table.
 *
 * Spec-25 (#171): the series also carries its UNIVERSE anchor — a named
 * world/cycle the series belongs to, with its order inside it. The universe
 * rows are written by the lazy series-universe resolution ([com.slukhayka.audiobooks.data.universe.SeriesUniverses]);
 * `universeId` / `positionInUniverse` stay null until resolution runs.
 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    // The catalogue series page (4read poster `poster__series` link); null
    // for local/user-created series.
    val url: String? = null,
    // Spec-25: the [UniverseEntity] this series belongs to; null = unseeded.
    val universeId: String? = null,
    // Spec-25: the series' ORDER inside its universe (1-based) — the order
    // yields the precedes/follows relations; null = unseeded.
    val positionInUniverse: Int? = null,
    // Spec-26 T7: the series' last publication year (Wikidata P577, captured
    // at resolution) — the age signal of the tiered refresh rule; null when
    // Wikidata had no P577 (or the series is curated).
    val publicationYear: Int? = null
)

/** Membership of a Work in a [SeriesEntity] at an ordered [position] (stage-2 S1). */
@Entity(
    tableName = "series_members",
    indices = [Index("seriesId")],
    primaryKeys = ["workId", "seriesId"]
)
data class SeriesMemberEntity(
    // The Work's id (mergeKey — the pinned identity, #54).
    val workId: String,
    val seriesId: String,
    // Authoritative ordering; edited via a FIELD correction (synced).
    val position: Int,
    // Spec-25: the epoch-millis time THIS book→series membership was resolved
    // (written by the lazy universe resolution). The Wikidata fallback
    // re-resolves once the cached membership is older than its TTL; null
    // (pre-TTL rows) counts as stale. Curated memberships re-persist on every
    // book open, so they stay fresh by construction.
    val resolvedAt: Long? = null
)

/**
 * Spec-25 (#171) — a named universe: a world/cycle containing ordered Series.
 * The order of the series inside a universe yields the precedes/follows
 * relations; the series rows carry their own [SeriesEntity.positionInUniverse].
 * Written by the lazy series-universe resolution from the curated asset; the
 * row exists only for universes the user has actually opened a book/series of.
 */
@Entity(tableName = "universes")
data class UniverseEntity(
    // Stable id of the curated universe asset (e.g. "first-law").
    @PrimaryKey val id: String,
    // The display name (e.g. «Перший закон»).
    val name: String
)

/**
 * Per-Edition playback settings (wayfinder #60 Q2, stage-2 S1). Keyed
 * (bookId, sourceKey) so the same Work on two sources keeps independent
 * preferences. Additive: nulls mean "use the global default" from
 * PlaybackSettings. The experimental toggles (volume boost, silence
 * skipping) ship OFF by policy — pauses are part of the narration.
 * (ADR-0007 does not re-key this table — nothing reads it yet; it follows
 * progress re-keying when it is wired.)
 */
@Entity(tableName = "edition_settings", primaryKeys = ["bookId", "sourceKey"])
data class EditionSettingsEntity(
    val bookId: String,
    // Same convention as PlaybackProgressEntity.sourceKey; "" = primary source.
    val sourceKey: String = "",
    // The seek-back amount offered by the rewind preset, in seconds.
    val rewindSeconds: Int? = null,
    // The sleep timer default for this edition, in seconds.
    val sleepTimerDefaultSeconds: Int? = null,
    // Experimental loudness features — OFF by policy until S6 enables them.
    val volumeBoostEnabled: Boolean = false,
    val silenceSkipEnabled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A persisted catalogue Work (spec-23 T1): one row per book identity, keyed
 * by the normalized title+author [MergeKey]. This is the browse layer —
 * distinct from [AudiobookEntity], which stays the listening/library row and
 * links to a Work only when the user adds or plays it. One card per Work,
 * however many sources carry it; the sources live in [WorkSourceEntity].
 *
 * ADR-0010 — the Work is bibliographic only: NO narrator column. The
 * narrator is an Edition (rendition) property ([EditionEntity.narrator], and
 * the audiobooks row that is the user's copy of that rendition). Two
 * narrations of the same text are ONE Works row with two Edition rows.
 */
@Entity(
    tableName = "works",
    indices = [Index("mergeKey")]
)
data class WorkEntity(
    // The merge key itself for mergeable rows (the pinned identity, #54/#55);
    // a stable per-source id for unmergeable rows (blank key, no identity to
    // merge on).
    @PrimaryKey val id: String,
    // Normalized title|author; '' for unmergeable rows — dedup by lookup on
    // the write path (merge-on-write), never a blank-key collision.
    val mergeKey: String,
    val title: String,
    val author: String,
    val seriesTitle: String? = null,
    // 4read series page URL (spec-9 T1, ADR-0009): moved here from the
    // audiobooks row in the v15 contract step — the membership signal the
    // next-in-series resolution reads from the Work.
    val seriesUrl: String? = null,
    val seriesIndex: Int? = null,
    val coverImageUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * A persisted catalogue **domain Edition** (ADR-0007): one row per rendition
 * of a Work — id deterministic (`hash(mergeKey|language)`, or
 * `hash(bookId|language)` when the mergeKey is blank), `workId` anchored to
 * the library row ([AudiobookEntity.id]). The Edition OWNS the logical
 * chapter list ([ChapterEntity.editionId]) and the listening state
 * ([PlaybackProgressEntity], [BookmarkEntity]); the physical sources that
 * can play it are [SourceEntity] rows, whose tracks are [SourceTrackEntity].
 *
 * The migration creates exactly one Edition per existing book — no concept
 * drift, since the mergeKey already includes the narrator.
 */
@Entity(tableName = "editions", indices = [Index("workId")])
data class EditionEntity(
    // Deterministic per (book identity + language), stable across processes
    // and re-imports — see [com.slukhayka.audiobooks.data.EditionId].
    @PrimaryKey val id: String,
    // The audiobooks row id this rendition belongs to.
    val workId: String,
    // Spoken language of the rendition; empty = unknown.
    val language: String = "",
    // Mirrored narrator of the rendition (the mergeKey already carries it).
    val narrator: String = "",
    val totalChapters: Int = 0,
    val totalDurationSeconds: Long = 0L
)

/**
 * A persisted catalogue Source carrier of a Work (spec-23 T1; renamed from
 * the misnamed `editions` table in ADR-0007). One row per source carrying a
 * [WorkEntity] — this is the glossary **Source** of the browse layer, NOT a
 * rendition: writing the same book from two sources attaches a second source
 * row to the same Work (the «N джерел» badge), never a duplicate. The
 * domain Edition (rendition) lives in [EditionEntity].
 */
@Entity(
    tableName = "work_sources",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workId"), Index("sourceId")]
)
data class WorkSourceEntity(
    // Deterministic per (work, source, url) so re-writing is idempotent:
    // `<workId>|<sourceId>|<url-hash>` — REPLACE no-ops on the same row.
    @PrimaryKey val id: String,
    val workId: String,
    val sourceId: String,
    val sourceUrl: String,
    val streamOnly: Boolean = false,
    val coverImageUrl: String? = null,
    val durationSeconds: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Spec-40 #281 — локальний м'ют автора відгуків: one row per reviewer the
 * listener muted («сховати всі відгуки цього автора»). Local-only by design —
 * the mute acts in THIS listener's feed and never claims to moderate anyone
 * else; hiding is reversible (unhide in settings). Keyed by [authorName]
 * exactly as reviews denormalize it at publication time.
 */
@Entity(tableName = "hidden_reviewers", primaryKeys = ["authorName"])
data class HiddenReviewerEntity(
    val authorName: String,
    // Epoch millis of the hide — informational (a settings list may show it);
    // a re-hide replaces the row, it never duplicates.
    val hiddenAt: Long
)

/**
 * #290 — only an explicit recommendation verdict is persisted. The row
 * contains stable Work/author keys and never duplicates an embedding,
 * listening history, title, description or other private content.
 */
@Entity(
    tableName = "recommendation_preferences",
    primaryKeys = ["kind", "targetKey"],
    indices = [Index("sourceWorkId")]
)
data class RecommendationPreferenceEntity(
    val kind: String,
    val targetKey: String,
    val sourceWorkId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val HIDE_WORK = "HIDE_WORK"
        const val REDUCE_SIMILAR = "REDUCE_SIMILAR"
        const val HIDE_AUTHOR = "HIDE_AUTHOR"
    }
}

/**
 * One row of the endless merged feed (spec-23 T4): a Work with the number of
 * Sources carrying it and its optional normalized local genre projection.
 * Source count remains resolution metadata but is not rendered on Огляд
 * (spec-42); genre comes from the indexed Work↔genre relation, never a
 * free-form `LIKE`. Row shape for a Room paging query, not a stored table.
 * ADR-0010: no narrator — the Work is bibliographic; the rendition narrator
 * lives on the Edition, never on the Work.
 */
data class WorkFeedRow(
    val workId: String,
    val mergeKey: String,
    val title: String,
    val author: String,
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    val coverImageUrl: String? = null,
    val addedAt: Long,
    // COUNT of `work_sources` rows for this Work — resolution metadata.
    val sourceCount: Int,
    // One display genre from the local facet dictionary; null when unknown.
    val genre: String? = null,
    // Spec-24 T1: the Work's listening total (the Edition owns it, ADR-0010)
    // — joined from the domain `editions` row of the linked library copy;
    // null/zero renders nothing on the card (unknown until known).
    val durationSeconds: Long? = null
)

/**
 * Spec-24 T1 — one (id, title) row of the one-time stored-title scrub: the
 * startup pass reads both `audiobooks` and `works` titles through this
 * projection, applies [com.slukhayka.audiobooks.data.metadata.MetadataAssertions.normalizeTitle]
 * in Kotlin, and rewrites only the rows that change. A row projection of a
 * Room query, not a stored table.
 */
data class TitleRow(
    val id: String,
    val title: String
)

/**
 * #264 — one (id, description) row of the stored-description scrub: the
 * startup pass reads the `audiobooks` descriptions through this projection,
 * applies [com.slukhayka.audiobooks.data.metadata.MetadataAssertions.normalizeDescription]
 * in Kotlin, and rewrites only the rows that change. A row projection of a
 * Room query, not a stored table.
 */
data class DescriptionRow(
    val id: String,
    val description: String
)
