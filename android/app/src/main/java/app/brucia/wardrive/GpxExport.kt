package app.brucia.wardrive

/** Export the walked GPS track as a GPX 1.1 track (importable by mapping tools). */
object GpxExport {
    fun build(track: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Brucia\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>Brucia Wardrive</name><trkseg>\n")
        for ((lat, lon) in track) {
            sb.append("    <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"></trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}
