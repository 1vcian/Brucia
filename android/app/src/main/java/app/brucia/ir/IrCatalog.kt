package app.brucia.ir

import android.content.Context
import org.json.JSONArray

/** A device entry from the bundled catalog (assets/ir_catalog.json). */
data class CatalogDevice(
    val category: String,
    val brand: String,
    val model: String,
    val buttons: List<IrButton>,
) {
    val label: String get() = "$brand - $model"
}

/** Loads the bundled IR catalog (a real Flipper-IRDB subset) from assets. */
object IrCatalog {
    fun load(context: Context): List<CatalogDevice> = try {
        val text = context.assets.open("ir_catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val bs = o.getJSONArray("buttons")
                val buttons = buildList {
                    for (j in 0 until bs.length()) {
                        val b = bs.getJSONObject(j)
                        add(
                            IrButton(
                                b.optString("name"),
                                b.optString("protocol"),
                                b.optString("address"),
                                b.optString("command"),
                            )
                        )
                    }
                }
                add(CatalogDevice(o.optString("category"), o.optString("brand"), o.optString("model"), buttons))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
