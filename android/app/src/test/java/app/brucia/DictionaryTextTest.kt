package app.brucia

import app.brucia.wordlist.DictionaryFormatException
import app.brucia.wordlist.DictionaryTextCheck
import app.brucia.wordlist.DictionaryTextOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class DictionaryTextTest {
    private fun chunked(data: ByteArray, size: Int, check: DictionaryTextCheck = DictionaryTextCheck()): Long {
        var i = 0
        while (i < data.size) { val n = minOf(size, data.size - i); check.update(data, i, n); i += n }
        return check.finish()
    }

    @Test fun countsNonEmptyLinesLikeReadLine() {
        assertEquals(3, DictionaryTextCheck.check("a\r\nb\n\n\rc".toByteArray()))
        assertEquals(0, DictionaryTextCheck.check(ByteArray(0)))
    }

    @Test fun nulBeyondTheOldPrefixIsRejected() {
        val data = ("x".repeat(99) + "\n").repeat(30).toByteArray() + byteArrayOf(0) + "tail\n".toByteArray()
        val e = assertThrows(DictionaryFormatException::class.java) { chunked(data, 512) }
        assertEquals(true, e.message!!.contains("offset 3000"))
    }

    @Test fun lineLimitIsExactAndIndependentOfChunking() {
        assertEquals(1, chunked(ByteArray(16) { 'a'.code.toByte() }, 3, DictionaryTextCheck(16)))
        assertThrows(DictionaryFormatException::class.java) {
            chunked(ByteArray(17) { 'a'.code.toByte() }, 5, DictionaryTextCheck(16))
        }
        // A file with no newline at all fails as soon as the limit is crossed, not at the end.
        val check = DictionaryTextCheck(16)
        check.update(ByteArray(16) { 'a'.code.toByte() })
        assertThrows(DictionaryFormatException::class.java) { check.update(byteArrayOf('a'.code.toByte())) }
    }

    @Test fun webPagesArchivesAndLfsPointersAreRejectedEvenWhenShort() {
        for (bad in listOf("﻿  <!DOCTYPE html>", "<html>", "version https://git-lfs.github.com/spec/v1"))
            assertThrows(DictionaryFormatException::class.java) { DictionaryTextCheck.check(bad.toByteArray()) }
        assertThrows(DictionaryFormatException::class.java) { DictionaryTextCheck.check(byteArrayOf(0x50, 0x4b, 3, 4, 0x41)) }
        assertThrows(DictionaryFormatException::class.java) { DictionaryTextCheck.check(byteArrayOf(0x1f, 0x8b.toByte(), 0x41)) }
        // A long head arriving in small chunks is still judged at 2,048 bytes.
        val page = ("<html>" + "x\n".repeat(2000)).toByteArray()
        assertThrows(DictionaryFormatException::class.java) { chunked(page, 7) }
    }

    @Test fun outputStreamForwardsOnlyValidatedBytes() {
        val sink = ByteArrayOutputStream()
        val out = DictionaryTextOutputStream(sink)
        out.write("ok\n".toByteArray())
        assertThrows(DictionaryFormatException::class.java) { out.write(byteArrayOf('a'.code.toByte(), 0)) }
        assertArrayEquals("ok\n".toByteArray(), sink.toByteArray())
        assertEquals(1, out.check.lines)
    }
}
