package app.brucia

import app.brucia.update.AssetIntegrity
import app.brucia.update.IntegrityException
import app.brucia.wordlist.DictionaryDownload
import app.brucia.wordlist.DictionaryFormatException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DictionaryDownloadTest {
    private class Connection(val data: ByteArray, val expected: Long = data.size.toLong(), val code: Int = 200,
                             val location: String? = null) : HttpURLConnection(URL("https://example.com/list")) {
        var closed = false
        override fun getInputStream() = data.inputStream()
        override fun getContentLengthLong() = expected
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
    }

    @Test fun failedDownloadsPreservePreviousFileAndRemovePartial() {
        val dir = Files.createTempDirectory("dictionary-test").toFile()
        try {
            val dest = File(dir, "list.txt")
            for (conn in listOf(
                Connection("short".toByteArray(), 100), Connection(ByteArray(200), 200),
                Connection("<html>error</html>".toByteArray()), Connection(byteArrayOf(0x50, 0x4b, 3, 4)),
                Connection(ByteArray(0)), Connection("error".toByteArray(), code = 404),
            )) {
                dest.writeText("previous-password")
                assertThrows(IOException::class.java) {
                    DictionaryDownload.store(dest, "https://example.com/list", 100, { false }, { _, _ -> }, { conn })
                }
                assertEquals("previous-password", dest.readText())
                assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
                assertTrue(conn.closed)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun successReplacesOnlyAfterCompleteCopy() {
        val dir = Files.createTempDirectory("dictionary-test").toFile()
        try {
            val dest = File(dir, "list.txt").apply { writeText("old") }
            val conn = Connection("new-password\n".toByteArray())
            DictionaryDownload.store(dest, "https://example.com/list", 100, { false }, { _, _ ->
                assertEquals("old", dest.readText())
            }, { conn })
            assertEquals("new-password\n", dest.readText())
            assertTrue(conn.closed)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationDoesNotReplaceDictionary() {
        val dir = Files.createTempDirectory("dictionary-test").toFile()
        try {
            val dest = File(dir, "list.txt").apply { writeText("old") }
            val conn = Connection("new-password".toByteArray())
            assertThrows(InterruptedException::class.java) {
                DictionaryDownload.store(dest, "https://example.com/list", 100, { true }, { _, _ -> }, { conn })
            }
            assertEquals("old", dest.readText())
            assertTrue(conn.closed)
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun readRejectsDowngradeAndExcessiveOrTruncatedBody() {
        val redirect = Connection(ByteArray(0), code = 302, location = "http://example.com/plain")
        assertThrows(IOException::class.java) {
            DictionaryDownload.read("https://example.com/list", 100, connect = { redirect })
        }
        assertTrue(redirect.closed)
        for (conn in listOf(Connection(ByteArray(101)), Connection("short".toByteArray(), 10))) {
            assertThrows(IOException::class.java) {
                DictionaryDownload.read("https://example.com/list", 100, connect = { conn })
            }
            assertTrue(conn.closed)
        }
    }

    private fun withDir(body: (File) -> Unit) {
        val dir = Files.createTempDirectory("dictionary-test").toFile()
        try { body(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun wholeFileIsValidatedNotOnlyThePrefix() = withDir { dir ->
        val dest = File(dir, "list.txt")
        val text = "valid-password\n".repeat(200).toByteArray() // 3,000 bytes, past the old 2,048 prefix
        for (body in listOf(text + byteArrayOf(0) + "bad\n".toByteArray(),
                            text + ByteArray(5000) { 'a'.code.toByte() } + "\n".toByteArray())) {
            dest.writeText("previous-password")
            val conn = Connection(body)
            assertThrows(DictionaryFormatException::class.java) {
                DictionaryDownload.store(dest, "https://example.com/list", 100_000, { false }, { _, _ -> }, { conn })
            }
            assertEquals("previous-password", dest.readText())
            assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
            assertTrue(conn.closed)
            assertThrows(DictionaryFormatException::class.java) {
                DictionaryDownload.read("https://example.com/list", 100_000, connect = { Connection(body) })
            }
        }
    }

    @Test fun pinnedDownloadRejectsSameLengthAlterationAndKeepsPreviousList() = withDir { dir ->
        val good = "password1\npassword2\n".toByteArray()
        val bad = "password1\npassword3\n".toByteArray()
        val pin = AssetIntegrity(good.size.toLong(), AssetIntegrity.hex(AssetIntegrity.sha256Of(good)))
        val dest = File(dir, "list.txt").apply { writeText("previous-password") }
        for (conn in listOf(Connection(bad), Connection(good + "x\n".toByteArray()),
                            Connection(good.copyOf(good.size - 1), expected = -1))) {
            assertThrows(IntegrityException::class.java) {
                DictionaryDownload.store(dest, "https://example.com/list", 100, { false }, { _, _ -> }, { conn },
                    integrity = pin)
            }
            assertEquals("previous-password", dest.readText())
            assertEquals(listOf("list.txt"), dir.listFiles()!!.map { it.name })
            assertTrue(conn.closed)
        }
        assertThrows(IntegrityException::class.java) {
            DictionaryDownload.read("https://example.com/list", 100, connect = { Connection(bad) }, integrity = pin)
        }
        DictionaryDownload.store(dest, "https://example.com/list", 100, { false }, { _, _ -> }, { Connection(good) },
            integrity = pin)
        assertArrayEquals(good, dest.readBytes())
        assertArrayEquals(good, DictionaryDownload.read("https://example.com/list", 100,
            connect = { Connection(good) }, integrity = pin))
    }
}
