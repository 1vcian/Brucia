package app.brucia.audio

/** A single note to play on the speaker: frequency in Hz (0 = rest) and duration in ms. */
data class Note(val freqHz: Int, val ms: Int)

/** A named, playable melody. */
data class Song(val name: String, val rtttl: String) {
    val notes: List<Note> by lazy { Rtttl.parse(rtttl) }
}

/**
 * RTTTL (Ring Tone Text Transfer Language) parser + a set of meme/classic preset melodies. Playback
 * is done on the phone by sequencing speaker_tone commands to the device - no new firmware needed.
 */
object Rtttl {
    // Semitone offsets within an octave for note letters.
    private val base = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

    private fun freq(noteLetter: Char, sharp: Boolean, octave: Int): Int {
        val semi = (base[noteLetter] ?: return 0) + if (sharp) 1 else 0
        // MIDI note number: octave 4 'a' = A4 = 440 Hz. midi = 12*(octave+1)+semi.
        val midi = 12 * (octave + 1) + semi
        return Math.round(440.0 * Math.pow(2.0, (midi - 69) / 12.0)).toInt()
    }

    /** Parse an RTTTL string into a note list. Tolerant of minor format variations. */
    fun parse(rtttl: String): List<Note> {
        val parts = rtttl.split(':')
        if (parts.size < 3) return emptyList()
        var d = 4; var o = 5; var bpm = 63
        for (kv in parts[1].split(',')) {
            val t = kv.trim().split('=')
            if (t.size != 2) continue
            val v = t[1].trim().toIntOrNull() ?: continue
            when (t[0].trim()) { "d" -> d = v; "o" -> o = v; "b" -> bpm = v }
        }
        val wholeMs = (60_000.0 / bpm) * 4.0
        val out = ArrayList<Note>()
        for (raw in parts[2].split(',')) {
            val tok = raw.trim().lowercase()
            if (tok.isEmpty()) continue
            var i = 0
            // duration number (optional)
            var num = 0
            while (i < tok.length && tok[i].isDigit()) { num = num * 10 + (tok[i] - '0'); i++ }
            val dur = if (num > 0) num else d
            if (i >= tok.length) continue
            val letter = tok[i]; i++
            var sharp = false
            if (i < tok.length && tok[i] == '#') { sharp = true; i++ }
            var dotted = false
            // octave (optional)
            var oct = o
            if (i < tok.length && tok[i].isDigit()) { oct = tok[i] - '0'; i++ }
            if (i < tok.length && tok[i] == '.') { dotted = true; i++ }
            var ms = (wholeMs / dur).toInt()
            if (dotted) ms = ms * 3 / 2
            val f = if (letter == 'p') 0 else freq(letter, sharp, oct)
            out.add(Note(f, ms))
        }
        return out
    }

    /**
     * Build an RTTTL string from composer note tokens (each already an RTTTL token such as "8c6",
     * "4a#5" or "8p" for a rest). [bpm] sets the tempo; the header defaults d=4,o=5 but every token
     * carries its own duration and octave so the defaults only fill blanks.
     */
    fun build(name: String, bpm: Int, tokens: List<String>): String {
        val safe = name.trim().replace(':', ' ').take(10).ifBlank { "Custom" }
        val b = bpm.coerceIn(20, 900)
        // Drop blanks and any stray commas inside a token so the result is always valid RTTTL.
        val clean = tokens.map { it.trim().replace(",", "") }.filter { it.isNotEmpty() }
        val body = if (clean.isEmpty()) "p" else clean.joinToString(",")
        return "$safe:d=4,o=5,b=$b:$body"
    }

    /** Meme / classic preset melodies (public-domain RTTTL). */
    val PRESETS: List<Song> = listOf(
        Song("Nokia tune", "Nokia:d=4,o=5,b=225:8e6,8d6,f#,g#,8c#6,8b,d,e,8b,8a,c#,e,2a"),
        Song("Super Mario", "Mario:d=4,o=5,b=100:16e6,16e6,32p,8e6,16c6,8e6,8g6,8p,8g,8p,8c6,16p,8g,16p,8e,16p,8a,8b,16a#,8a,16g.,16e6,16g6,8a6,16f6,8g6,8e6,16c6,16d6,8b"),
        Song("Imperial March", "Imperial:d=4,o=5,b=100:e,e,e,8c,16p,16g,e,8c,16p,16g,e,p,b,b,b,8c6,16p,16g,d#,8c,16p,16g,e,8p"),
        Song("Megalovania", "Mega:d=4,o=5,b=240:16d,16d,8d6,8a,8p,8g#,8p,8g,8p,8f,16d,16f,16g,16c,16c,8d6,8a,8p,8g#,8p,8g,8p,8f,16d,16f,16g"),
        Song("Tetris", "Tetris:d=4,o=5,b=160:e6,8b,8c6,8d6,16e6,16d6,8c6,8b,a,8a,8c6,e6,8d6,8c6,b,8b,8c6,d6,e6,c6,a,2a"),
        Song("Zelda", "Zelda:d=4,o=5,b=125:a#,f.,8a#,16a#,16c6,16d6,16d#6,2f6,8p,8f6,16f.6,16f#6,16g#.6,2a#.6"),
        Song("Doom E1M1", "Doom:d=4,o=5,b=112:16e,16e,16e6,16e,16e,16d6,16e,16e,16c6,16e,16e,16a#,16e,16e,16b,16c6"),
        Song("Still Alive", "Alive:d=8,o=5,b=100:c,c,c,c6,a#,a#,a#,g,g#,g#,g#,g,f,f,f,g,g#,g#,g#,g#,a#,c6"),
    )
}
