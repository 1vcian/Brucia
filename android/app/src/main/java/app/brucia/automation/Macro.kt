package app.brucia.automation

import org.json.JSONArray
import org.json.JSONObject

/** One step in a macro: a raw companion/CLI command and a pause (ms) to wait after it runs. */
data class MacroStep(val cmd: String, val delayMs: Int = 500)

/** A named, saved sequence of device commands the phone runs in order ("phone-side automation"). */
data class Macro(val name: String, val steps: List<MacroStep>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("steps", JSONArray().also { arr ->
            steps.forEach { s -> arr.put(JSONObject().apply { put("cmd", s.cmd); put("delayMs", s.delayMs) }) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val steps = ArrayList<MacroStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val cmd = s.optString("cmd").trim()
                if (cmd.isEmpty()) continue
                steps.add(MacroStep(cmd, s.optInt("delayMs", 500).coerceIn(0, 60_000)))
            }
            return Macro(o.optString("name").ifBlank { "Macro" }, steps)
        }

        fun listToJson(macros: List<Macro>): String =
            JSONArray().also { arr -> macros.forEach { arr.put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<Macro> {
            val out = ArrayList<Macro>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(fromJson(o))
                }
            } catch (e: Exception) { app.brucia.BruciaLog.w("Macro", "load macros failed", e) }
            return out
        }
    }
}
