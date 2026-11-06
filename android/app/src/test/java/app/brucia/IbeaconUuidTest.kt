package app.brucia

import app.brucia.ble.IbeaconUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the firmware check (src/companion/ibeacon_uuid.h, tools/test_radio_guards.py UUID_DRIVER). */
class IbeaconUuidTest {
    @Test fun canonicalOrEmptyIsAccepted() {
        assertNull(IbeaconUuid.problem(""))
        assertNull(IbeaconUuid.problem("8ec76ea3-6668-48da-9866-75be8bc86f4d"))
        assertNull(IbeaconUuid.problem("8EC76EA3-6668-48DA-9866-75BE8BC86F4D"))
    }

    @Test fun malformedIsRefused() {
        for (bad in listOf(
            "xyz", "8ec76ea3666848da986675be8bc86f4d", "8ec76ea3-6668-48da-9866-75be8bc86f4",
            "8ec76ea3-6668-48da-9866-75be8bc86f4d0", "8ec76ea3-6668-48da-9866-75be8bc86f4g",
            "8ec76ea3-6668-48da-9866_75be8bc86f4d", "8ec76ea3-6668-48da-9866-75be8bc86f4d x",
        )) {
            assertEquals(bad, IbeaconUuid.PROBLEM, IbeaconUuid.problem(bad))
        }
    }

    @Test fun viewModelChecksBeforeStartingTheLoop() {
        val vm = java.io.File("src/main/java/app/brucia/wardrive/WardriveViewModel.kt").readText()
        val start = vm.substring(vm.indexOf("fun startIbeacon("))
        val body = start.substring(0, start.indexOf("fun stopIbeacon("))
        val check = body.indexOf("IbeaconUuid.problem(u)")
        assertTrue("startIbeacon does not validate the UUID", check > 0)
        assertTrue("UUID checked after the loop starts", check < body.indexOf("ibeaconLoop.start("))
    }
}
