package app.brucia.subghz

/**
 * Static OOK protocol table for common fixed-code sub-GHz remotes.
 *
 * This mirrors the firmware RF registry (src/modules/rf/protocols/rf_registry.cpp)
 * so decode results map straight onto a real transmit command: [canonical] is the
 * name accepted by the device CLI `subghz txp <name> ...`, and [flipper] is the
 * name written to / read from Flipper `.sub` files (`Protocol:` field).
 *
 * Timings follow the classic factor model: every pulse is a multiple of [te] us.
 * A single data bit is two consecutive pulses (a cell); [zero] and [one] are the
 * ordered (first, second) duration factors of that cell as they appear in the
 * captured mark/space stream. This holds for both signal polarities:
 *   - high-first (inverted = false): the cell is (high, low).
 *   - space-coded (inverted = true): the cell is (low, high); the frame is also
 *     preceded by a short preamble pulse, which the decoder skips via an offset.
 */
data class OokProtocol(
    val canonical: String,
    val flipper: String,
    val te: Int,
    val zero: Pair<Int, Int>,
    val one: Pair<Int, Int>,
    /** Fixed payload length in bits, or 0 when the length is variable. */
    val bits: Int,
    val inverted: Boolean
) {
    /** Largest factor in any data cell (used to tell a data pulse from a sync gap). */
    val maxDataFactor: Int
        get() = maxOf(zero.first, zero.second, one.first, one.second)
}

object OokProtocols {

    /**
     * The decode candidates, ordered. Values taken from the firmware registry.
     * Princeton is variable-length (the vast majority of EV1527/PT2262 remotes);
     * the rest are fixed length, which disambiguates same-timing families.
     */
    val all: List<OokProtocol> = listOf(
        OokProtocol("Princeton", "Princeton", 350, 1 to 3, 3 to 1, 0, false),
        OokProtocol("NICE_FLO", "Nice FLO", 700, 2 to 1, 1 to 2, 12, false),
        OokProtocol("Linear", "Linear", 500, 1 to 3, 3 to 1, 10, false),
        OokProtocol("CAME", "CAME", 320, 2 to 1, 1 to 2, 12, true),
        OokProtocol("Holtek", "Holtek_HT12X", 430, 1 to 2, 2 to 1, 40, true),
        OokProtocol("Holtek_12bit", "Holtek 12bit", 430, 1 to 2, 2 to 1, 12, true)
    )

    fun byCanonical(name: String): OokProtocol? =
        all.firstOrNull { it.canonical.equals(name, ignoreCase = true) }

    /** Resolve a Flipper (or canonical) protocol name to a table entry. */
    fun byAnyName(name: String): OokProtocol? {
        val n = name.trim()
        return all.firstOrNull { it.flipper.equals(n, ignoreCase = true) }
            ?: all.firstOrNull { it.canonical.equals(n, ignoreCase = true) }
    }
}
