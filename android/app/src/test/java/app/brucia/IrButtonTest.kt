package app.brucia

import app.brucia.ir.IrButton
import app.brucia.ir.IrParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the fixed-contract raw-replay mapping: an IR button (parsed or learned)
 * must turn into `ir_tx_raw <khz> <csv durations>` args the firmware expects.
 */
class IrButtonTest {

    @Test fun rawArgsConvertHzToKhzAndSpacesToCsv() {
        val b = IrButton(
            name = "Raw",
            protocol = "",
            address = "",
            command = "",
            raw = true,
            frequency = 38000,
            data = "100 200 300 400",
        )
        val (khz, csv) = b.toTxRawArgs()
        assertEquals(38, khz)
        assertEquals("100,200,300,400", csv)
    }

    @Test fun rawArgsRoundFrequencyAndClampAtLeastOne() {
        assertEquals(38, IrButton("x", "", "", "", raw = true, frequency = 37900, data = "1 2").toTxRawArgs().first)
        assertEquals(1, IrButton("x", "", "", "", raw = true, frequency = 0, data = "1").toTxRawArgs().first)
    }

    @Test fun parsedRawButtonReplaysThroughTxRaw() {
        val parsed = IrParser.parse(
            """
            name: Power
            type: raw
            frequency: 38000
            data: 9000 4500 560 560
            """.trimIndent()
        )
        assertEquals(1, parsed.size)
        val (khz, csv) = parsed[0].toTxRawArgs()
        assertEquals(38, khz)
        assertEquals("9000,4500,560,560", csv)
    }
}
