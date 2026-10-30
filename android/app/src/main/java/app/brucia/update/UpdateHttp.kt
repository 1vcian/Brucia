package app.brucia.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Redirect policy separated from network creation so failures are testable offline. */
object UpdateHttp {
    fun follow(start: URL, open: (URL) -> HttpURLConnection): HttpURLConnection {
        var current = start
        repeat(6) { redirects ->
            if (current.protocol != "https" || current.userInfo != null)
                throw IOException("Only credential-free HTTPS updates are supported")
            val connection = open(current)
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                if (redirects == 5) throw IOException("Too many update redirects")
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect has no location")
                current = URL(current, location)
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many update redirects")
    }
}
