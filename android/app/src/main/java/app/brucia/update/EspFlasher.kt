package app.brucia.update

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Minimal ESP32 ROM-bootloader flasher over a USB serial link.
 *
 * IMPORTANT: the USB flash path is implemented and the protocol logic is unit-tested
 * for the pure parts (see [EspProtocol] / [SlipCodec] and EspProtocolTest), but the
 * end-to-end flash has NOT yet been verified on real hardware. The reset timing and
 * the exact ROM parameter words for the T-Embed CC1101 (ESP32-S3, native USB-CDC)
 * must be confirmed on-device before this is relied upon.
 *
 * Flow: reset into the ROM download mode (DTR/RTS), SYNC, SPI_ATTACH, SPI_SET_PARAMS,
 * FLASH_BEGIN, a series of FLASH_DATA blocks (uncompressed), FLASH_END, then a hard
 * reset back into the application. Every command's response status is checked.
 */
class EspFlasher(private val io: SerialIo) {

    /** Thrown on any protocol / link failure during a flash. */
    class FlashException(message: String) : Exception(message)

    /**
     * Outcome of the post-write MD5 check. MATCH means the bytes on the chip match the
     * image we sent; MISMATCH means they do not (the write did not take); UNVERIFIED means
     * the device did not answer the MD5 command (older ROM / parse issue) - the flash still
     * completed, we just could not confirm it. The check never aborts a flash on its own.
     */
    enum class Verify { MATCH, MISMATCH, UNVERIFIED }

    /** Abstraction over the serial port so the flow can be driven in tests. */
    interface SerialIo {
        fun write(bytes: ByteArray)
        /** Read up to [buf].size bytes; returns the count (0 on timeout). */
        fun read(buf: ByteArray, timeoutMs: Int): Int
        fun setDtr(value: Boolean)
        fun setRts(value: Boolean)
        fun setBaudRate(baud: Int)
    }

    private val reader = SlipCodec.FrameReader()
    private val readBuf = ByteArray(4096)

    /**
     * Flash [image] at [offset] (default 0x0 for a merged image). [onProgress] is
     * called with (bytesWritten, totalBytes). [flashSize] is the chip flash size
     * reported to the ROM (informational for erase sizing).
     *
     * This blocks; run it off the main thread.
     */
    fun flash(
        image: ByteArray,
        offset: Int = 0x0,
        flashSize: Int = 4 * 1024 * 1024,
        doReset: Boolean = true,
        onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
    ): Verify {
        if (image.isEmpty()) throw FlashException("Empty firmware image")

        if (!connect(doReset)) throw FlashException("No response from bootloader (SYNC failed)")

        // Upload + run the esptool stub. On the ESP32-S3 USB-Serial-JTAG the ROM's own flash writes
        // ACK but do not persist (device keeps booting the old image) and SPI_FLASH_MD5 stays silent;
        // the stub's flash routines write reliably and answer MD5. Best-effort: if the stub does not
        // start we continue against the ROM (works on classic UART boards).
        try {
        val stubRunning = runStub()

        // Configure the SPI flash pins + parameters. SPI_ATTACH differs by loader: the ROM loader
        // wants an extra zero word (8 bytes), the running stub wants only the 4-byte arg - sending
        // the ROM form to the stub fails with status=1 (reported: "command 0x0D failed status=1").
        command(EspProtocol.CMD_SPI_ATTACH, EspProtocol.spiAttachPayload(romLoader = !stubRunning), timeoutMs = 3000)
        command(EspProtocol.CMD_SPI_SET_PARAMS, EspProtocol.spiSetParamsPayload(flashSize), timeoutMs = 3000)

        val blockSize = EspProtocol.FLASH_WRITE_SIZE
        val numBlocks = EspProtocol.numBlocks(image.size, blockSize)

        // Enter flash download mode; erase can take a while, so allow a long timeout.
        command(
            EspProtocol.CMD_FLASH_BEGIN,
            EspProtocol.flashBeginPayload(image.size, numBlocks, blockSize, offset, romEsp32 = true),
            timeoutMs = 30_000,
        )

        onProgress(0, image.size)
        var written = 0
        for (seq in 0 until numBlocks) {
            val start = seq * blockSize
            val end = minOf(start + blockSize, image.size)
            val block = EspProtocol.padBlock(image.copyOfRange(start, end), blockSize)
            val header = EspProtocol.flashDataHeader(block.size, seq)
            val payload = header + block
            // The packet checksum covers only the block bytes, not the header.
            command(EspProtocol.CMD_FLASH_DATA, payload, checksum = EspProtocol.checksum(block), timeoutMs = 5000)
            written = end
            onProgress(written, image.size)
        }

        // Leave the loader (do not auto-reboot here); we hard-reset explicitly below.
        command(EspProtocol.CMD_FLASH_END, EspProtocol.flashEndPayload(reboot = false), timeoutMs = 3000)

        // Confirm the bytes actually landed by asking the ROM for the MD5 of the written
        // region and comparing with the image. Best-effort: a failure here never fails the
        // flash (the write already completed), it only downgrades our confidence.
        val verify = verifyWrite(image, offset)

        hardReset()
        return verify
        } catch (e: Exception) {
            // Never leave the device dark in the loader/stub on a failed flash: reboot it into
            // firmware so it recovers on its own (no manual unplug + power-button hold needed).
            try { hardReset() } catch (e: Exception) { app.brucia.BruciaLog.w("Flash", "hard-reset after failed flash", e) }
            throw e
        }
    }

    /**
     * Ask the ROM loader for the MD5 of [image].size bytes at [offset] and compare it with
     * the MD5 of [image]. Returns [Verify.UNVERIFIED] on any protocol/parse error so a real
     * write is never reported as failed because of the check itself.
     */
    private fun verifyWrite(image: ByteArray, offset: Int): Verify = try {
        val resp = command(
            EspProtocol.CMD_SPI_FLASH_MD5,
            EspProtocol.flashMd5Payload(offset, image.size),
            timeoutMs = 30_000,
        )
        val deviceMd5 = EspProtocol.parseMd5(resp)
        if (deviceMd5 == null) {
            Verify.UNVERIFIED
        } else {
            val localMd5 = java.security.MessageDigest.getInstance("MD5").digest(image)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (deviceMd5.equals(localMd5, ignoreCase = true)) Verify.MATCH else Verify.MISMATCH
        }
    } catch (_: Exception) {
        Verify.UNVERIFIED
    }

    /**
     * Reset into download mode and SYNC. Tries the classic auto-reset first, then the
     * native USB-CDC / USB-serial-JTAG sequence used by the ESP32-S3 (T-Embed CC1101).
     */
    fun connect(doReset: Boolean = true): Boolean {
        // Native-USB ESP32-S3 (USB-Serial-JTAG): a reset to enter download mode resets the whole
        // chip, so the USB peripheral re-enumerates and the open handle dies. For that case the
        // caller resets + re-opens the re-enumerated ROM device itself and passes doReset=false; we
        // then only SYNC on the already-in-download-mode port (no reset that would kill it again).
        if (!doReset) {
            drainInput()
            repeat(SYNC_ATTEMPTS * 2) {
                if (sync() && inBootloader()) return true
                sleep(100)
            }
            return false
        }
        for (reset in listOf(::classicReset, ::usbJtagReset)) {
            reset()
            drainInput()
            repeat(SYNC_ATTEMPTS) {
                // Require BOTH a SYNC reply AND a real READ_REG: a running app can echo bytes that look
                // like a SYNC ack (false positive -> the flasher reported success without writing), but
                // only the actual ROM download loader answers the SLIP protocol for READ_REG. This makes
                // a failed bootloader entry fail honestly instead of pretending to flash.
                if (sync() && inBootloader()) return true
            }
        }
        return false
    }

    /**
     * Upload the esptool stub to RAM and run it. Returns true if the stub greeted with "OHAI".
     * Best-effort: any failure leaves the device in the ROM loader so flashing can still proceed.
     */
    private fun runStub(): Boolean = try {
        val text = java.util.Base64.getDecoder().decode(EspStub.TEXT_B64)
        val data = java.util.Base64.getDecoder().decode(EspStub.DATA_B64)
        uploadSegment(text, EspStub.TEXT_START)
        uploadSegment(data, EspStub.DATA_START)
        // MEM_END runs the stub at its entrypoint; the ROM may not reply (it jumps away), so ignore.
        val end = EspProtocol.le32(if (EspStub.ENTRY == 0L) 1L else 0L) + EspProtocol.le32(EspStub.ENTRY)
        try { command(EspProtocol.CMD_MEM_END, end, timeoutMs = 500, checkStatus = false) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "MEM_END no reply (expected): ${e.message}") }
        waitForOhai(3000)
    } catch (_: Exception) {
        false
    }

    /** Download one RAM segment [bytes] to [offset] via MEM_BEGIN + MEM_DATA blocks. */
    private fun uploadSegment(bytes: ByteArray, offset: Long) {
        val bs = EspProtocol.RAM_BLOCK
        val blocks = (bytes.size + bs - 1) / bs
        command(
            EspProtocol.CMD_MEM_BEGIN,
            EspProtocol.le32(bytes.size) + EspProtocol.le32(blocks) + EspProtocol.le32(bs) + EspProtocol.le32(offset),
            timeoutMs = 3000,
        )
        for (seq in 0 until blocks) {
            val start = seq * bs
            val end = minOf(start + bs, bytes.size)
            val block = bytes.copyOfRange(start, end)
            val header = EspProtocol.le32(block.size) + EspProtocol.le32(seq) + EspProtocol.le32(0) + EspProtocol.le32(0)
            command(EspProtocol.CMD_MEM_DATA, header + block, checksum = EspProtocol.checksum(block), timeoutMs = 3000)
        }
    }

    /** Read raw SLIP frames until one is the stub's "OHAI" greeting, or [timeoutMs] elapses. */
    private fun waitForOhai(timeoutMs: Int): Boolean {
        reader.reset()
        val deadline = (System.nanoTime() / 1_000_000L) + timeoutMs
        while ((System.nanoTime() / 1_000_000L) < deadline) {
            val n = io.read(readBuf, (deadline - (System.nanoTime() / 1_000_000L)).toInt().coerceIn(1, timeoutMs))
            for (i in 0 until n) {
                val frame = reader.offer(readBuf[i].toInt() and 0xFF) ?: continue
                if (frame.size >= 4 && frame[0].toInt() == 'O'.code && frame[1].toInt() == 'H'.code &&
                    frame[2].toInt() == 'A'.code && frame[3].toInt() == 'I'.code
                ) return true
            }
        }
        return false
    }

    /** True only if the ROM bootloader really answers (READ_REG of a ROM address succeeds). */
    private fun inBootloader(): Boolean = try {
        command(EspProtocol.CMD_READ_REG, le32(0x40001000), timeoutMs = 400)
        true
    } catch (_: Exception) {
        false
    }

    private fun le32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun sync(): Boolean {
        return try {
            val resp = command(EspProtocol.CMD_SYNC, EspProtocol.syncPayload(), timeoutMs = 300, checkStatus = false)
            resp.command == EspProtocol.CMD_SYNC
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Classic esptool auto-reset via DTR (-> GPIO0) and RTS (-> EN/CHIP_PU):
     *  DTR=0,RTS=1 (chip held in reset) -> DTR=1,RTS=0 (release with GPIO0 low ->
     *  ROM download mode) -> DTR=0. Inverted by the USB-UART driver on real boards.
     */
    private fun classicReset() {
        io.setDtr(false); io.setRts(true)  // EN low: chip in reset, GPIO0 high
        sleep(100)
        io.setDtr(true); io.setRts(false)  // GPIO0 low, EN high: boot into download mode
        sleep(50)
        io.setDtr(false)                    // release GPIO0
        sleep(50)
    }

    /**
     * Native USB (USB-serial-JTAG) reset for the ESP32-S3. The control-line toggles
     * drive the chip through the download-mode entry; the extra RTS writes work around
     * hosts that only propagate DTR when RTS is also set.
     */
    private fun usbJtagReset() {
        io.setRts(false); io.setDtr(false) // idle
        sleep(100)
        io.setDtr(true); io.setRts(false)  // set GPIO0
        sleep(100)
        io.setRts(true); io.setDtr(false)  // reset, going through (1,1)
        io.setRts(true)
        sleep(100)
        io.setDtr(false); io.setRts(false) // out of reset
        sleep(100)
    }

    /** Hard reset into the application: pulse EN (RTS) with GPIO0 (DTR) released. */
    private fun hardReset() {
        io.setDtr(false)
        io.setRts(true)   // EN low: reset
        sleep(100)
        io.setRts(false)  // EN high: run
    }

    /** Read and discard any buffered bytes so SYNC starts from a clean frame. */
    private fun drainInput() {
        reader.reset()
        val t = (System.nanoTime() / 1_000_000L)
        while ((System.nanoTime() / 1_000_000L) - t < 100) {
            if (io.read(readBuf, 20) <= 0) break
        }
        reader.reset()
    }

    /**
     * Send a command and wait for the matching response, checking the status byte
     * unless [checkStatus] is false (used by SYNC, whose status is not meaningful).
     */
    private fun command(
        command: Int,
        data: ByteArray,
        checksum: Int = 0,
        timeoutMs: Int,
        checkStatus: Boolean = true,
    ): EspProtocol.Response {
        val packet = EspProtocol.buildPacket(command, data, checksum)
        io.write(SlipCodec.encode(packet))
        val resp = readResponse(command, timeoutMs)
            ?: throw FlashException("Timed out waiting for response to command 0x%02X".format(command))
        if (checkStatus && !resp.ok) {
            throw FlashException("Command 0x%02X failed (status=%d error=%d)".format(command, resp.status, resp.error))
        }
        return resp
    }

    /** Read SLIP frames until one echoes [command], or [timeoutMs] elapses. */
    private fun readResponse(command: Int, timeoutMs: Int): EspProtocol.Response? {
        val deadline = (System.nanoTime() / 1_000_000L) + timeoutMs
        while ((System.nanoTime() / 1_000_000L) < deadline) {
            val n = io.read(readBuf, (deadline - (System.nanoTime() / 1_000_000L)).toInt().coerceIn(1, timeoutMs))
            for (i in 0 until n) {
                val frame = reader.offer(readBuf[i].toInt() and 0xFF) ?: continue
                val resp = EspProtocol.parseResponse(frame) ?: continue
                if (resp.command == command) return resp
            }
        }
        return null
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private companion object {
        const val SYNC_ATTEMPTS = 7
    }

    /** Adapter backing [SerialIo] with a usb-serial-for-android port. */
    class UsbSerialIo(private val port: UsbSerialPort) : SerialIo {
        override fun write(bytes: ByteArray) { port.write(bytes, 3000) }
        override fun read(buf: ByteArray, timeoutMs: Int): Int =
            try { port.read(buf, timeoutMs) } catch (_: Exception) { 0 }
        override fun setDtr(value: Boolean) { try { port.setDTR(value) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "setDtr: ${e.message}") } }
        override fun setRts(value: Boolean) { try { port.setRTS(value) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "setRts: ${e.message}") } }
        override fun setBaudRate(baud: Int) {
            try {
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) { app.brucia.BruciaLog.w("Flash", "setParameters failed", e) }
        }
    }
}
