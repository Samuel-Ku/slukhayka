package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AudiobookEntity::class,
        SourceEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        PlaybackProgressEntity::class,
        ListeningStatEntity::class,
        PlaybackFailureEntity::class,
        PlaybackEventEntity::class,
        TombstoneEntity::class,
        CorrectionEntity::class,
        SeriesEntity::class,
        SeriesMemberEntity::class,
        EditionSettingsEntity::class,
        WorkEntity::class,
        EditionEntity::class
    ],
    version = 13,
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
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
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
    }
}
