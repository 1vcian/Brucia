package app.brucia

import app.brucia.update.UpdateHttp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class UpdateHttpTest {
    private class Connection(url: URL, private val code: Int, private val location: String? = null) : HttpURLConnection(url) {
        var closed = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() { }
    }

    @Test fun followsRelativeRedirectAndClosesOnlyIntermediateConnection() {
        val opened = mutableListOf<Connection>()
        val result = UpdateHttp.follow(URL("https://example.com/start")) { url ->
            Connection(url, if (opened.isEmpty()) 302 else 200, "/asset").also { opened.add(it) }
        }
        assertEquals("https://example.com/asset", result.url.toString())
        assertTrue(opened[0].closed)
        assertFalse(opened[1].closed)
        result.disconnect()
    }

    @Test fun refusesDowngradeCredentialsAndMissingLocation() {
        for (location in listOf(null, "http://example.com/asset", "https://secret@example.com/asset")) {
            val opened = mutableListOf<Connection>()
            try {
                UpdateHttp.follow(URL("https://example.com/start")) { url ->
                    Connection(url, 302, location).also { opened.add(it) }
                }
                fail("Invalid redirect accepted")
            } catch (_: IOException) { }
            assertEquals(1, opened.size)
            assertTrue(opened[0].closed)
        }
    }

    @Test fun boundsRedirectLoopsAndClosesEveryConnection() {
        val opened = mutableListOf<Connection>()
        try {
            UpdateHttp.follow(URL("https://example.com/start")) { url ->
                Connection(url, 307, "/start").also { opened.add(it) }
            }
            fail("Redirect loop accepted")
        } catch (_: IOException) { }
        assertEquals(6, opened.size)
        assertTrue(opened.all { it.closed })
    }

    @Test fun notModifiedIsNotTreatedAsARedirect() {
        val result = UpdateHttp.follow(URL("https://example.com/start")) { url -> Connection(url, 304) }
        assertEquals(304, result.responseCode)
        result.disconnect()
    }
}
