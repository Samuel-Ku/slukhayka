package com.slukhayka.audiobooks.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real exported schemas from both lanes must upgrade into one validated database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class IntegratedPeopleMigrationTest {
    @Test fun release26PreservesPersonBookmark() {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/26.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        verifyUpgrade(JSONObject(file.readText()).getJSONObject("database"), 0)
    }

    @Test fun people25PreservesExistingNotificationCount() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/people-branch-25.json"))
            .bufferedReader().use { it.readText() }
        verifyUpgrade(JSONObject(text).getJSONObject("database"), 7)
    }

    @Test fun people27PreservesNotificationCount() {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/27.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        verifyUpgrade(JSONObject(file.readText()).getJSONObject("database"), 7)
    }

    @Test fun popularity27PreservesSourceSignals() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("migrations/popularity-branch-27.json"))
            .bufferedReader().use { it.readText() }
        verifyUpgrade(JSONObject(text).getJSONObject("database"), 0)
    }

    /**
     * #1101 — end to end on a real exported schema, from v33: the NEXT step of
     * that chain is `MIGRATION_33_34`, whose own comment says it ran
     * `DELETE FROM audiobooks WHERE sourceUrl LIKE '%4read.org%'`, and the
     * history is real — the exported v33 schema still has
     * `audiobooks.sourceUrl`, the column that DELETE matches on.
     *
     * The test asserts BOTH halves. First the premise, right after the legacy
     * file is built: the purge inside the chain really does orphan the bookmark
     * and the listening rows, so the repair below is not vacuous. Then the
     * upgrade: no row of `bookmarks`, `playback_progress` or `playback_events`
     * may be left without its book, while the living book keeps its bookmark
     * untouched.
     */
    @Test fun upgradeLeavesNoBookmarkOrListeningStateWithoutItsBook() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacySchema = schema("33.json")
        val version = legacySchema.getInt("version")
        val name = "integrated-orphan-$version.db"
        context.deleteDatabase(name)
        try {
            openLegacy(context, name, legacySchema, version) { db ->
                for (id in listOf("4read-scam", "real-book")) {
                    val url =
                        if (id == "4read-scam") "https://4read.org/fake" else "https://sound-books.net/real"
                    db.execSQL(
                        "INSERT INTO audiobooks (id,title,author,narrator,description,coverDrawableRes," +
                            "coverImageUrl,genre,sourceUrl,isDownloaded,totalDurationSeconds,totalChapters,rating) " +
                            "VALUES ('$id','Книга','Автор','','',0,NULL,'','$url',0,0,0,0)"
                    )
                }
                db.execSQL(
                    "INSERT INTO bookmarks (bookId,editionId,chapterIndex,chapterTitle,timestampSeconds,note,createdAt) " +
                        "VALUES ('4read-scam',NULL,0,'Розділ 1',10,'сирота',1)"
                )
                db.execSQL(
                    "INSERT INTO bookmarks (bookId,editionId,chapterIndex,chapterTitle,timestampSeconds,note,createdAt) " +
                        "VALUES ('real-book',NULL,0,'Розділ 1',20,'жива',2)"
                )
                db.execSQL(
                    "INSERT INTO playback_progress (editionId,bookId,currentChapterIndex,currentPositionSeconds," +
                        "lastListenedAt,isCompleted) VALUES ('ed-dead','4read-scam',0,10,1,0)"
                )
                db.execSQL(
                    "INSERT INTO playback_events (bookId,sourceKey,kind,chapterIndex,positionSeconds,timestamp,deviceId) " +
                        "VALUES ('4read-scam','','RESUME',0,10,1,'')"
                )
            }

            // The premise, in two steps, because the damage and the repair
            // live in different migrations. Opening the file through Room
            // always upgrades to the CURRENT version, so the intermediate state
            // is read on a raw handle: run the chain up to v49 on a copy of the
            // file, and the orphans must be visible there — otherwise the
            // repair below would pass on a database that was never damaged.
            migrateUpTo49(context, name, version)
            openRaw(context, name, 49).use { at49 ->
                assertEquals(
                    "передумова тесту: пурж у міграціях 32→42 справді лишає закладку-сироту",
                    1,
                    countOrphans(at49, "bookmarks")
                )
                assertEquals(
                    "передумова тесту: Listening State-сирота теж лишається",
                    1,
                    countOrphans(at49, "playback_progress")
                )
            }

            val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .allowMainThreadQueries().build()
            try {
                for (table in listOf("bookmarks", "playback_progress", "playback_events")) {
                    assertEquals(
                        "сироти лишились у $table",
                        0,
                        countOrphans(migrated.openHelper.writableDatabase, table)
                    )
                }
                migrated.openHelper.writableDatabase.query(
                    "SELECT note FROM bookmarks WHERE bookId='real-book'"
                ).use { row ->
                    check(row.moveToFirst())
                    assertEquals("закладка живої книги зникла", "жива", row.getString(0))
                }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun schema(fileName: String): JSONObject {
        val relative = "schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/$fileName"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun openLegacy(
        context: Context,
        name: String,
        schema: JSONObject,
        version: Int,
        seed: (SupportSQLiteDatabase) -> Unit
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (j in 0 until indices.length()) {
                            db.execSQL(
                                indices.getJSONObject(j).getString("createSql")
                                    .replace("${'$'}{TABLE_NAME}", table)
                            )
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    seed(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
    ).also { it.writableDatabase }

    /** A raw handle to an existing database file at [version]; no schema management. */
    private fun openRaw(context: Context, name: String, version: Int): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        ).writableDatabase

    /**
     * Applies the chain up to v49 directly on the file, so the state between the
     * purge and the repair can be observed. Room cannot be asked for this: a
     * builder always migrates to the declared current version.
     */
    private fun migrateUpTo49(context: Context, name: String, from: Int) {
        val db = openRaw(context, name, from)
        try {
            for (migration in MIGRATIONS_UP_TO_49) {
                if (migration.startVersion >= from) migration.migrate(db)
            }
            db.version = 49
        } finally {
            db.close()
        }
    }

    /**
     * Counts rows in [table] whose `bookId` resolves to no book. Uses
     * `query(String)` (raw SQL) rather than the bind-args overload: the
     * `NOT EXISTS` subquery is the whole point here and must reach SQLite
     * exactly as written.
     */
    private fun countOrphans(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table` t WHERE NOT EXISTS " +
            "(SELECT 1 FROM `audiobooks` a WHERE a.`id` = t.`bookId`)").use { row ->
            check(row.moveToFirst())
            row.getInt(0)
        }

    private fun verifyUpgrade(schema: JSONObject, expectedCount: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val version = schema.getInt("version")
        val hasCount = schema.toString().contains("lastNotifiedCount")
        val hasSignals = schema.toString().contains("popularity_assertions")
        val name = "integrated-people-$version.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO person_bookmarks (kind,id,displayName,normalizedName,createdAt,lastSeenAt,lastNotifiedAt,notifyEnabled,updatedAt" +
                (if (hasCount) ",lastNotifiedCount" else "") + ") VALUES ('AUTHOR','author:test','Автор','автор',11,22,33,1,44" +
                (if (hasCount) ",7" else "") + ")"
        )
        if (hasSignals) helper.writableDatabase.execSQL(
            "INSERT INTO popularity_assertions VALUES ('signal:test','rank','книга|автор','12','4read',123)"
        )
        helper.close()
        val migrated = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .addMigrations(
                AudiobookDatabase.MIGRATION_25_26, AudiobookDatabase.MIGRATION_26_27,
                AudiobookDatabase.MIGRATION_27_28, AudiobookDatabase.MIGRATION_28_29,
                AudiobookDatabase.MIGRATION_29_30,
                AudiobookDatabase.MIGRATION_30_31,
                AudiobookDatabase.MIGRATION_31_32,
                // #812 — ланцюг мусить доходити до поточної версії, інакше
                // Room не знаходить шляху (25→39) і тест падає.
                AudiobookDatabase.MIGRATION_32_33, AudiobookDatabase.MIGRATION_33_34,
                AudiobookDatabase.MIGRATION_34_35, AudiobookDatabase.MIGRATION_35_36,
                AudiobookDatabase.MIGRATION_36_37, AudiobookDatabase.MIGRATION_37_38,
                AudiobookDatabase.MIGRATION_38_39,
                // #812 — ланцюг мусить доходити до ПОТОЧНОЇ версії. Кожна
                // нова міграція вимагає дописати себе сюди, інакше Room не
                // знаходить шляху 25→N і тест падає.
                AudiobookDatabase.MIGRATION_39_40,
                AudiobookDatabase.MIGRATION_40_41,
                AudiobookDatabase.MIGRATION_41_42,
                AudiobookDatabase.MIGRATION_42_43,
                AudiobookDatabase.MIGRATION_43_44,
                AudiobookDatabase.MIGRATION_44_45,
                // #883 — the database is at 47 now: a path from an OLD schema
                // must reach the CURRENT version, so the two v1.8 steps that
                // added `readthroughs` and `library_entries.origin` belong here
                // too. Without them Room refuses the whole path ("A migration
                // from 26 to 47 was required but not found").
                AudiobookDatabase.MIGRATION_45_46,
                AudiobookDatabase.MIGRATION_46_47,
                AudiobookDatabase.MIGRATION_47_48,
                AudiobookDatabase.MIGRATION_48_49,
                // #1101 — the current version is 50 now; the repair of the
                // 4read purge's orphans belongs in this chain, otherwise Room
                // finds no path from 26/27 to 50.
                AudiobookDatabase.MIGRATION_49_50, AudiobookDatabase.MIGRATION_50_51
            )
            .allowMainThreadQueries().build()
        try {
            // Opening through Room validates every entity and index, not only the added column.
            migrated.openHelper.writableDatabase.query(
                "SELECT displayName,createdAt,lastSeenAt,lastNotifiedAt,notifyEnabled,updatedAt,lastNotifiedCount FROM person_bookmarks WHERE id='author:test'"
            ).use { row ->
                check(row.moveToFirst())
                assertEquals("Автор", row.getString(0))
                assertEquals(listOf(11L,22L,33L,1L,44L,expectedCount.toLong()), (1..6).map { row.getLong(it) })
            }
            if (hasSignals) migrated.openHelper.writableDatabase.query(
                "SELECT rawValue,observedAt FROM popularity_assertions WHERE id='signal:test'"
            ).use { row ->
                check(row.moveToFirst())
                assertEquals("12", row.getString(0))
                assertEquals(123L, row.getLong(1))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        /**
         * The chain must reach the CURRENT version, or Room finds no path from
         * an old schema and fails with "A migration from 26 to 50 was required
         * but not found" — every new migration appends itself here. (#1101)
         */
        val MIGRATIONS_UP_TO_49 = arrayOf(
            AudiobookDatabase.MIGRATION_25_26, AudiobookDatabase.MIGRATION_26_27,
            AudiobookDatabase.MIGRATION_27_28, AudiobookDatabase.MIGRATION_28_29,
            AudiobookDatabase.MIGRATION_29_30, AudiobookDatabase.MIGRATION_30_31,
            AudiobookDatabase.MIGRATION_31_32, AudiobookDatabase.MIGRATION_32_33,
            AudiobookDatabase.MIGRATION_33_34, AudiobookDatabase.MIGRATION_34_35,
            AudiobookDatabase.MIGRATION_35_36, AudiobookDatabase.MIGRATION_36_37,
            AudiobookDatabase.MIGRATION_37_38, AudiobookDatabase.MIGRATION_38_39,
            AudiobookDatabase.MIGRATION_39_40, AudiobookDatabase.MIGRATION_40_41,
            AudiobookDatabase.MIGRATION_41_42, AudiobookDatabase.MIGRATION_42_43,
            AudiobookDatabase.MIGRATION_43_44, AudiobookDatabase.MIGRATION_44_45,
            AudiobookDatabase.MIGRATION_45_46, AudiobookDatabase.MIGRATION_46_47,
            AudiobookDatabase.MIGRATION_47_48, AudiobookDatabase.MIGRATION_48_49
        )

        val ALL_MIGRATIONS = arrayOf(
            AudiobookDatabase.MIGRATION_25_26, AudiobookDatabase.MIGRATION_26_27,
            AudiobookDatabase.MIGRATION_27_28, AudiobookDatabase.MIGRATION_28_29,
            AudiobookDatabase.MIGRATION_29_30, AudiobookDatabase.MIGRATION_30_31,
            AudiobookDatabase.MIGRATION_31_32, AudiobookDatabase.MIGRATION_32_33,
            AudiobookDatabase.MIGRATION_33_34, AudiobookDatabase.MIGRATION_34_35,
            AudiobookDatabase.MIGRATION_35_36, AudiobookDatabase.MIGRATION_36_37,
            AudiobookDatabase.MIGRATION_37_38, AudiobookDatabase.MIGRATION_38_39,
            AudiobookDatabase.MIGRATION_39_40, AudiobookDatabase.MIGRATION_40_41,
            AudiobookDatabase.MIGRATION_41_42, AudiobookDatabase.MIGRATION_42_43,
            AudiobookDatabase.MIGRATION_43_44, AudiobookDatabase.MIGRATION_44_45,
            AudiobookDatabase.MIGRATION_45_46, AudiobookDatabase.MIGRATION_46_47,
            AudiobookDatabase.MIGRATION_47_48, AudiobookDatabase.MIGRATION_48_49,
            AudiobookDatabase.MIGRATION_49_50, AudiobookDatabase.MIGRATION_50_51
        )
    }
}
