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
        ListeningStatEntity::class
    ],
    version = 4,
    exportSchema = false
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
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
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
    }
}
