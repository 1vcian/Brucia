package app.brucia

import app.brucia.session.DeviceOperation
import app.brucia.wardrive.GeoReplayService
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geo-signal replay reports what the device answered, not an optimistic "Replayed". */
class GeoReplayServiceTest {
    private val runner = GateRunner()

    @Test fun successIsReportedOnlyAfterTheDeviceAcks() = runVirtual {
        val svc = GeoReplayService(this, runner, { true })
        svc.replay("Gate", "rf tx ABC 433920000 350 10")
        settle()
        assertEquals("Replaying \"Gate\"...", svc.status.value)
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Replayed \"Gate\".", svc.status.value)
        assertEquals(listOf("rf tx ABC 433920000 350 10"), runner.cmds)
    }

    @Test fun deviceRejectionIsShownAsAFailure() = runVirtual {
        val svc = GeoReplayService(this, runner, { true })
        runner.auto = false to "CC1101 not found"
        svc.replay("Gate", "rf tx ABC 433920000 350 10")
        until { !svc.busy.value }
        assertEquals("Replay of \"Gate\" failed: CC1101 not found", svc.status.value)
        runner.auto = false to ""
        svc.replay("Gate", "rf tx ABC 433920000 350 10")
        until { !svc.busy.value }
        assertEquals("Replay of \"Gate\" failed: no response", svc.status.value)
    }

    @Test fun notConnectedOrEmptyPayloadSendsNothing() = runVirtual {
        var connected = false
        val svc = GeoReplayService(this, runner, { connected })
        svc.replay("Gate", "rf tx 1")
        assertEquals(DeviceOperation.NOT_CONNECTED, svc.status.value)
        connected = true
        svc.replay("Empty", "")
        assertEquals(GeoReplayService.NOTHING, svc.status.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun irPayloadsUseTheSavedSignalMapping() {
        assertEquals("rf tx 433920000 0x1 24", GeoReplayService.command("rf tx 433920000 0x1 24"))
        assertEquals("ir tx nec 0x04 0x08", GeoReplayService.command("ir tx nec 0x04 0x08"))
    }
}
