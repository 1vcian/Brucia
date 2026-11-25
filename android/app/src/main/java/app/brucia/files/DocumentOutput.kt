package app.brucia.files

import java.io.IOException
import java.io.OutputStream

internal fun writeDocument(bytes: ByteArray, open: () -> OutputStream?) {
    val output = open() ?: throw IOException("Document provider did not open the destination")
    output.use { it.write(bytes) }
}
