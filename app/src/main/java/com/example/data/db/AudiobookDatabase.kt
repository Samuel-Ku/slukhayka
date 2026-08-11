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
        ChapterEntity::class,
        BookmarkEntity::class,
        PlaybackProgressEntity::class,
        ListeningStatEntity::class,
        PlaybackFailureEntity::class
    ],
    version = 8,
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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
         * v7 -> v8 (wayfinder #47/#48/#52): three additive, append-only
         * changes. `chapters.contentHash` and `audiobooks.sourceTreeUri`
         * back the local-import work (SHA-256 dedupe + persisted SAF grants);
         * the new `playback_failures` table is the durable error ledger. All
         * new columns are nullable and all new writes come from new code
         * paths, so existing rows need no backfill. Internal (not private) so
         * the JVM test suite can verify the upgrade path.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN contentHash TEXT")
                db.execSQL("ALTER TABLE audiobooks ADD COLUMN sourceTreeUri TEXT")
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
            }
        }
    }
}
