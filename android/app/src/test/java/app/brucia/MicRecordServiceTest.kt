package app.brucia

import app.brucia.audio.MicRecordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MicRecordServiceTest {
    private val runner = GateRunner()

    private fun CoroutineScope.svc(connected: Boolean = true) = MicRecordService(this, runner, { connected })
    private fun MicRecordService.state() = status.value to busy.value

    @Test fun recordTextsCommandsAndTimeouts() = runVirtual {
        val svc = svc()
        svc.record(5)
        assertEquals("Recording 5s..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Recording saved.", svc.status.value)
        runner.auto = true to "/recordings/rec_1.wav"
        svc.record(45)
        until { svc.status.value == "/recordings/rec_1.wav" && !svc.busy.value }
        runner.auto = false to ""
        svc.record(30)
        until { svc.status.value == "Recording failed." }
        runner.auto = false to "no storage"
        svc.record(1)
        until { svc.status.value == "no storage" && !svc.busy.value }
        assertEquals(listOf("mic_record 5" to 15_000L, "mic_record 45" to 40_000L,
            "mic_record 30" to 40_000L, "mic_record 1" to 11_000L), runner.calls.toList())
    }

    @Test fun localRejectionsAreNotSent() = runVirtual {
        val svc = svc()
        for (secs in listOf(0, -3)) {
            svc.record(secs)
            assertEquals("Enter a duration in seconds." to false, svc.state())
        }
        val off = svc(connected = false)
        off.record(5)
        assertEquals("Connect the device over USB first." to false, off.state())
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun stopIgnoresLateReply() = runVirtual {
        runner.sticky = true
        val svc = svc()
        svc.record(10)
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "/recordings/late.wav")
        delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
