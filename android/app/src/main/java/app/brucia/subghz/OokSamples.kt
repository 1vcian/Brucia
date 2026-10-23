package app.brucia.subghz

/**
 * Synthetic-but-valid OOK captures for testing and demos.
 *
 * These arrays follow exactly the same convention the ESP firmware must stream:
 * alternating mark/space pulse widths in microseconds
 * (mark, space, mark, space, ...).
 */
object OokSamples {

    /**
     * A Princeton / PT2262-style 24-bit capture, base time element TE ~= 350 us.
     *
     * Encoding (per the SubGhzDecoder grammar):
     *   bit 0 = short mark (1*TE) + long  space (3*TE)
     *   bit 1 = long  mark (3*TE) + short space (1*TE)
     *   sync  = short mark (1*TE) + very long low gap (31*TE ~= 10850 us)
     *
     * The payload is the 24-bit code 0xA5B3C7
     * (binary 1010 0101 1011 0011 1100 0111, sent MSB first), and the frame is
     * repeated 3 times with a sync gap after each frame. Small per-pulse jitter
     * is included to resemble a real receiver capture while staying well inside
     * the decoder's 35% tolerance.
     *
     * Running SubGhzDecoder.decode(princeton24) yields a single DecodedSignal:
     *   protocol = "PT2262/EV1527", bits = 24, codeHex = "A5B3C7",
     *   teMicros ~= 350, repeats = 3.
     */
    val princeton24: IntArray = intArrayOf(
        1057, 345, 361, 1041, 1054, 338, 358, 1047, 356, 1040,
        1059, 344, 355, 1042, 1062, 346, 1053, 339, 360, 1043,
        1052, 348, 1063, 337, 357, 1045, 361, 1041, 1054, 338,
        1058, 347, 1056, 340, 1059, 344, 355, 1042, 362, 1046,
        353, 1039, 1060, 343, 1052, 348, 1063, 337, 350, 10850,
        1057, 345, 361, 1041, 1054, 338, 358, 1047, 356, 1040,
        1059, 344, 355, 1042, 1062, 346, 1053, 339, 360, 1043,
        1052, 348, 1063, 337, 357, 1045, 361, 1041, 1054, 338,
        1058, 347, 1056, 340, 1059, 344, 355, 1042, 362, 1046,
        353, 1039, 1060, 343, 1052, 348, 1063, 337, 350, 10850,
        1057, 345, 361, 1041, 1054, 338, 358, 1047, 356, 1040,
        1059, 344, 355, 1042, 1062, 346, 1053, 339, 360, 1043,
        1052, 348, 1063, 337, 357, 1045, 361, 1041, 1054, 338,
        1058, 347, 1056, 340, 1059, 344, 355, 1042, 362, 1046,
        353, 1039, 1060, 343, 1052, 348, 1063, 337, 350, 10850
    )
}
