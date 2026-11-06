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

        /**
         * Name for a macro saved without one: "Macro N" with the first N from count+1 that no saved
         * macro uses. Saving replaces a macro of the same name, so "Macro ${'$'}{count + 1}" alone
         * overwrote an existing one after a deletion (Macro 1, Macro 2; delete Macro 1; save -> Macro 2).
         */
        fun defaultName(existing: List<Macro>): String {
            val used = existing.mapTo(HashSet()) { it.name }
            var n = existing.size + 1
            while ("Macro $n" in used) n++
            return "Macro $n"
        }

        /** [steps] with the step at [index] moved by [delta] places (clamped to the list); same list when it cannot move. */
        fun moveStep(steps: List<MacroStep>, index: Int, delta: Int): List<MacroStep> {
            if (index !in steps.indices) return steps
            val to = (index + delta).coerceIn(0, steps.size - 1)
            if (to == index) return steps
            return steps.toMutableList().apply { add(to, removeAt(index)) }
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
