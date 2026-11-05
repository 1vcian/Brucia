package app.brucia

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Material3 1.4 defaults draw OutlinedButton labels grey; the Brucia look comes from the
 * wrappers in app.brucia.ui (BruciaButtons.kt). A screen that called the material3
 * buttons or their default colors directly would silently lose it (a wildcard
 * `androidx.compose.material3.*` import is enough), so check every production source.
 */
class ButtonThemeUsageTest {

    private val names = listOf("Button", "OutlinedButton", "TextButton")
    private val sources = File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.endsWith("ui/BruciaButtons.kt") }
        .toList()

    @Test
    fun sourcesFound() {
        assertTrue("no Kotlin sources under src/main/java", sources.size > 10)
    }

    @Test
    fun buttonsComeFromTheBruciaWrappers() {
        val problems = mutableListOf<String>()
        for (f in sources) {
            val text = f.readText()
            val code = text.lines().filterNot { it.startsWith("import ") }.joinToString("\n")
            for (n in names) {
                if (Regex("^import androidx\\.compose\\.material3\\.$n$", RegexOption.MULTILINE).containsMatchIn(text)) {
                    problems += "${f.path}: imports androidx.compose.material3.$n"
                }
                if (code.contains("androidx.compose.material3.$n(")) {
                    problems += "${f.path}: calls androidx.compose.material3.$n directly"
                }
                val calls = Regex("(?<![A-Za-z0-9_.])$n\\(").containsMatchIn(code)
                if (calls && !text.contains("\nimport app.brucia.ui.$n\n")) {
                    problems += "${f.path}: calls $n without importing app.brucia.ui.$n"
                }
            }
            if (Regex("(?<![A-Za-z0-9_])ButtonDefaults\\.(buttonColors|outlinedButtonColors|textButtonColors|outlinedButtonBorder)\\(").containsMatchIn(code)) {
                problems += "${f.path}: uses material3 ButtonDefaults colors; use BruciaButtonDefaults"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
