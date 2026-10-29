package app.brucia.ir

/**
 * The standard functions of an on-screen remote control ("telecomandino"). Each key
 * is mapped to one of a catalog device's [IrButton]s by fuzzy name match so a real
 * remote layout can be rendered for messy, inconsistent catalog naming.
 */
enum class RemoteKey {
    POWER, MUTE,
    VOL_UP, VOL_DOWN,
    CH_UP, CH_DOWN,
    UP, DOWN, LEFT, RIGHT, OK,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,
    INPUT, MENU, BACK, HOME;

    companion object {
        /** The ten number-pad keys, in display order. */
        val numbers: List<RemoteKey> =
            listOf(NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9, NUM_0)
    }
}

/**
 * Pure fuzzy mapping from the messy Flipper-IRDB button names to the standard remote
 * functions. Kept free of Android/Compose so it is unit-testable.
 */
object IrRemoteMap {

    /** Lowercase and strip the common separators, but keep `+` and `-` (they carry direction). */
    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[\\s_./]+"), "")

    private fun isUpish(n: String): Boolean =
        n.endsWith("+") || n.contains("up") || n.contains("plus") ||
            n.contains("next") || n.contains("inc")

    private fun isDownish(n: String): Boolean =
        n.endsWith("-") || n.contains("down") || n.contains("dn") || n.contains("minus") ||
            n.contains("prev") || n.contains("dec")

    private fun isVol(n: String): Boolean =
        n.startsWith("vol") || n.contains("volume") || n == "v+" || n == "v-"

    private fun isChan(n: String): Boolean =
        n.startsWith("ch") || n.contains("chan") || n.contains("prog")

    private fun numberKey(n: String): RemoteKey? {
        val digit: Char? = when {
            n.length == 1 && n[0].isDigit() -> n[0]
            else -> {
                // Match "<prefix><single digit>", longest prefixes first so "number7" is not
                // mangled by the shorter "num" prefix.
                val prefixes = listOf("number", "digit", "num", "key")
                prefixes.firstOrNull { n.length == it.length + 1 && n.startsWith(it) && n.last().isDigit() }
                    ?.let { n.last() }
            }
        }
        return when (digit) {
            '0' -> RemoteKey.NUM_0; '1' -> RemoteKey.NUM_1; '2' -> RemoteKey.NUM_2
            '3' -> RemoteKey.NUM_3; '4' -> RemoteKey.NUM_4; '5' -> RemoteKey.NUM_5
            '6' -> RemoteKey.NUM_6; '7' -> RemoteKey.NUM_7; '8' -> RemoteKey.NUM_8
            '9' -> RemoteKey.NUM_9; else -> null
        }
    }

    /**
     * The single remote function a raw button name best denotes, or null if none.
     * Order matters: specific keys (numbers, volume, channel) are tested before the
     * generic arrows so "Vol_up" does not fall through to the plain UP arrow.
     */
    fun keyFor(name: String): RemoteKey? {
        val n = norm(name)
        if (n.isEmpty()) return null

        numberKey(n)?.let { return it }

        if (isVol(n)) return when {
            isUpish(n) -> RemoteKey.VOL_UP
            isDownish(n) -> RemoteKey.VOL_DOWN
            else -> null
        }
        if (isChan(n)) return when {
            isUpish(n) -> RemoteKey.CH_UP
            isDownish(n) -> RemoteKey.CH_DOWN
            else -> null
        }

        if (n.startsWith("power") || n == "pwr" || n == "pwer" || n == "onoff" || n == "standby") return RemoteKey.POWER
        if (n.contains("mute") || n == "mut") return RemoteKey.MUTE
        if (n.contains("input") || n.contains("source") || n == "av") return RemoteKey.INPUT
        if (n.contains("menu")) return RemoteKey.MENU
        if (n == "back" || n == "return" || n == "exit" || n == "esc" || n == "escape") return RemoteKey.BACK
        if (n == "home") return RemoteKey.HOME
        if (n == "ok" || n == "enter" || n == "select" || n == "sel") return RemoteKey.OK

        if (n in setOf("up", "arrowup", "uparrow", "navup", "cursorup", "dpadup", "upkey")) return RemoteKey.UP
        if (n in setOf("down", "arrowdown", "downarrow", "navdown", "cursordown", "dpaddown", "downkey")) return RemoteKey.DOWN
        if (n in setOf("left", "arrowleft", "leftarrow", "navleft", "cursorleft", "dpadleft", "leftkey")) return RemoteKey.LEFT
        if (n in setOf("right", "arrowright", "rightarrow", "navright", "cursorright", "dpadright", "rightkey")) return RemoteKey.RIGHT

        return null
    }

    /**
     * Map the standard remote functions to a device's buttons. The first button that
     * matches each key wins; unmatched keys are simply absent from the result.
     */
    fun mapRemote(buttons: List<IrButton>): Map<RemoteKey, IrButton> {
        val out = LinkedHashMap<RemoteKey, IrButton>()
        for (b in buttons) {
            val key = keyFor(b.name) ?: continue
            if (!out.containsKey(key)) out[key] = b
        }
        return out
    }
}
