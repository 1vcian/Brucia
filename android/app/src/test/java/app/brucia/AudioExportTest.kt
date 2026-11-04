package app.brucia

import app.brucia.audio.AudioExport
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AudioExportTest {
    private fun tempRoot(): File = Files.createTempDirectory("recordings").toFile()

    @Test fun exportInTheSameSecondNeverOverwritesAnEarlierSavedRecording() {
        val root = tempRoot()
        try {
            val first = AudioExport.saveInto(root, "20261104_120000", byteArrayOf(1, 2), 16000) { _, _ -> emptyList() }
            val firstWav = first.files.single().readBytes()
            val second = AudioExport.saveInto(root, "20261104_120000", byteArrayOf(3, 4, 5, 6), 16000) { _, _ -> emptyList() }
            assertNotEquals(first.dir, second.dir)
            assertArrayEquals(firstWav, first.files.single().readBytes())
            assertEquals(44 + 4, second.files.single().length())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedExportLeavesNoPartialRecordingFolderBehind() {
        val root = tempRoot()
        try {
            try {
                AudioExport.saveInto(root, "20261104_120000", byteArrayOf(1, 2), 16000) { dir, _ ->
                    File(dir, "waveform.png").writeBytes(byteArrayOf(9))
                    throw IOException("disk full")
                }
                fail("export failure must propagate so the buffer keeps the audio")
            } catch (_: IOException) {}
            assertEquals(emptyList<String>(), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun wavHeaderDescribesExactlyTheCapturedPcm() {
        val root = tempRoot()
        try {
            val wav = AudioExport.saveInto(root, "s", byteArrayOf(1, 2, 3, 4), 16000) { _, _ -> emptyList() }
                .files.single().readBytes()
            fun le32(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8) or
                ((wav[o + 2].toInt() and 0xFF) shl 16) or ((wav[o + 3].toInt() and 0xFF) shl 24)
            assertEquals(48, wav.size)
            assertEquals(40, le32(4))
            assertEquals(16000, le32(24))
            assertEquals(4, le32(40))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), wav.copyOfRange(44, 48))
        } finally { root.deleteRecursively() }
    }
}
