package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PreservedWorkMergeParticipant
import com.slukhayka.audiobooks.data.db.WorkEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WorkIdentityResolverNamespaceIsolationRoomTest {
    @Test(timeout = 180_000)
    fun `resolver keeps WorkId and MergeKey namespaces separate after reopening without writing Works`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "work-identity-namespace-isolation-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open(): AudiobookDatabase = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        var database = open()
        try {
            val workA = WorkEntity(
                id = "namespace-work-a", mergeKey = "namespace token|shared author",
                title = "Namespace Alpha", author = "Shared Author", seriesTitle = null,
                seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 10L,
            )
            val workB = WorkEntity(
                id = "namespace token|shared author", mergeKey = "another namespace|shared author",
                title = "Namespace Beta", author = "Shared Author", seriesTitle = null,
                seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 20L,
            )
            database.audiobookDao().upsertWork(workA)
            database.audiobookDao().upsertWork(workB)
            assertStored(database, workA, workB)

            database.close()
            database = open()
            assertStored(database, workA, workB)
            val resolver = WorkIdentityAccess(database, ManualWorkMergeGate(database))
            assertEquals(
                "A WorkId token must select the complete Work B with that id",
                WorkResolution.Resolved(workB),
                resolver.resolve(WorkIdentityInput.WorkId("namespace token|shared author")),
            )
            assertStored(database, workA, workB)
            assertEquals(
                "The same MergeKey token must select the complete Work A with that mergeKey",
                WorkResolution.Resolved(workA),
                resolver.resolve(WorkIdentityInput.MergeKey("namespace token|shared author")),
            )
            assertStored(database, workA, workB)
            val missingWorkId = WorkIdentityInput.WorkId("another namespace|shared author")
            assertEquals(
                "A missing WorkId must retain its input and never fall back to a MergeKey",
                WorkResolution.Missing(missingWorkId),
                resolver.resolve(missingWorkId),
            )
            assertStored(database, workA, workB)
            assertEquals(
                "The other MergeKey must still resolve the complete Work B",
                WorkResolution.Resolved(workB),
                resolver.resolve(WorkIdentityInput.MergeKey("another namespace|shared author")),
            )
            assertStored(database, workA, workB)
        } finally {
            try {
                database.close()
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    private suspend fun assertStored(database: AudiobookDatabase, workA: WorkEntity, workB: WorkEntity) {
        val dao = database.audiobookDao()
        assertEquals(2, dao.countWorks())
        assertEquals(listOf(workB, workA), dao.observeWorks().first())
        assertEquals(workA, dao.getWorkById("namespace-work-a"))
        assertEquals(workB, dao.getWorkById("namespace token|shared author"))
        val identity = database.workIdentityDao()
        assertEquals(0, identity.operationCount())
        assertEquals(0, identity.redirectCount())
        // Existing public reader is per operation; this is scoped absence, not a global table count.
        assertEquals(
            emptyList<PreservedWorkMergeParticipant>(),
            identity.preservedParticipants("00000000-0000-4000-8015-000000000968"),
        )
    }
}
