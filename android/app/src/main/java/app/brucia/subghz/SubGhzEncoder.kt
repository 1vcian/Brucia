package app.brucia.subghz

/**
 * Encodes a fixed OOK code into a pulse-timing array (alternating mark/space, us)
 * that the device can transmit, or that we can write as RAW_Data to a `.sub`
 * file. Symmetric to [SubGhzDecoder].
 */
object SubGhzEncoder {

    /** Factor of TE used for the inter-frame sync gap when encoding. */
    private const val SYNC_GAP_FACTOR = 31

    /**
     * Princeton / PT2262-style convenience encode (the historic default):
     *   bit 0 = short mark (1*TE) + long  space (3*TE)
     *   bit 1 = long  mark (3*TE) + short space (1*TE)
     */
    fun encode(codeHex: String, bits: Int, te: Int = 350, repeats: Int = 3): IntArray =
        encode(OokProtocols.byCanonical("Princeton")!!, codeHex, bits, te, repeats)

    /**
     * Protocol-aware encode. [codeHex] is the key (MSB first), [bits] its width.
     * When [te] <= 0 the protocol's nominal TE is used. Produces [repeats] frames
     * each terminated (or, for space-coded protocols, preceded) by a sync gap.
     */
    fun encode(p: OokProtocol, codeHex: String, bits: Int, te: Int = 0, repeats: Int = 3): IntArray {
        val teUs = if (te > 0) te else p.te
        val value = codeHex.trim().ifEmpty { "0" }.toULong(16)
        val out = ArrayList<Int>(repeats * (bits * 2 + 2))
        repeat(repeats) {
            if (p.inverted) {
                // Space-coded: sync gap (low) + short preamble (high), then cells
                // ordered (low, high).
                out.add(SYNC_GAP_FACTOR * teUs)
                out.add(teUs)
            }
            for (i in bits - 1 downTo 0) {
                val bit = ((value shr i) and 1uL).toInt()
                val cell = if (bit == 0) p.zero else p.one
                out.add(cell.first * teUs)
                out.add(cell.second * teUs)
            }
            if (!p.inverted) {
                // High-first: short mark + long inter-frame gap.
                out.add(teUs)
                out.add(SYNC_GAP_FACTOR * teUs)
            }
        }
        return out.toIntArray()
    }
}
