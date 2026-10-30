package app.brucia.nfc

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Advanced NFC tiles (NDEF push, masked EMV read, magic-card UID write, and the shared "advanced"
 * line of amiibo / MIFARE nested / mfkey32 / SRIX read / 125 kHz read) on four independent
 * [DeviceOperation]s. The ViewModel keeps four separate status lines that must never supersede each
 * other, so each line owns its own operation; the advanced tiles share one line like `runNfcAdv`.
 * Texts and commands mirror the firmware ACKs (src/companion/nfc.cpp) for authorized use on the
 * user's own cards. No local validation beyond the ViewModel's: the firmware is the sole validator.
 */
class NfcToolsService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val ndef = DeviceOperation(scope, runner, isConnected, NDEF_TIMEOUT_MS)
    private val emv = DeviceOperation(scope, runner, isConnected, UNBOUNDED_TIMEOUT_MS)
    private val uid = DeviceOperation(scope, runner, isConnected, UNBOUNDED_TIMEOUT_MS)
    private val adv = DeviceOperation(scope, runner, isConnected, UNBOUNDED_TIMEOUT_MS)

    /** NDEF push progress / result (shared by the NFC screen's push card and the Spam hub tile). */
    val ndefStatus: StateFlow<String> = ndef.status
    val ndefBusy: StateFlow<Boolean> = ndef.busy
    /** Masked EMV read progress / result. */
    val emvStatus: StateFlow<String> = emv.status
    val emvBusy: StateFlow<Boolean> = emv.busy
    /** Magic / UID-writable card write progress / result. */
    val uidStatus: StateFlow<String> = uid.status
    val uidBusy: StateFlow<Boolean> = uid.busy
    /** Shared advanced-tile line (amiibo / nested / mfkey32 / SRIX / 125 kHz). */
    val advStatus: StateFlow<String> = adv.status
    val advBusy: StateFlow<Boolean> = adv.busy

    /** Emulate an NDEF record (url/text/contact) on tap: `nfc_ndef_push <type> <payload>`. */
    fun pushNdef(type: String, payload: String) {
        val p = payload.trim()
        ndef.start(
            "nfc_ndef_push $type $p",
            "Pushing NDEF ($type) - tap a device...",
            { if (p.isEmpty()) "Enter a payload first." else null },
        ) { ok, out -> if (ok) out.ifBlank { "NDEF pushed." } else out.ifBlank { "NDEF push failed." } }
    }

    /** Read a masked / read-only EMV card: `nfc_emv_read`. */
    fun readEmv() = emv.start("nfc_emv_read", "Reading card - hold it on the reader...") { ok, out ->
        if (ok) out.ifBlank { "No card data." } else out.ifBlank { "EMV read failed." }
    }

    /** Write a UID onto a magic / UID-writable card: `nfc_write_uid <hexuid>`. */
    fun writeUid(hex: String) {
        val h = hex.trim().replace(" ", "")
        uid.start(
            "nfc_write_uid $h",
            "Writing UID $h...",
            { if (h.isEmpty()) "Enter a hex UID first." else null },
        ) { ok, out -> if (ok) out.ifBlank { "UID written." } else out.ifBlank { "Write failed." } }
    }

    fun amiibo(action: String) = runAdv("nfc_amiibo $action", "Amiibo ($action)", PROMPT_TIMEOUT_MS)
    fun nested() = runAdv("mifare_nested", "Nested attack", PROMPT_TIMEOUT_MS)
    fun mfkey32() = runAdv("mifare_mfkey32", "mfkey32 attack", PROMPT_TIMEOUT_MS)
    fun srixRead(secs: Int = 8) = runAdv("srix_read $secs", "SRIX read", secsTimeout(secs))
    fun rfid125Read(secs: Int = 8) = runAdv("rfid125_read $secs", "125kHz read", secsTimeout(secs))

    /** Send one advanced-tile [cmd] on the shared line, reporting its ACK against [label]. */
    private fun runAdv(cmd: String, label: String, timeoutMs: Long = UNBOUNDED_TIMEOUT_MS) =
        adv.start(cmd, "$label running...", timeoutMs = timeoutMs) { ok, out ->
            if (ok) out.ifBlank { "$label done." } else out.ifBlank { "$label failed." }
        }

    /** Stop waiting on every line; any late ACKs are ignored. */
    fun stopAll() {
        ndef.stop(); emv.stop(); uid.stop(); adv.stop()
    }

    companion object {
        /** emulate() self-bounds the NDEF target-mode window to ~60s (nfc.cpp runNfcNdefPush) + ACK margin. */
        const val NDEF_TIMEOUT_MS = 75_000L

        /** nfc_emv_read and nfc_write_uid block on the reader with no firmware bound: long guard. */
        const val UNBOUNDED_TIMEOUT_MS = 650_000L

        /** nfc_amiibo / mifare_nested / mifare_mfkey32 ACK at once (fixed "not supported headless" texts). */
        const val PROMPT_TIMEOUT_MS = 10_000L

        /** srix_read / rfid125_read run for [secs] (nfc.cpp clamps to 1..30) plus an ACK margin. */
        const val SECS_MARGIN_MS = 15_000L

        private fun secsTimeout(secs: Int) = secs.coerceIn(1, 30) * 1_000L + SECS_MARGIN_MS
    }
}
