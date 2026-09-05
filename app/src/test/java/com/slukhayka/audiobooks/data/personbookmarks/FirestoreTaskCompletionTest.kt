package com.slukhayka.audiobooks.data.personbookmarks

import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreTaskCompletionTest {
    @Test fun `successful Void write or delete is acknowledged`() = runBlocking {
        assertTrue(Tasks.forResult<Void>(null).awaitCompletion())
    }

    @Test fun `failed write remains pending`() = runBlocking {
        assertFalse(Tasks.forException<Void>(IllegalStateException("offline")).awaitCompletion())
    }
}
