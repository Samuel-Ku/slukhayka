package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AudiobookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        PlaybackProgressEntity::class,
        ListeningStatEntity::class
    ],
    version = 3,
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
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
