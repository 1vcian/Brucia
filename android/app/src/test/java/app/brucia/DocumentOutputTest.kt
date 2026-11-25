package app.brucia

import app.brucia.files.writeDocument
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentOutputTest {
    @Test fun missingStreamAndWriteOrCloseErrorsReachCaller() {
        val bytes = byteArrayOf(1)
        assertThrows(IOException::class.java) { writeDocument(bytes) { null } }
        for (failWrite in listOf(true, false)) {
            assertThrows(IOException::class.java) {
                writeDocument(bytes) {
                    object : OutputStream() {
                        override fun write(value: Int) { if (failWrite) throw IOException("write failed") }
                        override fun close() { if (!failWrite) throw IOException("close failed") }
                    }
                }
            }
        }
    }
}
