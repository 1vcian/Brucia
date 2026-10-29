package app.brucia

import app.brucia.ir.IrButton
import app.brucia.ir.IrRemoteMap
import app.brucia.ir.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the fuzzy name match that turns a catalog device's messy button names into
 * the standard on-screen remote functions. The names below are taken from the real
 * Flipper-IRDB catalog (Vol_up, Ch_next, Ok, ...) plus the common variants.
 */
class IrRemoteMapTest {

    private fun key(name: String) = IrRemoteMap.keyFor(name)

    @Test fun powerVariants() {
        for (n in listOf("Power", "POWER", "pwr", "Power_off", "On_Off", "on/off", "Standby")) {
            assertEquals("'$n' -> POWER", RemoteKey.POWER, key(n))
        }
    }

    @Test fun volumeDirectionsIncludingSymbols() {
        for (n in listOf("Vol_up", "VOL+", "Volume_Up", "V+", "vol +")) {
            assertEquals("'$n' -> VOL_UP", RemoteKey.VOL_UP, key(n))
        }
        for (n in listOf("Vol_dn", "VOL-", "Volume_Down", "V-", "vol -")) {
            assertEquals("'$n' -> VOL_DOWN", RemoteKey.VOL_DOWN, key(n))
        }
    }

    @Test fun channelDirectionsIncludingNextPrevAndSymbols() {
        for (n in listOf("Ch_next", "CH+", "Channel_Up", "Prog+", "ch up")) {
            assertEquals("'$n' -> CH_UP", RemoteKey.CH_UP, key(n))
        }
        for (n in listOf("Ch_prev", "CH-", "Channel_Down", "Prog-", "ch down")) {
            assertEquals("'$n' -> CH_DOWN", RemoteKey.CH_DOWN, key(n))
        }
    }

    @Test fun muteAndInput() {
        assertEquals(RemoteKey.MUTE, key("Mute"))
        assertEquals(RemoteKey.MUTE, key("MUTE"))
        for (n in listOf("Input", "Source", "AV", "Input_Source")) {
            assertEquals("'$n' -> INPUT", RemoteKey.INPUT, key(n))
        }
    }

    @Test fun okAndArrowsDoNotCollideWithVolume() {
        for (n in listOf("Ok", "OK", "Enter", "Select")) {
            assertEquals("'$n' -> OK", RemoteKey.OK, key(n))
        }
        assertEquals(RemoteKey.UP, key("Up"))
        assertEquals(RemoteKey.DOWN, key("Down"))
        assertEquals(RemoteKey.LEFT, key("Left"))
        assertEquals(RemoteKey.RIGHT, key("Right"))
        assertEquals(RemoteKey.UP, key("Arrow_Up"))
        // The plain UP arrow must never swallow the volume/channel rocker names.
        assertEquals(RemoteKey.VOL_UP, key("Vol_up"))
        assertEquals(RemoteKey.CH_UP, key("Ch_next"))
    }

    @Test fun navigationKeys() {
        assertEquals(RemoteKey.MENU, key("Menu"))
        assertEquals(RemoteKey.HOME, key("Home"))
        for (n in listOf("Back", "Return", "Exit")) {
            assertEquals("'$n' -> BACK", RemoteKey.BACK, key(n))
        }
    }

    @Test fun numberPad() {
        assertEquals(RemoteKey.NUM_0, key("0"))
        assertEquals(RemoteKey.NUM_5, key("5"))
        assertEquals(RemoteKey.NUM_9, key("9"))
        assertEquals(RemoteKey.NUM_1, key("num1"))
        assertEquals(RemoteKey.NUM_7, key("Number_7"))
        assertEquals(RemoteKey.NUM_3, key("Key3"))
    }

    @Test fun unknownNamesReturnNull() {
        for (n in listOf("Netflix", "Red", "Subtitle", "Ch_list", "", "   ")) {
            assertNull("'$n' should not map", key(n))
        }
    }

    @Test fun mapRemoteTakesFirstMatchAndCoversTypicalTv() {
        val buttons = listOf(
            IrButton("Power", "NEC", "04", "08"),
            IrButton("Power", "NEC", "04", "FF"), // duplicate: first wins
            IrButton("Vol_up", "NEC", "04", "02"),
            IrButton("Vol_dn", "NEC", "04", "03"),
            IrButton("Ch_next", "NEC", "04", "04"),
            IrButton("Ch_prev", "NEC", "04", "05"),
            IrButton("Mute", "NEC", "04", "09"),
            IrButton("Ok", "NEC", "04", "0A"),
            IrButton("Up", "NEC", "04", "0B"),
            IrButton("Down", "NEC", "04", "0C"),
            IrButton("Left", "NEC", "04", "0D"),
            IrButton("Right", "NEC", "04", "0E"),
            IrButton("1", "NEC", "04", "01"),
            IrButton("Source", "NEC", "04", "0F"),
            IrButton("Menu", "NEC", "04", "10"),
            IrButton("Back", "NEC", "04", "11"),
            IrButton("Netflix", "NEC", "04", "FE"), // unmapped
        )
        val map = IrRemoteMap.mapRemote(buttons)
        assertEquals("08", map[RemoteKey.POWER]?.command) // first Power wins
        assertEquals(RemoteKey.VOL_UP, RemoteKey.VOL_UP.takeIf { map.containsKey(it) })
        assertTrue(map.containsKey(RemoteKey.CH_UP))
        assertTrue(map.containsKey(RemoteKey.NUM_1))
        assertEquals(RemoteKey.INPUT, RemoteKey.INPUT.takeIf { map.containsKey(it) })
        assertNull(map[RemoteKey.HOME]) // not present in this device
        // Netflix contributes nothing.
        assertEquals(15, map.size)
    }
}
