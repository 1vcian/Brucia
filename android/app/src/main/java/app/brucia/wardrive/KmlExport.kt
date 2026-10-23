package app.brucia.wardrive

/**
 * Builds a KML 2.2 document from AP observations so the wardrive results can be
 * opened in Google Earth or any KML viewer. Only APs with a known position are
 * emitted; all text is XML-escaped.
 */
object KmlExport {

    /** Returns a complete KML 2.2 document as a String. */
    fun build(aps: Collection<ApObservation>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escape("Brucia Wardrive")).append("</name>\n")

        for (ap in aps) {
            val lat = ap.lat
            val lon = ap.lon
            if (lat == null || lon == null) continue

            val name = if (ap.ssid.isBlank()) "(hidden)" else ap.ssid
            val description = buildString {
                append("BSSID: ").append(ap.bssid).append('\n')
                append("Channel: ").append(ap.channel).append('\n')
                append("RSSI: ").append(ap.rssi).append(" dBm").append('\n')
                append("Encryption: ").append(ap.enc)
            }

            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(escape(name)).append("</name>\n")
            sb.append("      <description>").append(escape(description)).append("</description>\n")
            sb.append("      <Point>\n")
            // KML coordinate order is lon,lat,altitude.
            sb.append("        <coordinates>")
                .append(lon).append(',').append(lat).append(",0")
                .append("</coordinates>\n")
            sb.append("      </Point>\n")
            sb.append("    </Placemark>\n")
        }

        sb.append("  </Document>\n")
        sb.append("</kml>\n")
        return sb.toString()
    }

    /** Escapes the five characters that are significant in XML text/attributes. */
    private fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
