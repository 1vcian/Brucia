package app.brucia.wifi

/** Candidate mangling rules (hashcat-style), applied on the phone to expand a
 *  wordlist on the fly: common suffixes, capitalization, leetspeak, reverse. */
object WordlistRules {
    private val suffixes = listOf("1", "12", "123", "1234", "!", "01", "00", "007", "2023", "2024", "2025")

    fun expand(word: String): Sequence<String> = sequence {
        yield(word)
        yield(word.replaceFirstChar { it.uppercaseChar() })
        yield(word.uppercase())
        for (s in suffixes) yield(word + s)
        val leet = word.replace('a', '@').replace('e', '3').replace('o', '0').replace('i', '1').replace('s', '$')
        if (leet != word) yield(leet)
        yield(word.reversed())
    }.distinct()
}
