package app.brucia

import app.brucia.wifi.*
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DictionaryTestServiceTest {
    private val password = "known-pass"
    private fun target(): PmkidTarget {
        val ap = ByteArray(6) { it.toByte() }; val sta = ByteArray(6) { (it + 6).toByte() }
        return PmkidTarget(WpaCracker.pmkid(password, "Fixture", ap, sta), ap, sta, "Fixture")
    }
    // Runs on the test's virtual clock (the cracker is synchronous CPU work), except
    // cancelledOldReaderCannotOverwriteNewResult, which needs a real worker thread blocked in open().
    private suspend fun finished(s: DictionaryTestService) {
        withTimeout(3000) { while (s.status.value.running) delay(5) }
    }

    @Test(timeout = 5000) fun requiresTargetWithoutOpeningDictionary() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val s = DictionaryTestService(scope)
            s.start(null, null, 9, "fixture") { error("Must not open") }
            assertFalse(s.status.value.running)
            assertFalse(s.status.value.finished)
            assertEquals("Load a handshake or PMKID first.", s.status.value.note)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun reportsActualTriedCountOnEarlyMatchAndClosesReader() = runVirtual {
        val scope = CoroutineScope(Job() + clock)
        try {
            val closed = CountDownLatch(1)
            val s = DictionaryTestService(scope, clock)
            s.start(null, target(), 900, "fixture") {
                object : BufferedReader(StringReader("wrong-pass\n$password\nnever-tried")) {
                    override fun close() { super.close(); closed.countDown() }
                }
            }
            finished(s)
            assertEquals(password, s.status.value.result)
            assertEquals(2, s.status.value.tried)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun failureIsNotSuccessfulExhaustion() = runVirtual {
        val scope = CoroutineScope(Job() + clock)
        try {
            val s = DictionaryTestService(scope, clock)
            s.start(null, target(), 1, "fixture") { throw java.io.IOException("fixture") }
            finished(s)
            assertEquals("read error", s.status.value.note)
            assertFalse(s.status.value.finished)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun cancelledOldReaderCannotOverwriteNewResult() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        try {
            val s = DictionaryTestService(scope)
            val pm = target()
            s.start(null, pm, 1, "old") {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                object : BufferedReader(StringReader(password)) {
                    override fun close() { super.close(); closed.countDown() }
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            s.cancel()
            assertEquals("Cancelled", s.status.value.note)
            s.start(null, pm, 1, "new") { StringReader(password).buffered() }
            finished(s)
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals("new", s.status.value.note)
            assertEquals(password, s.status.value.result)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test(timeout = 5000) fun exhaustionUsesObservedCountInsteadOfStaleMetadata() = runVirtual {
        val scope = CoroutineScope(Job() + clock)
        try {
            val s = DictionaryTestService(scope, clock)
            s.start(null, target(), 900, "fixture") { StringReader("wrong-pass").buffered() }
            finished(s)
            assertTrue(s.status.value.finished)
            assertNull(s.status.value.result)
            assertEquals(1, s.status.value.tried)
            assertEquals(1, s.status.value.total)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun handshakePathUsesExistingVerifier() = runVirtual {
        val scope = CoroutineScope(Job() + clock)
        try {
            val base = Handshake("Fixture", ByteArray(6), ByteArray(6) { 1 },
                ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(99), ByteArray(16), 2)
            val h = base.copy(mic = WpaCracker.computeMic(password, base))
            val s = DictionaryTestService(scope, clock)
            s.start(h, null, 1, "fixture") { StringReader(password).buffered() }
            finished(s)
            assertEquals(password, s.status.value.result)
            assertEquals(1, s.status.value.tried)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun cancelledParentDoesNotLeaveRunningState() = runVirtual {
        val scope = CoroutineScope(Job() + clock)
        scope.cancel()
        val s = DictionaryTestService(scope, clock)
        s.start(null, target(), 1, "fixture") { error("Must not open") }
        finished(s)
        assertEquals("Cancelled", s.status.value.note)
    }
}
