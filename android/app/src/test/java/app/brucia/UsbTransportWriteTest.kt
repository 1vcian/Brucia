package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.session.CommandSession
import app.brucia.transport.UsbTransport
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Real UsbTransport, with an already-open driver interface; no USB hardware or Android mocks. */
class UsbTransportWriteTest {
    private class Driver {
        var writes = 0
        var failure: IOException? = null
        var open = true
        val port = Proxy.newProxyInstance(UsbSerialPort::class.java.classLoader,
            arrayOf(UsbSerialPort::class.java)) { _, method, _ ->
            when (method.name) {
                "isOpen" -> open
                "write" -> { writes++; failure?.let { throw it }; null }
                "close" -> { open = false; null }
                "toString" -> "Test USB driver"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as UsbSerialPort
    }

    @Test fun driverFailurePropagatesAndPreventsAnotherWrite() {
        val driver = Driver().apply { failure = IOException("device removed") }
        val pipe = UsbTransport(driver.port)
        val error = runCatching { pipe.write(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertSame(driver.failure, error?.cause)
        assertFalse(pipe.isOpen)
        assertTrue(runCatching { pipe.write(byteArrayOf(2)) }.isFailure)
        assertEquals(1, driver.writes)
    }

    @Test fun closedDriverIsNotWritten() {
        val driver = Driver().apply { open = false }
        assertTrue(runCatching { UsbTransport(driver.port).write(byteArrayOf(1)) }.isFailure)
        assertEquals(0, driver.writes)
    }

    @Test fun failedUsbWriteCompletesPendingCommandWithoutTimeoutOrSentCounter() = runVirtual {
        val driver = Driver().apply { failure = IOException("device removed") }
        val pipe = UsbTransport(driver.port)
        val client = CompanionClient(pipe)
        val session = CommandSession(client, pipe)
        val result = session.await("info", 650_000)
        assertFalse(result.first)
        assertTrue(result.second.contains("device removed"))
        assertFalse(session.hasPending)
        assertEquals(0L, client.counters.snapshot().timeouts)
        assertEquals(0L, client.counters.snapshot().framesSent)
    }
}
