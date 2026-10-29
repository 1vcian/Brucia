package app.brucia

import app.brucia.wifi.WordlistRules
import org.junit.Assert.assertTrue
import org.junit.Test

class WordlistRulesTest {
    @Test fun expandsCommonMutations() {
        val out = WordlistRules.expand("pass").toList()
        assertTrue(out.contains("pass"))
        assertTrue(out.contains("Pass"))
        assertTrue(out.contains("PASS"))
        assertTrue(out.contains("pass123"))
        assertTrue(out.contains("p@\$\$"))     // leet a->@, s->$
        assertTrue(out.contains("ssap"))      // reversed
        assertTrue(out.size == out.distinct().size) // no dupes
    }
}
