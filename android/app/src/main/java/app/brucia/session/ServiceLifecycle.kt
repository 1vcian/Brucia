package app.brucia.session

/** Explicit cleanup inventory. A broken hook cannot prevent unrelated features being stopped. */
class ServiceLifecycle(
    private val entries: List<Entry>,
    private val onError: (String, Exception) -> Unit = { _, _ -> },
) {
    data class Entry(val name: String, val stop: () -> Unit, val disconnect: () -> Unit = stop)
    init { require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate cleanup name" } }
    fun stopAll() = clean(false)
    fun disconnect() = clean(true)
    private fun clean(disconnected: Boolean) {
        entries.forEach {
            try { if (disconnected) it.disconnect() else it.stop() }
            catch (e: Exception) { onError(it.name, e) }
        }
    }
}
