package app.brucia.wifi

data class CrackStatus(
    val running: Boolean = false,
    val finished: Boolean = false,
    val tried: Int = 0,
    val total: Int = 0,
    val keysPerSec: Int = 0,
    val result: String? = null,
    val note: String = "",
)
