package app.brucia

import app.brucia.update.DownloadIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadIOTest {
    @Test fun copiesExactOrUnknownSizeAndReportsCompletion() {
        for (expected in listOf(3L, -1L)) {
            val out = ByteArrayOutputStream()
            var reported = 0L
            assertEquals(3L, DownloadIO.copy(ByteArrayInputStream("abc".toByteArray()), out,
                expected, 10, { false }, { done, _ -> reported = done }))
            assertEquals("abc", out.toString("UTF-8"))
            assertEquals(3L, reported)
        }
    }

    @Test fun rejectsEmptyTruncatedOversizedAndWrongLengthBodies() {
        for ((body, expected, limit) in listOf(
            Triple("", 0L, 10L), Triple("ab", 3L, 10L), Triple("abcd", 3L, 10L),
            Triple("abcd", -1L, 3L), Triple("abcd", 4L, 3L))) {
            try {
                DownloadIO.copy(ByteArrayInputStream(body.toByteArray()), ByteArrayOutputStream(),
                    expected, limit, { false }, { _, _ -> })
                fail("Accepted invalid download: $body / $expected / $limit")
            } catch (_: IOException) { }
        }
    }

    @Test fun cancellationIsHonoredBeforeReadingAndBeforeSuccess() {
        for (cancelAfter in listOf(0, 1)) {
            var checks = 0
            try {
                DownloadIO.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(),
                    1, 10, { checks++ >= cancelAfter }, { _, _ -> })
                fail("Cancellation ignored")
            } catch (_: InterruptedException) { }
        }
    }

    @Test fun promotionReplacesOnlyWithNonemptyCompletedFile() {
        val dir = Files.createTempDirectory("brucia-download-test").toFile()
        try {
            val destination = dir.resolve("app.apk").apply { writeText("old") }
            val partial = dir.resolve("app.part").apply { writeText("") }
            try { DownloadIO.promote(partial, destination); fail("Promoted empty download") } catch (_: IOException) { }
            assertEquals("old", destination.readText())
            partial.writeText("new")
            DownloadIO.promote(partial, destination)
            assertEquals("new", destination.readText())
            assertFalse(partial.exists())
        } finally { dir.deleteRecursively() }
    }
}
