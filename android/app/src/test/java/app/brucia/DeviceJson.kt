package app.brucia

import org.json.JSONObject

/**
 * [obj] serialized the way the firmware writes an envelope: "v" first (beginEnvelope()). The
 * app's decoder resyncs on the `{"v":` prefix, and the JVM org.json used by unit tests does not
 * keep insertion order, so device frames in tests are built with this.
 */
internal fun deviceJson(obj: JSONObject): String {
    if (!obj.has("v")) return obj.toString()
    val rest = JSONObject(obj.toString()).apply { remove("v") }.toString().removePrefix("{")
    // "v" is a number in every envelope, so its toString() is its JSON form.
    return "{\"v\":" + obj.get("v").toString() + (if (rest == "}") "}" else ",$rest")
}
