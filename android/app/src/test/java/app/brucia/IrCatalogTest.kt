package app.brucia

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the bundled IR catalog asset (converted from Flipper-IRDB): it must stay
 * large and cover the whole alphabet, so a bad regeneration can't silently shrink it
 * back to a handful of brands. Text-based checks so no JSON lib is needed on the JVM.
 */
class IrCatalogTest {
    private val file = File("src/main/assets/ir_catalog.json")

    @Test fun catalogIsLargeAndWellFormed() {
        if (!file.isFile) return // skip if run from an unexpected working dir
        val text = file.readText()
        assertTrue("catalog should be a JSON array", text.trimStart().startsWith("["))
        val models = Regex("\"model\"").findAll(text).count()
        assertTrue("expected many IR models, got $models", models >= 500)
        val brands = Regex("\"brand\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1].first().uppercaseChar() }.toSet()
        // Must cover well past 'E' (the old hand-made catalog stopped there).
        for (c in listOf('M', 'S', 'T', 'Z')) {
            assertTrue("no brand starting with '$c'", brands.contains(c))
        }
        assertTrue("expected broad brand coverage", brands.size >= 15)
    }
}
