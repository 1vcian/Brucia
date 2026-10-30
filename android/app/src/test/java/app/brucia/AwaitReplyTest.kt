package app.brucia

import app.brucia.protocol.awaitReply
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AwaitReplyTest {
    @Test fun callbackIsRegisteredBeforeImmediateResponseAndRemovedAfterCompletion() = runBlocking {
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>()
        val result = awaitReply(pending, 101, 100, "timeout") {
            pending.getValue(101)(true, "done")
        }
        assertEquals(true to "done", result)
        assertTrue(pending.isEmpty())
    }

    @Test fun timeoutAndCancellationReleaseTheCallback() = runBlocking {
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>()
        assertEquals(false to "timeout", awaitReply(pending, 101, 20, "timeout") {})
        assertTrue(pending.isEmpty())
        val request = async(start = CoroutineStart.UNDISPATCHED) { awaitReply(pending, 102, 1000, "timeout") {} }
        assertTrue(pending.containsKey(102))
        request.cancel(); request.join()
        assertTrue(pending.isEmpty())
    }

    @Test fun sendFailureDoesNotLeakRegisteredCallback() = runBlocking {
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>()
        try {
            awaitReply(pending, 101, 100, "timeout") { throw IllegalStateException("closed") }
            fail("Send error ignored")
        } catch (_: IllegalStateException) { }
        assertTrue(pending.isEmpty())
    }
}
