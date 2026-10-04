package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedListeningTimeTest {
    @Test fun `new factual intervals keep legacy seconds and accumulate fractions across restart`() = runTest {
        val dao = FakeAudiobookDao()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        dao.saveListeningStat(ListeningStatEntity(date, 12345L))
        val first = ListeningStateStore(dao)
        first.recordActualListeningTime(650L)
        first.recordActualListeningTime(150L)
        val restarted = ListeningStateStore(dao)
        restarted.recordActualListeningTime(400L)
        restarted.recordActualListeningTime(0L)
        restarted.recordActualListeningTime(-500L)
        val row = restarted.getAllListeningStats().first().single()
        assertEquals(1200L, row.verifiedListenedMillis)
        assertEquals(12346L, row.listenedSeconds)
    }
}
