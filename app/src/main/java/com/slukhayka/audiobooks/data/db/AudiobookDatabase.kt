package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.slukhayka.audiobooks.data.duration.DurationBuckets
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import com.slukhayka.audiobooks.data.facets.GenreIdentity
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.metadata.EditionDurationPolicy

@Database(
    entities = [
        AudiobookEntity::class,
        SourceEntity::class,
        ChapterEntity::class,
        SourceTrackEntity::class,
        BookmarkEntity::class,
        PlaybackProgressEntity::class,
        ListeningStatEntity::class,
        PlaybackFailureEntity::class,
        PlaybackEventEntity::class,
        TombstoneEntity::class,
        CorrectionEntity::class,
        SeriesEntity::class,
        SeriesMemberEntity::class,
        UniverseEntity::class,
        EditionSettingsEntity::class,
        WorkEntity::class,
        EditionEntity::class,
        WorkSourceEntity::class,
        LibraryEntryEntity::class,
        HiddenReviewerEntity::class,
        RecommendationPreferenceEntity::class,
        WorkFacetEntity::class,
        WorkFacetSeriesEntity::class,
        GenreFacetEntity::class,
        WorkGenreEntity::class,
        GenreAssertionStateEntity::class,
        GenreAssertionEntity::class,
        EditionFacetEntity::class,
        AuthorFacetEntity::class,
        AuthorAliasEntity::class
    ],
    version = 23,
    exportSchema = true
)
abstract class AudiobookDatabase : RoomDatabase() {
    abstract fun audiobookDao(): AudiobookDao

    companion object {
        @Volatile
        private var INSTANCE: AudiobookDatabase? = null

        fun getDatabase(context: Context): AudiobookDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AudiobookDatabase::class.java,
                    "read4_audiobook_database"
                )
                    // Schema v4: indices on every FK column queried via
                    // `WHERE bookId = :bookId` (audit CRITICAL PERF-004 --
                    // full table scans as the library grows).
                    // Schema v8 (wayfinder #47): a user's library must survive
                    // upgrades, so a schema change fails loudly at runtime
                    // instead of silently dropping the database.
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /** v3 -> v4: add indices matching Room's `index_<table>_<column>` convention. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_bookId ON chapters(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_bookId ON playback_progress(bookId)")
            }
        }

        /** v4 -> v5: add the 4read series (cycle) metadata columns (spec-9 T1). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN seriesTitle TEXT")
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN seriesUrl TEXT")
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN seriesIndex INTEGER")
            }
        }

        /**
         * v5 -> v6 (wayfinder #26 + #25): per-book playback speed on
         * audiobooks, and the last-pause timestamp that drives the smart
         * rewind on playback_progress. Both columns are nullable; both are
         * written by new code paths only. Internal (not private) so the JVM
         * test suite can verify the upgrade path against a real v5 database.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN preferredSpeed REAL")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN lastPausedAtEpochMs INTEGER")
            }
        }

        /**
         * v6 -> v7 (wayfinder #39): the "recently added" sort needs a
         * creation stamp on audiobooks. Existing rows get the migration-run
         * time so the library is never empty-handed; new imports stamp their
         * own insert time via the entity default. Internal (not private) so
         * the JVM test suite can verify the upgrade path.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE audiobooks SET createdAt = (strftime('%s','now') * 1000)")
            }
        }

        /**
         * v7 -> v8 (wayfinder #47/#48/#52 + spec-10 T2, merged in one step —
         * the parallel branches both picked v8). Additive changes only:
         * `audiobooks.sourceTreeUri` (persisted SAF grants, #48),
         * `audiobooks.mergeKey` + the `sources` table (multi-source catalog,
         * spec-10 T2), `chapters.contentHash` (SHA-256 import dedupe, #48),
         * the durable `playback_failures` ledger (#52), and the
         * `playback_progress` PK widened to (bookId, sourceKey) so listening
         * state is isolated per source (ADR-0001). Existing progress rows
         * migrate with sourceKey '' (the book's primary source). Internal
         * (not private) so the JVM test suite can verify the upgrade path
         * against a real v7 database.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // #48: SAF tree URI of the local folder, for future rescans.
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN sourceTreeUri TEXT")
                // spec-10 T2: Work-level dedup key; '' for pre-existing rows.
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN mergeKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audiobooks_mergeKey ON audiobooks(mergeKey)")
                // #48: SHA-256 content hash of copied local files.
                db.execSQL("ALTER TABLE chapters ADD COLUMN contentHash TEXT")
                // spec-10 T2: one row per playable source of a Work.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sources (" +
                        "id TEXT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "url TEXT NOT NULL, " +
                        "streamOnly INTEGER NOT NULL DEFAULT 0, " +
                        "addedAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_bookId ON sources(bookId)")
                // #52: durable playback-failure ledger.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_failures (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        bookId TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        errorCodeName TEXT NOT NULL,
                        streamUrl TEXT NOT NULL,
                        audioEngineMode TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_failures_bookId ON playback_failures(bookId)")
                // spec-10 T2: widen playback_progress PK to (bookId, sourceKey).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_progress_new (" +
                        "bookId TEXT NOT NULL, " +
                        "sourceKey TEXT NOT NULL, " +
                        "currentChapterIndex INTEGER NOT NULL, " +
                        "currentPositionSeconds INTEGER NOT NULL, " +
                        "lastListenedAt INTEGER NOT NULL, " +
                        "isCompleted INTEGER NOT NULL, " +
                        "lastPausedAtEpochMs INTEGER, " +
                        "PRIMARY KEY(bookId, sourceKey))"
                )
                db.execSQL(
                    "INSERT INTO playback_progress_new (bookId, sourceKey, currentChapterIndex, " +
                        "currentPositionSeconds, lastListenedAt, isCompleted, lastPausedAtEpochMs) " +
                        "SELECT bookId, '', currentChapterIndex, currentPositionSeconds, " +
                        "lastListenedAt, isCompleted, lastPausedAtEpochMs FROM playback_progress"
                )
                db.execSQL("DROP TABLE playback_progress")
                db.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_bookId ON playback_progress(bookId)")
            }
        }

        /**
         * v8 -> v9 (spec-16, wayfinder #53): the capped append-only playback
         * event log. Additive only — one new table, no existing table is
         * touched, so every v8 row survives untouched. Internal (not private)
         * so the JVM test suite can verify the upgrade path against a real v8
         * database.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "sourceKey TEXT NOT NULL DEFAULT '', " +
                        "kind TEXT NOT NULL, " +
                        "chapterIndex INTEGER NOT NULL DEFAULT 0, " +
                        "positionSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "fromPositionSeconds INTEGER, " +
                        "timestamp INTEGER NOT NULL, " +
                        "deviceId TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_events_bookId ON playback_events(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_events_sourceKey ON playback_events(sourceKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_events_timestamp ON playback_events(timestamp)")
            }
        }

        /**
         * v9 -> v10 (wayfinder #42): the re-scan fingerprint of a local
         * source. Additive only — one nullable column on `sources`, no
         * existing row is touched, so every v9 row survives untouched.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN lastScanFingerprint TEXT")
            }
        }

        /**
         * v10 -> v11 (wayfinder #55 Q8, stage-2 S1): the durable tombstone
         * table. Additive only — one new table, no existing table is touched,
         * so every v10 row survives untouched. The in-memory
         * `deletedCatalogBookIds` set becomes this table: a deleted book stays
         * deleted across restarts until the user explicitly imports it again.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tombstones (" +
                        "bookId TEXT NOT NULL, " +
                        "deletedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId))"
                )
            }
        }

        /**
         * v11 -> v12 (stage-2 S1, the unified-library identity & memory
         * bump): additive only — nullable identity columns and new tables,
         * no existing row is touched. Carries the #55 Q2 work/edition ids
         * (back-filled: `workId` = `mergeKey`, `chapters.editionId` =
         * `bookId` for legacy rows), the #54 Q9 `corrections` memory, the
         * #57 Q1 `series` + `series_members` membership, the #60 Q2
         * `edition_settings`, and the #61 Q1 `playback_failures.category`
         * column (null until the S7 classifier lands).
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // #55 Q2: Work id pinned to the merge key. Rows without a key
                // (pre-spec-10 imports) stay null until re-import.
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN workId TEXT")
                db.execSQL("UPDATE audiobooks SET workId = mergeKey WHERE workId IS NULL AND mergeKey != ''")
                // #55 Q2: Edition id on chapters — legacy rows had one edition
                // per book, so their edition is the book row itself.
                db.execSQL("ALTER TABLE chapters ADD COLUMN editionId TEXT")
                db.execSQL("UPDATE chapters SET editionId = bookId WHERE editionId IS NULL")
                // #61 Q1: diagnosability bucket; null until the classifier (S7).
                db.execSQL("ALTER TABLE playback_failures ADD COLUMN category TEXT")
                // #54 Q9: synced correction memory (merge/split/never-match/field).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS corrections (" +
                        "mergeKey TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "value TEXT NOT NULL DEFAULT '', " +
                        "origin TEXT NOT NULL DEFAULT 'USER_MADE', " +
                        "updatedAt INTEGER NOT NULL, " +
                        "updatedBy TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(mergeKey, kind, value))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_corrections_mergeKey ON corrections(mergeKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_corrections_updatedAt ON corrections(updatedAt)")
                // #57 Q1: series + ordered membership.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series (" +
                        "id TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "url TEXT, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_members (" +
                        "workId TEXT NOT NULL, " +
                        "seriesId TEXT NOT NULL, " +
                        "position INTEGER NOT NULL, " +
                        "PRIMARY KEY(workId, seriesId))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_series_members_seriesId ON series_members(seriesId)")
                // #60 Q2: per-edition playback settings, experimental toggles OFF.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS edition_settings (" +
                        "bookId TEXT NOT NULL, " +
                        "sourceKey TEXT NOT NULL DEFAULT '', " +
                        "rewindSeconds INTEGER, " +
                        "sleepTimerDefaultSeconds INTEGER, " +
                        "volumeBoostEnabled INTEGER NOT NULL DEFAULT 0, " +
                        "silenceSkipEnabled INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(bookId, sourceKey))"
                )
            }
        }

        /**
         * v12 -> v13 (spec-23 T1): the persisted browse catalogue. Additive
         * only — two new tables, no existing table is touched, so every v12
         * row (audiobooks, chapters, progress, downloads, tombstones) survives
         * untouched. The `works` table is one row per book identity keyed by
         * the normalized MergeKey (dedup happens on the write path —
         * merge-on-write — never a blank-key collision, so `mergeKey` is a
         * plain index, not UNIQUE); `editions` is one row per source carrying
         * a Work, with a deterministic id (`<workId>|<sourceId>|<url-hash>`)
         * so re-hydration is idempotent. Internal (not private) so the JVM
         * test suite can verify the upgrade path against a real v12 database.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS works (" +
                        "id TEXT NOT NULL, " +
                        "mergeKey TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "narrator TEXT NOT NULL DEFAULT '', " +
                        "seriesTitle TEXT, " +
                        "seriesIndex INTEGER, " +
                        "coverImageUrl TEXT, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_works_mergeKey ON works(mergeKey)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS editions (" +
                        "id TEXT NOT NULL, " +
                        "workId TEXT NOT NULL, " +
                        "sourceId TEXT NOT NULL, " +
                        "sourceUrl TEXT NOT NULL, " +
                        "streamOnly INTEGER NOT NULL DEFAULT 0, " +
                        "coverImageUrl TEXT, " +
                        "durationSeconds INTEGER, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(workId) REFERENCES works(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_workId ON editions(workId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_sourceId ON editions(sourceId)")
            }
        }

        /**
         * v13 -> v14 (ADR-0007 — Editions own Chapters, Sources own tracks):
         * the domain split of the library identity. The spec-23 catalogue
         * `editions` table is renamed to `work_sources` (its row is one
         * SOURCE carrying a Work, not a rendition); a new domain `editions`
         * table is created with exactly one row per existing book (id =
         * deterministic sha256 of (mergeKey|language), computed in Kotlin so
         * the migration agrees with every future write); `chapters` drop the
         * physical playback columns (they move to the new `source_tracks`
         * table, one row per (chapter, source of its book)) and re-point
         * `editionId` at the domain edition; `sources` re-parent to
         * `editionId` with ids recomputed as `$type-$editionId`; progress is
         * re-keyed to `editionId` (per book keep the latest row, drop the
         * sourceKey shadows); bookmarks gain `editionId`. The bookId columns
         * are KEPT during this expand step (they contract in a later
         * ticket) — minSdk 24 forbids DROP COLUMN on the chapters table, so
         * chapters are rebuilt instead. Internal (not private) so the JVM
         * test suite can verify the upgrade path against a real v13 database.
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ADR-0007: the spec-23 catalogue row is one SOURCE carrying a
                // Work, not a domain Edition — rename the table and recreate
                // its indices under the Room-expected names.
                db.execSQL("ALTER TABLE editions RENAME TO work_sources")
                db.execSQL("DROP INDEX IF EXISTS index_editions_workId")
                db.execSQL("DROP INDEX IF EXISTS index_editions_sourceId")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_workId ON work_sources(workId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_sourceId ON work_sources(sourceId)")

                // The domain editions table: one rendition per book. The id is
                // computed in Kotlin (deterministic sha256 of (mergeKey|
                // language)) so the migration and every future write agree.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS editions (" +
                        "id TEXT NOT NULL, " +
                        "workId TEXT NOT NULL, " +
                        "language TEXT NOT NULL DEFAULT '', " +
                        "narrator TEXT NOT NULL DEFAULT '', " +
                        "totalChapters INTEGER NOT NULL DEFAULT 0, " +
                        "totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_workId ON editions(workId)")
                db.query("SELECT id, mergeKey, narrator, totalChapters, totalDurationSeconds FROM audiobooks").use { cursor ->
                    while (cursor.moveToNext()) {
                        val bookId = cursor.getString(0)
                        val mergeKey = cursor.getString(1) ?: ""
                        val narrator = cursor.getString(2) ?: ""
                        val totalChapters = cursor.getInt(3)
                        val totalDuration = cursor.getLong(4)
                        db.execSQL(
                            "INSERT INTO editions (id, workId, language, narrator, totalChapters, " +
                                "totalDurationSeconds) VALUES (?, ?, '', ?, ?, ?)",
                            // The v13→v14-era formula (narrator inside the mergeKey):
                            // the v16 migration remaps these ids to the ADR-0010
                            // formula (narrator as a separate hash input).
                            arrayOf(com.slukhayka.audiobooks.data.EditionId.forBookLegacy(mergeKey, bookId), bookId, narrator, totalChapters, totalDuration)
                        )
                    }
                }

                // Sources re-parent to editionId; ids recomputed
                // deterministically as $type-$editionId (same-type collisions
                // get a numeric suffix, ordered by addedAt).
                db.execSQL("ALTER TABLE sources ADD COLUMN editionId TEXT")
                db.execSQL(
                    "UPDATE sources SET editionId = (SELECT e.id FROM editions e WHERE e.workId = sources.bookId LIMIT 1)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_editionId ON sources(editionId)")
                val idByOld = LinkedHashMap<String, String>()
                val usedIds = HashSet<String>()
                db.query("SELECT id, type, editionId FROM sources ORDER BY bookId ASC, addedAt ASC, id ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getString(0)
                        val type = cursor.getString(1) ?: "unknown"
                        val editionId = cursor.getString(2) ?: continue
                        var candidate = "$type-$editionId"
                        var n = 2
                        while (!usedIds.add(candidate)) {
                            candidate = "$type-$editionId-$n"
                            n++
                        }
                        idByOld[oldId] = candidate
                    }
                }
                for ((old, new) in idByOld) {
                    db.execSQL("UPDATE sources SET id = ? WHERE id = ?", arrayOf(new, old))
                }

                // source_tracks: one row per (chapter, source of the chapter's
                // book), carrying the physical playback data that leaves the
                // chapter rows below.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_tracks (" +
                        "id TEXT NOT NULL, " +
                        "sourceId TEXT NOT NULL, " +
                        "trackIndex INTEGER NOT NULL, " +
                        "url TEXT NOT NULL, " +
                        "localFilePath TEXT, " +
                        "contentHash TEXT, " +
                        "isDownloaded INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_tracks_sourceId ON source_tracks(sourceId)")
                db.query("SELECT id, bookId FROM sources ORDER BY bookId ASC, id ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        val sourceId = cursor.getString(0)
                        val bookId = cursor.getString(1)
                        db.query(
                            "SELECT chapterIndex, streamUrl, localFilePath, contentHash, isDownloaded " +
                                "FROM chapters WHERE bookId = ? ORDER BY chapterIndex ASC",
                            arrayOf(bookId)
                        ).use { chapters ->
                            while (chapters.moveToNext()) {
                                val index = chapters.getInt(0)
                                val url = chapters.getString(1)
                                val localPath = chapters.getString(2)
                                val hash = chapters.getString(3)
                                val isDownloaded = chapters.getInt(4)
                                db.execSQL(
                                    "INSERT INTO source_tracks (id, sourceId, trackIndex, url, " +
                                        "localFilePath, contentHash, isDownloaded) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                    arrayOf("${sourceId}_tr_${index + 1}", sourceId, index, url, localPath, hash, isDownloaded)
                                )
                            }
                        }
                    }
                }

                // Chapters: drop the physical playback columns (they moved to
                // source_tracks) and re-point editionId at the domain edition.
                // Rebuilt (not DROP COLUMN) for minSdk 24 compatibility.
                db.execSQL(
                    "CREATE TABLE chapters_new (" +
                        "id TEXT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "chapterIndex INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "durationSeconds INTEGER NOT NULL, " +
                        "editionId TEXT, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL(
                    "INSERT INTO chapters_new (id, bookId, chapterIndex, title, durationSeconds, editionId) " +
                        "SELECT c.id, c.bookId, c.chapterIndex, c.title, c.durationSeconds, e.id " +
                        "FROM chapters c LEFT JOIN editions e ON e.workId = c.bookId"
                )
                db.execSQL("DROP TABLE chapters")
                db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_bookId ON chapters(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_editionId ON chapters(editionId)")

                // playback_progress re-keys to editionId: per book keep the row
                // with the latest lastListenedAt (rowid tiebreak), drop the
                // other sourceKey shadows. lastPausedAtEpochMs migrates with
                // it (Smart Rewind rides along).
                db.execSQL(
                    "CREATE TABLE playback_progress_new (" +
                        "editionId TEXT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "currentChapterIndex INTEGER NOT NULL, " +
                        "currentPositionSeconds INTEGER NOT NULL, " +
                        "lastListenedAt INTEGER NOT NULL, " +
                        "isCompleted INTEGER NOT NULL, " +
                        "lastPausedAtEpochMs INTEGER, " +
                        "PRIMARY KEY(editionId))"
                )
                db.execSQL(
                    "INSERT INTO playback_progress_new (editionId, bookId, currentChapterIndex, " +
                        "currentPositionSeconds, lastListenedAt, isCompleted, lastPausedAtEpochMs) " +
                        "SELECT e.id, p.bookId, p.currentChapterIndex, p.currentPositionSeconds, " +
                        "p.lastListenedAt, p.isCompleted, p.lastPausedAtEpochMs " +
                        "FROM playback_progress p JOIN editions e ON e.workId = p.bookId " +
                        "WHERE NOT EXISTS (" +
                        "  SELECT 1 FROM playback_progress p2 " +
                        "  WHERE p2.bookId = p.bookId AND (p2.lastListenedAt > p.lastListenedAt " +
                        "    OR (p2.lastListenedAt = p.lastListenedAt AND p2.rowid > p.rowid)))"
                )
                db.execSQL("DROP TABLE playback_progress")
                db.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_bookId ON playback_progress(bookId)")

                // Bookmarks re-key to editionId (bookId kept during expand).
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN editionId TEXT")
                db.execSQL(
                    "UPDATE bookmarks SET editionId = (SELECT e.id FROM editions e WHERE e.workId = bookmarks.bookId LIMIT 1)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_editionId ON bookmarks(editionId)")
            }
        }

        /**
         * v14 -> v15 (ADR-0009 — the book row splits into Works and Library
         * Entries): expand–contract of `audiobooks`, which used to fuse three
         * concepts in one row. EXPAND: a new `library_entries` table (one row
         * per audiobooks row; isFavorite / createdAt / downloadProgress),
         * `works.seriesUrl` (the series membership signal that lived on
         * audiobooks), and `playback_progress.preferredSpeed` (the per-book
         * speed moves to the Listening State row) — all back-filled from the
         * audiobooks columns they replace, and one Works row per mergeable
         * library book (upserted merge-on-write by the pinned key). CONTRACT:
         * the audiobooks row is rebuilt without the fused columns (mergeKey /
         * series* / workId → Works, isFavorite / createdAt / downloadProgress
         * → Library Entries, preferredSpeed → Listening State); it keeps only
         * the per-row metadata. Blank-key rows (local books, catalogue seeds
         * without a merge key) anchor their entry to the book id itself; a
         * crawled book whose Works row already exists is linked by the same
         * normalized title|author key the catalogue write path uses.
         * Internal (not private) so the JVM test suite can verify the upgrade
         * path against a real v14 database.
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // EXPAND 1 — library_entries: one row per audiobooks row,
                // workId = the pinned Work id for mergeable books, the book's
                // own id otherwise (the blank-key works convention).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_entries (" +
                        "id TEXT NOT NULL, " +
                        "workId TEXT NOT NULL, " +
                        "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL DEFAULT 0, " +
                        "downloadProgress REAL NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_entries_workId ON library_entries(workId)")
                db.execSQL(
                    "INSERT INTO library_entries (id, workId, isFavorite, createdAt, downloadProgress) " +
                        "SELECT id, COALESCE(NULLIF(mergeKey, ''), id), isFavorite, createdAt, downloadProgress FROM audiobooks"
                )

                // EXPAND 2 — works gains the series URL (the membership
                // signal), and every mergeable library book gets its Works row
                // (upserted merge-on-write by the pinned key; an existing
                // spec-23 row keeps its identity and only gains the series
                // fields it is missing).
                db.execSQL("ALTER TABLE works ADD COLUMN seriesUrl TEXT")
                db.query(
                    "SELECT id, mergeKey, title, author, narrator, seriesTitle, seriesUrl, seriesIndex, coverImageUrl, createdAt " +
                        "FROM audiobooks ORDER BY id ASC"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val bookId = cursor.getString(0)
                        val mergeKey = cursor.getString(1) ?: ""
                        if (mergeKey.isBlank()) continue
                        db.execSQL(
                            "INSERT INTO works (id, mergeKey, title, author, narrator, seriesTitle, seriesUrl, seriesIndex, coverImageUrl, addedAt) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                                "ON CONFLICT(id) DO UPDATE SET " +
                                "  seriesUrl = COALESCE(excluded.seriesUrl, works.seriesUrl), " +
                                "  seriesTitle = COALESCE(excluded.seriesTitle, works.seriesTitle), " +
                                "  seriesIndex = COALESCE(excluded.seriesIndex, works.seriesIndex)",
                            arrayOf(
                                mergeKey, mergeKey,
                                cursor.getString(2) ?: "", cursor.getString(3) ?: "", cursor.getString(4) ?: "",
                                cursor.getString(5), cursor.getString(6),
                                cursor.getInt(7).takeIf { !cursor.isNull(7) },
                                cursor.getString(8),
                                cursor.getLong(9)
                            )
                        )
                    }
                }
                // Blank-key rows: a crawled book whose Works row already
                // exists (spec-23 wrote it under the normalized title|author
                // key) is linked by that same key, and the row gains any
                // series fields the audiobooks row carried. Everything else
                // re-links on the next catalogue sync.
                db.query(
                    "SELECT id, title, author, seriesTitle, seriesUrl, seriesIndex FROM audiobooks ORDER BY id ASC"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val bookId = cursor.getString(0)
                        val title = cursor.getString(1) ?: ""
                        val author = cursor.getString(2) ?: ""
                        val key = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(title, author)
                        if (key.isBlank()) continue
                        // Only re-anchor when the Works row for that key
                        // actually exists — a blank-key book with a generic
                        // author must never be pointed at a phantom Work.
                        // (The entry still points at itself when the book had
                        // no pinned merge key.)
                        db.execSQL(
                            "UPDATE library_entries SET workId = ? WHERE id = ? AND workId = ? " +
                                "AND EXISTS (SELECT 1 FROM works WHERE works.id = ?)",
                            arrayOf(key, bookId, bookId, key)
                        )
                        db.execSQL(
                            "UPDATE works SET seriesUrl = COALESCE(?, seriesUrl), " +
                                "seriesTitle = COALESCE(?, seriesTitle), seriesIndex = COALESCE(?, seriesIndex) " +
                                "WHERE id = ?",
                            arrayOf(
                                // Columns: id(0), title(1), author(2),
                                // seriesTitle(3), seriesUrl(4), seriesIndex(5).
                                cursor.getString(4),
                                cursor.getString(3),
                                cursor.getInt(5).takeIf { !cursor.isNull(5) },
                                key
                            )
                        )
                    }
                }

                // EXPAND 3 — the preferred speed moves to the Listening State
                // row (playback_progress, keyed by the Edition). Back-filled
                // BEFORE the audiobooks rebuild drops the old column.
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN preferredSpeed REAL")
                db.execSQL(
                    "UPDATE playback_progress SET preferredSpeed = " +
                        "(SELECT audiobooks.preferredSpeed FROM audiobooks WHERE audiobooks.id = playback_progress.bookId)"
                )

                // CONTRACT — rebuild the audiobooks row without the fused
                // columns (rebuilt, not DROP COLUMN, for minSdk 24
                // compatibility — the same pattern as the v14 chapters).
                db.execSQL(
                    "CREATE TABLE audiobooks_new (" +
                        "id TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "narrator TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "coverDrawableRes INTEGER NOT NULL, " +
                        "coverImageUrl TEXT, " +
                        "genre TEXT NOT NULL, " +
                        "sourceUrl TEXT NOT NULL, " +
                        "isDownloaded INTEGER NOT NULL, " +
                        "totalDurationSeconds INTEGER NOT NULL, " +
                        "totalChapters INTEGER NOT NULL, " +
                        "rating REAL NOT NULL, " +
                        "sourceTreeUri TEXT, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL(
                    "INSERT INTO audiobooks_new (id, title, author, narrator, description, coverDrawableRes, " +
                        "coverImageUrl, genre, sourceUrl, isDownloaded, totalDurationSeconds, totalChapters, rating, sourceTreeUri) " +
                        "SELECT id, title, author, narrator, description, coverDrawableRes, coverImageUrl, " +
                        "genre, sourceUrl, isDownloaded, totalDurationSeconds, totalChapters, rating, sourceTreeUri FROM audiobooks"
                )
                db.execSQL("DROP TABLE audiobooks")
                db.execSQL("ALTER TABLE audiobooks_new RENAME TO audiobooks")
            }
        }

        /**
         * v15 -> v16 (ADR-0010 — the Work is bibliographic: mergeKey
         * `title|author`, narrator is an Edition property). The works row
         * loses its narrator column and is re-keyed on the narrator-less key
         * (rows that only differed by narrator MERGE into one Work); the
         * library_entries and work_sources anchors follow. Edition ids gain
         * the narrator as a hash input (`hash(mergeKey|narrator|language)`)
         * so two narrations of the same Work keep distinct listening state
         * (ADR-0001), and every edition-scoped row is remapped. The works and
         * work_sources tables are rebuilt TOGETHER in an FK-safe order (the
         * new work_sources references works_new, so the re-pointed workIds
         * validate against the table that will become `works`). Internal (not
         * private) so the JVM test suite can verify the upgrade path against
         * a real v15 database.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- Step 1: the narrator-less Work key per mergeable row --
                // The new key is computed from the row's own title/author so
                // it agrees with every future write. Blank-key rows (stable
                // per-source ids) are not mapped and keep their ids.
                val workIdMap = HashMap<String, String>()
                db.query("SELECT id, mergeKey, title, author FROM works ORDER BY addedAt ASC, id ASC").use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getString(0)
                        val mergeKey = cursor.getString(1) ?: ""
                        if (mergeKey.isBlank()) continue
                        val newId = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(
                            cursor.getString(2) ?: "", cursor.getString(3) ?: ""
                        )
                        if (newId.isNotBlank()) workIdMap[oldId] = newId
                    }
                }

                // ---- Step 2: works rebuilt without narrator, re-keyed and
                // de-duplicated (the earliest row wins; later duplicates only
                // enrich the survivor's series fields via COALESCE).
                db.execSQL(
                    "CREATE TABLE works_new (" +
                        "id TEXT NOT NULL, " +
                        "mergeKey TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "seriesTitle TEXT, " +
                        "seriesUrl TEXT, " +
                        "seriesIndex INTEGER, " +
                        "coverImageUrl TEXT, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.query(
                    "SELECT id, mergeKey, title, author, seriesTitle, seriesUrl, seriesIndex, coverImageUrl, addedAt " +
                        "FROM works ORDER BY addedAt ASC, id ASC"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getString(0)
                        val newId = workIdMap[oldId] ?: oldId
                        db.execSQL(
                            "INSERT OR IGNORE INTO works_new (id, mergeKey, title, author, seriesTitle, " +
                                "seriesUrl, seriesIndex, coverImageUrl, addedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                newId, newId,
                                cursor.getString(2) ?: "", cursor.getString(3) ?: "",
                                cursor.getString(4), cursor.getString(5),
                                cursor.getInt(6).takeIf { !cursor.isNull(6) },
                                cursor.getString(7), cursor.getLong(8)
                            )
                        )
                        if (newId != oldId) {
                            db.execSQL(
                                "UPDATE works_new SET seriesTitle = COALESCE(seriesTitle, ?), " +
                                    "seriesUrl = COALESCE(seriesUrl, ?), seriesIndex = COALESCE(seriesIndex, ?) " +
                                    "WHERE id = ?",
                                arrayOf(
                                    cursor.getString(4), cursor.getString(5),
                                    cursor.getInt(6).takeIf { !cursor.isNull(6) },
                                    newId
                                )
                            )
                        }
                    }
                }

                // ---- Step 3: work_sources rebuilt with the re-keyed workIds,
                // its FK pointing at works_new so the new ids validate against
                // the table that will become `works`.
                db.execSQL(
                    "CREATE TABLE work_sources_new (" +
                        "id TEXT NOT NULL, " +
                        "workId TEXT NOT NULL, " +
                        "sourceId TEXT NOT NULL, " +
                        "sourceUrl TEXT NOT NULL, " +
                        "streamOnly INTEGER NOT NULL DEFAULT 0, " +
                        "coverImageUrl TEXT, " +
                        "durationSeconds INTEGER, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(workId) REFERENCES works_new(id) ON DELETE CASCADE)"
                )
                db.query(
                    "SELECT id, workId, sourceId, sourceUrl, streamOnly, coverImageUrl, durationSeconds, addedAt " +
                        "FROM work_sources"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val newWorkId = workIdMap[cursor.getString(1)] ?: cursor.getString(1)
                        db.execSQL(
                            "INSERT INTO work_sources_new (id, workId, sourceId, sourceUrl, streamOnly, " +
                                "coverImageUrl, durationSeconds, addedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                cursor.getString(0), newWorkId, cursor.getString(2), cursor.getString(3),
                                cursor.getInt(4), cursor.getString(5),
                                cursor.getLong(6).let { if (cursor.isNull(6)) null else it },
                                cursor.getLong(7)
                            )
                        )
                    }
                }

                // ---- Step 4: library_entries re-pointed to the new Work ids
                // (no FK — safe in place, any time).
                for ((oldId, newId) in workIdMap) {
                    db.execSQL("UPDATE library_entries SET workId = ? WHERE workId = ?", arrayOf(newId, oldId))
                }

                // ---- Step 5: the swap. The old child goes first (dropping
                // `works` would otherwise fire ON DELETE CASCADE into it);
                // renaming works_new to `works` rewrites work_sources_new's
                // FK to reference `works`, matching the Room schema.
                db.execSQL("DROP TABLE work_sources")
                db.execSQL("DROP TABLE works")
                db.execSQL("ALTER TABLE works_new RENAME TO works")
                db.execSQL("ALTER TABLE work_sources_new RENAME TO work_sources")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_works_mergeKey ON works(mergeKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_workId ON work_sources(workId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_sources_sourceId ON work_sources(sourceId)")

                // ---- Step 6: Edition ids gain the narrator. The Work key of
                // each book is its (re-keyed) works.id via library_entries; a
                // blank-key book falls back to its own id.
                val editionRows = mutableListOf<Array<Any?>>()
                db.query("SELECT id, workId, language, narrator FROM editions").use { cursor ->
                    while (cursor.moveToNext()) {
                        editionRows.add(arrayOf(cursor.getString(0), cursor.getString(1), cursor.getString(2) ?: "", cursor.getString(3) ?: ""))
                    }
                }
                val editionIdMap = HashMap<String, String>()
                for (row in editionRows) {
                    val oldId = row[0] as String
                    val bookId = row[1] as String
                    val language = row[2] as String
                    val narrator = row[3] as String
                    val workKey = db.query(
                        "SELECT w.id FROM works w JOIN library_entries le ON le.workId = w.id " +
                            "WHERE le.id = ? LIMIT 1",
                        arrayOf(bookId)
                    ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
                    editionIdMap[oldId] = com.slukhayka.audiobooks.data.EditionId.forBook(workKey, bookId, narrator, language)
                }
                db.execSQL(
                    "CREATE TABLE editions_new (" +
                        "id TEXT NOT NULL, " +
                        "workId TEXT NOT NULL, " +
                        "language TEXT NOT NULL DEFAULT '', " +
                        "narrator TEXT NOT NULL DEFAULT '', " +
                        "totalChapters INTEGER NOT NULL DEFAULT 0, " +
                        "totalDurationSeconds INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
                db.query(
                    "SELECT id, workId, language, narrator, totalChapters, totalDurationSeconds FROM editions"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT INTO editions_new (id, workId, language, narrator, totalChapters, " +
                                "totalDurationSeconds) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                editionIdMap[cursor.getString(0)] ?: cursor.getString(0),
                                cursor.getString(1), cursor.getString(2) ?: "", cursor.getString(3) ?: "",
                                cursor.getInt(4), cursor.getLong(5)
                            )
                        )
                    }
                }
                db.execSQL("DROP TABLE editions")
                db.execSQL("ALTER TABLE editions_new RENAME TO editions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_editions_workId ON editions(workId)")

                // ---- Step 7: edition-scoped children remapped to the new
                // ids. chapters/sources/bookmarks update in place; the
                // playback_progress PRIMARY KEY is the editionId, so it is
                // rebuilt.
                for ((oldId, newId) in editionIdMap) {
                    if (oldId == newId) continue
                    db.execSQL("UPDATE chapters SET editionId = ? WHERE editionId = ?", arrayOf(newId, oldId))
                    db.execSQL("UPDATE sources SET editionId = ? WHERE editionId = ?", arrayOf(newId, oldId))
                    db.execSQL("UPDATE bookmarks SET editionId = ? WHERE editionId = ?", arrayOf(newId, oldId))
                }
                db.execSQL(
                    "CREATE TABLE playback_progress_new (" +
                        "editionId TEXT NOT NULL, " +
                        "bookId TEXT NOT NULL, " +
                        "currentChapterIndex INTEGER NOT NULL, " +
                        "currentPositionSeconds INTEGER NOT NULL, " +
                        "lastListenedAt INTEGER NOT NULL, " +
                        "isCompleted INTEGER NOT NULL, " +
                        "lastPausedAtEpochMs INTEGER, " +
                        "preferredSpeed REAL, " +
                        "PRIMARY KEY(editionId))"
                )
                db.query(
                    "SELECT editionId, bookId, currentChapterIndex, currentPositionSeconds, lastListenedAt, " +
                        "isCompleted, lastPausedAtEpochMs, preferredSpeed FROM playback_progress"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT INTO playback_progress_new (editionId, bookId, currentChapterIndex, " +
                                "currentPositionSeconds, lastListenedAt, isCompleted, lastPausedAtEpochMs, " +
                                "preferredSpeed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                editionIdMap[cursor.getString(0)] ?: cursor.getString(0),
                                cursor.getString(1), cursor.getInt(2), cursor.getLong(3), cursor.getLong(4),
                                cursor.getInt(5),
                                if (cursor.isNull(6)) null else cursor.getLong(6),
                                if (cursor.isNull(7)) null else cursor.getFloat(7)
                            )
                        )
                    }
                }
                db.execSQL("DROP TABLE playback_progress")
                db.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_bookId ON playback_progress(bookId)")
            }
        }

        /**
         * Spec-25 (#171): v16 -> v17 adds the universe cache — the `universes`
         * table plus the two nullable anchor columns on the (so far empty)
         * `series` table (the universe the series belongs to and its order
         * inside it). Pure additions: no existing row is touched, so the
         * migration is trivially safe on any v16 database. Internal (not
         * private) so the JVM test suite can verify the upgrade path against
         * a real v16 database.
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS universes (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("ALTER TABLE series ADD COLUMN universeId TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN positionInUniverse INTEGER")
            }
        }

        /**
         * Spec-25: v17 -> v18 gives the series-universe cache a bounded TTL —
         * `series_members.resolvedAt` (epoch millis) marks when a book's
         * resolution happened, so the Wikidata fallback can re-resolve stale
         * rows instead of persisting forever. Pure addition: pre-existing
         * memberships get a NULL timestamp, which the resolver treats as
         * stale (one refresh on the next book open). Internal (not private)
         * so the JVM test suite can verify the upgrade path against a real
         * v17 database.
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series_members ADD COLUMN resolvedAt INTEGER")
            }
        }

        /**
         * v18 -> v19 (spec-26 T7): the series' last publication year (P577) —
         * the age signal of the tiered refresh rule. Pure addition: pre-
         * existing series rows get a NULL year, which the tier treats as
         * unknown-age (never hot; warm only when the series sits at the chain
         * tail). Internal (not private) so the JVM test suite can verify the
         * upgrade path against a real v18 database.
         */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE series ADD COLUMN publicationYear INTEGER")
            }
        }

        /**
         * Spec-40 #281: v19 -> v20 adds the local reviewer-mute table — one
         * row per author whose reviews THIS listener hides (reversible, local
         * moderation v1). Pure addition: no existing row is touched, so the
         * migration is trivially safe on any v19 database. Internal (not
         * private) so the JVM test suite can verify the upgrade path against
         * a real v19 database.
         */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS hidden_reviewers (" +
                        "authorName TEXT NOT NULL, " +
                        "hiddenAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(authorName))"
                )
            }
        }

        /** #290: local-only explicit recommendation feedback. */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recommendation_preferences (" +
                        "kind TEXT NOT NULL, " +
                        "targetKey TEXT NOT NULL, " +
                        "sourceWorkId TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(kind, targetKey))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recommendation_preferences_sourceWorkId " +
                        "ON recommendation_preferences(sourceWorkId)"
                )
            }
        }

        /**
         * Spec-42 #304 expand gate. All structures are additive; the
         * provenance-bearing assertion stays separate from its normalized,
         * indexed Work↔genre projection.
         */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS work_facets (workId TEXT NOT NULL, canonicalAuthorId TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(workId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_facets_canonicalAuthorId ON work_facets(canonicalAuthorId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS work_facet_series (workId TEXT NOT NULL, seriesId TEXT NOT NULL, PRIMARY KEY(workId, seriesId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_facet_series_seriesId ON work_facet_series(seriesId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS genre_facets (id TEXT NOT NULL, displayName TEXT NOT NULL, normalizedName TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS work_genres (workId TEXT NOT NULL, genreId TEXT NOT NULL, sourceId TEXT NOT NULL, PRIMARY KEY(workId, genreId, sourceId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_genres_genreId ON work_genres(genreId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_genres_workId_sourceId ON work_genres(workId, sourceId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS genre_assertion_states (workId TEXT NOT NULL, sourceId TEXT NOT NULL, documentUpdatedAt INTEGER NOT NULL, PRIMARY KEY(workId, sourceId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS genre_assertions (id TEXT NOT NULL, assertionId TEXT NOT NULL, workId TEXT NOT NULL, genreId TEXT NOT NULL, rawText TEXT NOT NULL, sourceId TEXT NOT NULL, observedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genre_assertions_workId ON genre_assertions(workId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genre_assertions_genreId ON genre_assertions(genreId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genre_assertions_sourceId ON genre_assertions(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_genre_assertions_assertionId ON genre_assertions(assertionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS edition_facets (editionId TEXT NOT NULL, workId TEXT NOT NULL, narratorId TEXT, language TEXT, durationSeconds INTEGER, durationBucketId TEXT, chapterCount INTEGER, isAbridged INTEGER, availabilityAvailable INTEGER, availabilityObservedAtMillis INTEGER, availabilityTtlSeconds INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(editionId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_facets_workId ON edition_facets(workId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_facets_narratorId ON edition_facets(narratorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_facets_durationBucketId ON edition_facets(durationBucketId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_edition_facets_language ON edition_facets(language)")
                db.execSQL("CREATE TABLE IF NOT EXISTS author_facets (id TEXT NOT NULL, displayName TEXT NOT NULL, normalizedName TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_author_facets_normalizedName ON author_facets(normalizedName)")
                db.execSQL("CREATE TABLE IF NOT EXISTS author_aliases (authorId TEXT NOT NULL, normalizedAlias TEXT NOT NULL, rawAlias TEXT NOT NULL, sourceId TEXT NOT NULL, observedAt INTEGER NOT NULL, PRIMARY KEY(authorId, normalizedAlias, sourceId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_author_aliases_normalizedAlias ON author_aliases(normalizedAlias)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_author_aliases_sourceId ON author_aliases(sourceId)")
                backfillFacetProjections(db)
            }
        }

        /** #392 — downloadState for Library Entry (IDLE default). */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_entries ADD COLUMN downloadState TEXT NOT NULL DEFAULT 'IDLE'")
            }
        }

        /** Public for the migration acceptance test; replay is idempotent. */
        internal fun backfillFacetProjections(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO edition_facets (editionId, workId, narratorId, language, durationSeconds, durationBucketId, chapterCount, isAbridged, availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds, updatedAt) " +
                    "SELECT e.id, COALESCE(le.workId, e.workId), NULL, NULLIF(e.language, ''), " +
                    "CASE WHEN e.totalDurationSeconds > 0 AND e.totalDurationSeconds != ? AND e.totalDurationSeconds <= ? THEN e.totalDurationSeconds ELSE NULL END, " +
                    "NULL, CASE WHEN e.totalChapters > 0 THEN e.totalChapters ELSE NULL END, NULL, NULL, NULL, NULL, 0 " +
                    "FROM editions e LEFT JOIN library_entries le ON le.id=e.workId",
                arrayOf(DurationBuckets.FABRICATED_LEGACY_SECONDS, DurationSanity.MAX_PLAUSIBLE_SECONDS)
            )
            EditionDurationPolicy.buckets.forEach { bucket ->
                val secondsRange = EditionDurationPolicy.secondsRangeFor(bucket)
                db.execSQL(
                    "UPDATE edition_facets SET durationBucketId=? " +
                        "WHERE durationBucketId IS NULL AND durationSeconds BETWEEN ? AND ? AND durationSeconds != ?",
                    arrayOf<Any>(
                        bucket.wireName,
                        secondsRange.first,
                        secondsRange.last,
                        DurationBuckets.FABRICATED_LEGACY_SECONDS
                    )
                )
            }
            db.query("SELECT id, author FROM works").use { cursor ->
                while (cursor.moveToNext()) {
                    val workId = cursor.getString(0)
                    val author = cursor.getString(1)
                    val normalizedAuthor = FacetIdentity.normalizedText(author)
                    val authorId = normalizedAuthor?.let { FacetIdentity.boundedId("author", it) }
                    if (authorId != null) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO author_facets (id, displayName, normalizedName, updatedAt) VALUES (?, ?, ?, 0)",
                            arrayOf(authorId, author.trim().take(120), normalizedAuthor.take(120))
                        )
                        db.execSQL(
                            "INSERT OR IGNORE INTO author_aliases (authorId, normalizedAlias, rawAlias, sourceId, observedAt) VALUES (?, ?, ?, 'legacy:works', 0)",
                            arrayOf(authorId, normalizedAuthor.take(120), author.take(120))
                        )
                    }
                    db.execSQL(
                        "INSERT OR IGNORE INTO work_facets (workId, canonicalAuthorId, updatedAt) VALUES (?, ?, 0)",
                        arrayOf(workId, authorId)
                    )
                }
            }
            db.execSQL("INSERT OR IGNORE INTO work_facet_series (workId, seriesId) SELECT workId, seriesId FROM series_members")
            db.query(
                "SELECT le.workId, a.id, a.genre FROM audiobooks a " +
                    "JOIN library_entries le ON le.id=a.id WHERE TRIM(a.genre) != ''"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val workId = cursor.getString(0)
                    val bookId = cursor.getString(1)
                    val rawText = cursor.getString(2)
                    GenreIdentity.fromSourceText(rawText).forEach { genre ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO genre_facets (id, displayName, normalizedName) VALUES (?, ?, ?)",
                            arrayOf(genre.id, genre.label, FacetIdentity.normalizedText(genre.label).orEmpty())
                        )
                        db.execSQL(
                            "INSERT OR IGNORE INTO work_genres (workId, genreId, sourceId) VALUES (?, ?, 'legacy:audiobooks')",
                            arrayOf(workId, genre.id)
                        )
                        db.execSQL(
                            "INSERT OR IGNORE INTO genre_assertions (id, assertionId, workId, genreId, rawText, sourceId, observedAt) VALUES (?, ?, ?, ?, ?, 'legacy:audiobooks', 0)",
                            arrayOf("legacy:$bookId:${genre.id}", "legacy:$bookId", workId, genre.id, rawText)
                        )
                    }
                    db.execSQL(
                        "INSERT INTO genre_assertion_states (workId, sourceId, documentUpdatedAt) VALUES (?, 'legacy:audiobooks', 0) " +
                            "ON CONFLICT(workId, sourceId) DO UPDATE SET documentUpdatedAt=MAX(documentUpdatedAt, excluded.documentUpdatedAt)",
                        arrayOf(workId)
                    )
                }
            }
        }
    }
}
