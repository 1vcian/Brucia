package app.brucia

import app.brucia.ir.IrButton
import app.brucia.ir.IrParser
import org.junit.Assert.*
import org.junit.Test

/** The companion `ir_rx` ACK is a one-line summary, not a `.ir` block (see infrared.cpp runIrRx). */
class IrRxSummaryTest {
    @Test fun decodedSummaryKeepsIrFieldNormalization() {
        assertEquals(
            IrButton("NEC 08 00 00 00", "NEC", "04000000", "08000000"),
            IrParser.parseRxSummary("NEC addr=04 00 00 00 cmd=08 00 00 00"),
        )
        val withValue = IrParser.parseRxSummary("Samsung32 addr=07 00 00 00 cmd=02 00 00 00 (raw: 0xE0E040BF)")
        assertEquals("Samsung32" to ("07000000" to "02000000"), withValue?.protocol to (withValue?.address to withValue?.command))
    }

    @Test fun rawSummaryBecomesRawButton() {
        val b = IrParser.parseRxSummary("raw: 9000 4500 560 560 560 1690")
        assertEquals(true to "9000 4500 560 560 560 1690", b?.raw to b?.data)
        assertEquals(38000, b?.frequency)
    }

    @Test fun anythingElseIsNotGuessed() {
        listOf("", "no IR signal", "raw:", "raw: 9000 abc", "NEC addr=0x04 cmd=0x08", "NEC addr= cmd=08",
            "NEC addr=04 00 00 00 00 cmd=08").forEach { assertNull(it, IrParser.parseRxSummary(it)) }
    }
}
