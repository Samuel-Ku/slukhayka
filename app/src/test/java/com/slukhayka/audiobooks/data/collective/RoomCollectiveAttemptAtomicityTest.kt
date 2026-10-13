package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SQLite delegation observes actual mapped rows and pauses before the real write lock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveAttemptAtomicityTest {

    private val a = CollectiveFeedBlock(
        blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
        kind = CollectiveBlockKind.RECOMMENDATIONS, name = "До «Тарас Бульба»",
        provenanceUrl = "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба",
        cards = listOf(
                CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7043213:Кониський-Олександр-Семен-Жук-і-його-родичі", "Семен Жук і його родичі", "Кониський Олександр", "https://fixture.invalid/covers/a.png")
        ), fetchedAt = 1000L, staleAfter = 86401000L, version = 1L,
        lastAttempt = CollectiveAttempt(1000L, CollectiveAttemptStatus.SUCCESS)
    )

    private val b = CollectiveFeedBlock(
        blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
        kind = CollectiveBlockKind.RECOMMENDATIONS, name = "До «Сердешна Оксана»",
        provenanceUrl = "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana",
        cards = listOf(
                CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба", "Тарас Бульба", "Микола Гоголь", "https://fixture.invalid/covers/b.png"),
                CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7043213:Кониський-Олександр-Семен-Жук-і-його-родичі", "Семен Жук і його родичі", "Кониський Олександр", "https://fixture.invalid/covers/a.png")
        ), fetchedAt = 2000L, staleAfter = 86402000L, version = 2L,
        lastAttempt = CollectiveAttempt(2000L, CollectiveAttemptStatus.SUCCESS)
    )

    private val attempt = CollectiveAttempt(3000L, CollectiveAttemptStatus.TIMEOUT)

    @Test(timeout = 90_000L)
    fun `recording an attempt preserves a complete block committed before its write transaction`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "collective-attempt-${UUID.randomUUID()}.db"
        val gate = AttemptBoundary()
        var owner: AudiobookDatabase? = null
        var peer: AudiobookDatabase? = null
        var ownerExecutor: ExecutorService? = null
        var peerExecutor: ExecutorService? = null
        var recording: Job? = null
        var bodyFailure: Throwable? = null
        try {
            withTimeout(60_000L) {
                assertEquals(Application::class.java, context.javaClass)
                ownerExecutor = Executors.newSingleThreadExecutor()
                peerExecutor = Executors.newSingleThreadExecutor()
                owner = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryExecutor(requireNotNull(ownerExecutor))
                    .setTransactionExecutor(requireNotNull(ownerExecutor))
                    .openHelperFactory(ObservedOpenHelperFactory(gate))
                    .build()
                peer = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryExecutor(requireNotNull(peerExecutor))
                    .setTransactionExecutor(requireNotNull(peerExecutor))
                    .build()
                val ownerDb = requireNotNull(owner)
                val peerDb = requireNotNull(peer)
                val ownerStore = RoomCollectiveFeedBlockStore(ownerDb.audiobookDao())
                val peerStore = RoomCollectiveFeedBlockStore(peerDb.audiobookDao())
                val rowA = row(a)
                val rowB = row(b)
                assertTrue(peerStore.activate(a))
                assertEquals(a, ownerStore.active(a.blockKey))
                assertEquals(54, ownerDb.openHelper.writableDatabase.version)
                assertEquals(54, peerDb.openHelper.writableDatabase.version)
                assertEquals(context.getDatabasePath(name).canonicalFile, java.io.File(ownerDb.openHelper.writableDatabase.path).canonicalFile)
                assertEquals(context.getDatabasePath(name).canonicalFile, java.io.File(peerDb.openHelper.writableDatabase.path).canonicalFile)

                // Warm the real owner path without changing A. This proves the public context
                // marker reaches the actual Room dispatcher and cursor, not an inferred thread id.
                withContext(gate.marker.asContextElement(true)) {
                    ownerStore.recordAttempt(a.blockKey, a.lastAttempt)
                }
                val warm = gate.snapshot()
                assertEquals(listOf(rowA), warm.filter { it.owned && it.kind == "READ" }.map { it.row })
                assertEquals(listOf(rowA), warm.filter { it.owned && it.kind == "WRITE_RETURN" }.map { it.row })
                assertEquals(a, peerStore.active(a.blockKey))
                println("S2_ATTEMPT_WARM_REAL_SQL=" + warm)

                gate.arm()
                recording = launch(Dispatchers.Default + gate.marker.asContextElement(true)) {
                    ownerStore.recordAttempt(a.blockKey, attempt)
                }
                val boundary = withTimeout(10_000L) { gate.arrived.await() }
                assertFalse("the held boundary must precede an actual SQLite transaction", boundary.inTransaction)
                assertTrue("only no read or the complete initial A may precede BEGIN",
                    boundary.rowsBeforeBegin.isEmpty() || boundary.rowsBeforeBegin.all { it == rowA })

                // The owner's real BEGIN has not entered SQLite. A second real Room connection
                // can commit B even when a corrected implementation begins before its first read.
                withTimeout(10_000L) { assertTrue(peerStore.activate(b)) }
                assertEquals(b, peerStore.active(b.blockKey))
                assertEquals(rowB, peerDb.audiobookDao().getFeedSnapshot("sluhayua", "collective-recommendations"))
                gate.peerCommitted(rowB)
                gate.release.countDown()
                withTimeout(10_000L) { requireNotNull(recording).join() }
                assertTrue(requireNotNull(recording).isCompleted)
                assertFalse(requireNotNull(recording).isCancelled)
                gate.disarm()

                val observed = gate.snapshot()
                val entered = observed.indexOfFirst { it.owned && it.kind == "BEGIN_ENTERED" }
                val committed = observed.indexOfFirst { it.kind == "PEER_COMMITTED" }
                val write = observed.indexOfFirst { it.owned && it.kind == "WRITE_REQUEST" }
                assertTrue("actual peer commit must precede owner's BEGIN and first write", committed >= 0 && entered > committed && write > entered)
                val firstSql = observed.drop(entered + 1).first { it.owned && it.kind == "SQL" }.sql
                assertTrue("the held transaction must be the feed operation, not invalidation work",
                    firstSql == SELECT_ROW || firstSql == INSERT_ROW)
                val afterBeginReads = observed.drop(entered + 1).filter { it.owned && it.kind == "READ" }.map { it.row }
                if (afterBeginReads.isEmpty()) {
                    // Current implementation: the exact real mapped A was read before B committed.
                    assertTrue(boundary.rowsBeforeBegin.isNotEmpty())
                    assertEquals(listOf(row(a.copy(lastAttempt = attempt))),
                        observed.filter { it.owned && it.kind == "WRITE_REQUEST" }.map { it.row })
                } else {
                    // Legitimate corrected implementation: BEGIN was held before the latest read,
                    // so every real row mapped inside that transaction must be complete B.
                    assertTrue(afterBeginReads.all { it == rowB })
                    assertEquals(listOf(row(b.copy(lastAttempt = attempt))),
                        observed.filter { it.owned && it.kind == "WRITE_REQUEST" }.map { it.row })
                }
                assertEquals(1, observed.count { it.owned && it.kind == "WRITE_RETURN" })
                assertTrue(observed.any { it.owned && it.kind == "END_RETURN" })
                println("S2_ATTEMPT_CAUSAL_REAL_SQL=" + observed)

                assertEquals("S2_ROOM_ATTEMPT_PRESERVATION_FIRST: recording a failure must preserve the newly committed complete block",
                    b.copy(lastAttempt = attempt), ownerStore.active(a.blockKey))
                assertEquals(b.copy(lastAttempt = attempt), peerStore.active(a.blockKey))
                assertEquals(listOf(row(b.copy(lastAttempt = attempt))),
                    peerDb.audiobookDao().getFeedSnapshots("sluhayua", "collective-recommendations"))
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            // Always release the owned real SQL call before cancellation/close. Every cleanup
            // is independent; primary failure is retained and later errors remain suppressed.
            gate.disarm()
            gate.release.countDown()
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val primary = bodyFailure ?: cleanupFailure
                    if (primary == null) cleanupFailure = failure else primary.addSuppressed(failure)
                }
            }
            withContext(NonCancellable) {
                cleanup { withTimeout(15_000L) { recording?.cancelAndJoin() } }
                cleanup { withContext(Dispatchers.IO) { owner?.close() } }
                cleanup { withContext(Dispatchers.IO) { peer?.close() } }
                cleanup { ownerExecutor?.shutdownNow() }
                cleanup { peerExecutor?.shutdownNow() }
                cleanup { withContext(Dispatchers.IO) { ownerExecutor?.let { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) } } }
                cleanup { withContext(Dispatchers.IO) { peerExecutor?.let { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) } } }
                cleanup { assertTrue(context.deleteDatabase(name)) }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun row(block: CollectiveFeedBlock) = FeedSnapshotEntity(
        sourceId = block.sourceId, feedKey = collectiveFeedKey(block.kind), pageCursor = "",
        fetchedAt = block.fetchedAt, cardsJson = CollectiveFeedBlockCodec.encode(block)
    )

    private data class Event(val kind: String, val owned: Boolean, val sql: String? = null, val row: FeedSnapshotEntity? = null)
    private data class Boundary(val inTransaction: Boolean, val rowsBeforeBegin: List<FeedSnapshotEntity?>)

    private class AttemptBoundary {
        val marker = ThreadLocal<Boolean>()
        val arrived = CompletableDeferred<Boundary>()
        val release = CountDownLatch(1)
        private val held = AtomicBoolean(false)
        private val events = CopyOnWriteArrayList<Event>()
        @Volatile private var armed = false
        fun snapshot(): List<Event> = events.toList()
        fun record(kind: String, sql: String? = null, row: FeedSnapshotEntity? = null) {
            events += Event(kind, marker.get() == true, sql, row)
        }
        fun arm() { events.clear(); armed = true }
        fun disarm() { armed = false }
        fun peerCommitted(row: FeedSnapshotEntity) { events += Event("PEER_COMMITTED", false, row = row) }
        fun beforeBegin(database: SupportSQLiteDatabase) {
            record("BEGIN_REQUEST")
            if (armed && marker.get() == true && held.compareAndSet(false, true)) {
                val rows = snapshot().filter { it.owned && it.kind == "READ" }.map { it.row }
                arrived.complete(Boundary(database.inTransaction(), rows))
                check(release.await(15, TimeUnit.SECONDS)) { "Owned SQL boundary release timed out" }
            }
        }
    }

    private class ObservedOpenHelperFactory(private val gate: AttemptBoundary) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(helper.readableDatabase)
            }
        }

        private fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by database {
            override fun beginTransaction() {
                gate.beforeBegin(database); database.beginTransaction(); gate.record("BEGIN_ENTERED")
            }
            override fun beginTransactionNonExclusive() {
                gate.beforeBegin(database); database.beginTransactionNonExclusive(); gate.record("BEGIN_ENTERED")
            }
            override fun endTransaction() { database.endTransaction(); gate.record("END_RETURN") }
            override fun query(query: SupportSQLiteQuery): Cursor {
                gate.record("SQL", query.sql)
                return observe(query.sql, database.query(query))
            }
            override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                gate.record("SQL", query.sql)
                return observe(query.sql, database.query(query, cancellationSignal))
            }
            override fun query(query: String): Cursor {
                gate.record("SQL", query); return observe(query, database.query(query))
            }
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                gate.record("SQL", query); return observe(query, database.query(query, bindArgs))
            }
            override fun execSQL(sql: String) { gate.record("SQL", sql); database.execSQL(sql) }
            override fun execSQL(sql: String, bindArgs: Array<out Any?>) { gate.record("SQL", sql); database.execSQL(sql, bindArgs) }
            override fun compileStatement(sql: String): SupportSQLiteStatement {
                gate.record("SQL", sql)
                val actual = database.compileStatement(sql)
                if (sql != INSERT_ROW || gate.marker.get() != true) return actual
                val bindings = mutableMapOf<Int, Any>()
                return object : SupportSQLiteStatement by actual {
                    override fun bindString(index: Int, value: String) { actual.bindString(index, value); bindings[index] = value }
                    override fun bindLong(index: Int, value: Long) { actual.bindLong(index, value); bindings[index] = value }
                    override fun clearBindings() { actual.clearBindings(); bindings.clear() }
                    override fun execute() {
                        val row = FeedSnapshotEntity(bindings[1] as String, bindings[2] as String, bindings[3] as String,
                            bindings[4] as Long, bindings[5] as String)
                        gate.record("WRITE_REQUEST", row = row)
                        actual.execute()
                        gate.record("WRITE_RETURN", row = row)
                    }
                }
            }
        }

        private fun observe(sql: String, actual: Cursor): Cursor {
            if (sql != SELECT_ROW || gate.marker.get() != true) return actual
            return object : CursorWrapper(actual) {
                private val values = mutableMapOf<String, Any?>()
                override fun getString(columnIndex: Int): String? = super.getString(columnIndex).also {
                    values[super.getColumnName(columnIndex)] = it
                }
                override fun getLong(columnIndex: Int): Long = super.getLong(columnIndex).also {
                    values[super.getColumnName(columnIndex)] = it
                }
                override fun close() {
                    try {
                        check(values.keys.containsAll(listOf("sourceId", "feedKey", "pageCursor", "fetchedAt", "cardsJson"))) {
                            "Real feed cursor did not expose every generated mapped field"
                        }
                        gate.record("READ", row = FeedSnapshotEntity(values["sourceId"] as String, values["feedKey"] as String,
                            values["pageCursor"] as String, values["fetchedAt"] as Long, values["cardsJson"] as String))
                    } finally { super.close() }
                }
            }
        }
    }

    private companion object {
        const val SELECT_ROW = "SELECT * FROM feed_snapshots WHERE sourceId = ? AND feedKey = ? AND pageCursor = ? LIMIT 1"
        const val INSERT_ROW = "INSERT OR REPLACE INTO `feed_snapshots` (`sourceId`,`feedKey`,`pageCursor`,`fetchedAt`,`cardsJson`) VALUES (?,?,?,?,?)"
    }
}
