package app.brucia.badusb

import android.content.Context
import org.json.JSONArray

/**
 * Loads the bundled library of DuckyScript payloads from the app assets.
 *
 * The payloads live in "assets/ducky_scripts.json" as a JSON array of objects.
 * This loader is defensive: any read or parse problem results in an empty list
 * rather than a thrown exception, so callers can rely on a non-null result.
 */
object DuckyLibrary {

    private const val ASSET_FILE = "ducky_scripts.json"

    /**
     * Reads and parses the bundled DuckyScript library.
     *
     * @param context any Android context used to access the asset manager.
     * @return the parsed list of payloads, or an empty list on any error.
     */
    fun load(context: android.content.Context): List<DuckyScript> {
        return try {
            // Read the whole asset file as UTF-8 text.
            val json = context.assets.open(ASSET_FILE).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }

            val array = JSONArray(json)
            val result = ArrayList<DuckyScript>(array.length())

            for (i in 0 until array.length()) {
                // Skip entries that are not JSON objects instead of failing the whole load.
                val obj = array.optJSONObject(i) ?: continue

                result.add(
                    DuckyScript(
                        name = obj.optString("name", "Untitled"),
                        category = obj.optString("category", "Uncategorized"),
                        description = obj.optString("description", ""),
                        os = obj.optString("os", "any"),
                        script = obj.optString("script", "")
                    )
                )
            }

            result
        } catch (t: Throwable) {
            // Any I/O or JSON error yields an empty library.
            emptyList()
        }
    }
}
