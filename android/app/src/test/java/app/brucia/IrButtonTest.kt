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

    /**
     * Exact quoted form; tools/test_ir_cli_tokenizer.py runs these same literals through SimpleCLI's
     * tokenizer (real C sources when .pio/libdeps is present, a cited port otherwise).
     */
    @Test fun liteCliFormTurnsKhzCsvBackIntoOneQuotedSamplesWord() {
        assertEquals("ir tx_raw 38000 \"9000 4500 560 560\"", IrButton.cliTxRaw(38, "9000,4500, 560 560"))
        val (khz, csv) = IrButton("x", "", "", "", raw = true, frequency = 38000, data = "100 200").toTxRawArgs()
        assertEquals("ir tx_raw 38000 \"100 200\"", IrButton.cliTxRaw(khz, csv))
        // Saved/learned raw buttons replay through the same quoted form (multi-space data normalized).
        assertEquals("ir tx_raw 36000 \"9000 4500 560\"",
            IrButton("x", "", "", "", raw = true, frequency = 36000, data = " 9000  4500\n560 ").toCli())
    }

    @Test fun legacySavedRawPayloadsAreQuotedAtReplayOthersUntouched() {
        // Saved before the quoting fix: rejected by Bruce's CLI as is.
        assertEquals("ir tx_raw 38000 \"9000 4500 560 560\"", IrButton.replayable("ir tx_raw 38000 9000 4500 560 560"))
        assertEquals("ir tx_raw 36000 \"100 200\"", IrButton.replayable("  ir tx_raw 36000 100,200 \n"))
        // Already quoted, parsed `ir tx`, a lone sample and non-IR payloads stay byte-identical.
        for (p in listOf("ir tx_raw 38000 \"9000 4500\"", "ir tx NEC 04000000 08000000", "ir tx_raw 38000 9000",
            "subghz tx 1A2B 433920000 350 5", "ir tx_raw", "ir tx_raw abc 1 2")) {
            assertEquals(p, IrButton.replayable(p))
        }
        // Idempotent on the new form.
        val quoted = IrButton.replayable("ir tx_raw 38000 1 2 3")
        assertEquals(quoted, IrButton.replayable(quoted))
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
