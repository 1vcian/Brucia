package app.brucia.wordlist

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/** A dictionary byte stream that is not plain line-oriented text. */
class DictionaryFormatException(message: String) : IOException(message)

/**
 * Incremental plain-text check shared by dictionary downloads and personal imports.
 *
 * Every byte is inspected as it is written, so validation covers the whole file, not a
 * prefix, and needs bounded memory: a NUL anywhere, a line longer than [maxLineBytes],
 * or a web page / Git LFS pointer / ZIP / GZIP start throws [DictionaryFormatException].
 * Lines end at `\n` or `\r` (as [java.io.BufferedReader.readLine]); [lines] counts the
 * non-empty ones. Call [finish] after the last byte: short inputs are judged there.
 * Not thread-safe; one instance per stream.
 */
class DictionaryTextCheck(private val maxLineBytes: Int = MAX_LINE_BYTES) {
    private val head = ByteArrayOutputStream(HEAD_BYTES)
    private var headChecked = false
    private var lineBytes = 0
    private var finished = false

    /** Bytes inspected so far. */
    var bytes = 0L
        private set

    /** Non-empty lines seen so far (complete after [finish]). */
    var lines = 0L
        private set

    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        check(!finished) { "finish() already called" }
        if (!headChecked) {
            head.write(buffer, offset, minOf(length, HEAD_BYTES - head.size()))
            if (head.size() >= HEAD_BYTES) checkHead()
        }
        for (i in offset until offset + length) {
            when (buffer[i]) {
                ZERO -> throw DictionaryFormatException(
                    "Expected a plain-text dictionary: NUL byte at offset ${bytes + (i - offset)}")
                LF, CR -> { if (lineBytes > 0) lines++; lineBytes = 0 }
                else -> if (++lineBytes > maxLineBytes) throw DictionaryFormatException(
                    "Line ${lines + 1} is longer than $maxLineBytes bytes; not a line-per-entry dictionary")
            }
        }
        bytes += length
    }

    /** Completes the check; returns the number of non-empty lines. */
    fun finish(): Long {
        if (!finished) {
            if (!headChecked) checkHead()
            if (lineBytes > 0) lines++
            lineBytes = 0
            finished = true
        }
        return lines
    }

    private fun checkHead() {
        headChecked = true
        val b = head.toByteArray()
        val zip = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        val gzip = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()
        val text = b.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (zip || gzip || text.startsWith("version https://git-lfs") ||
            WEB.any { text.startsWith(it, ignoreCase = true) })
            throw DictionaryFormatException("Expected a plain-text dictionary, not a web page or archive")
    }

    companion object {
        /** Longest accepted line. WPA passphrases are at most 63 bytes; MIFARE lines carry comments. */
        const val MAX_LINE_BYTES = 4096
        const val HEAD_BYTES = 2048
        private const val ZERO: Byte = 0
        private const val LF: Byte = '\n'.code.toByte()
        private const val CR: Byte = '\r'.code.toByte()
        private val WEB = listOf("<!doctype", "<html", "<head", "<body")

        /** Validates a complete in-memory dictionary; returns its non-empty line count. */
        fun check(bytes: ByteArray, maxLineBytes: Int = MAX_LINE_BYTES): Long =
            DictionaryTextCheck(maxLineBytes).apply { update(bytes) }.finish()
    }
}

/**
 * Validates bytes before forwarding them to [out]. Invalid data throws before it is
 * written. [close] does not call [DictionaryTextCheck.finish]; callers do that explicitly
 * before promoting the output, so an interrupted copy is never judged complete.
 */
class DictionaryTextOutputStream(out: OutputStream, val check: DictionaryTextCheck = DictionaryTextCheck()) :
    FilterOutputStream(out) {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        check.update(b, off, len)
        out.write(b, off, len)
    }
}
