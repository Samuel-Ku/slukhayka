package com.slukhayka.audiobooks.data.reviews

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regression coverage for the Firebase Task bridge used by review writes. */
class FirestoreReviewWriteTaskTest {

    @Test
    fun `never completing Firebase write is queued locally without waiting`() = runBlocking {
        val source = TaskCompletionSource<Void?>()

        val receipt = source.task.toReviewWriteReceipt()

        assertTrue(receipt is ReviewWriteReceipt.Queued)
        val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
            (receipt as ReviewWriteReceipt.Queued).awaitRemote()
        }
        yield()
        assertFalse(acknowledgement.isCompleted)

        acknowledgement.cancel()
        assertTrue(acknowledgement.isCancelled)
    }

    @Test
    fun `completed Firebase write reports its actual success or failure`() = runBlocking {
        val success = TaskCompletionSource<Void?>().apply { setResult(null) }
        val failure = TaskCompletionSource<Void?>().apply {
            setException(IllegalStateException("backend rejected write"))
        }

        assertTrue(success.task.awaitReviewWriteResult())
        assertFalse(failure.task.awaitReviewWriteResult())
    }

    @Test
    fun `caller cancellation propagates while Firebase write is pending`() = runBlocking {
        val source = TaskCompletionSource<Void?>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            source.task.awaitReviewWriteResult()
        }

        result.cancel()

        try {
            result.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Caller cancellation remains cancellation, never false/success.
        }
        source.setResult(null)
        assertTrue(result.isCancelled)
    }
}
