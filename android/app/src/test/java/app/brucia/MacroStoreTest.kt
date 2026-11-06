package app.brucia

import app.brucia.automation.Macro
import app.brucia.automation.MacroStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved macros are user data in SharedPreferences: they must survive a save/load and bad input. */
class MacroStoreTest {

    @Test fun savedMacrosRoundTrip() {
        val macros = listOf(
            Macro("Scan then deauth", listOf(MacroStep("wifi scan", 3000), MacroStep("say \"done\" / ok", 0))),
            Macro("Empty", emptyList()),
        )
        assertEquals(macros, Macro.listFromJson(Macro.listToJson(macros)))
    }

    @Test fun corruptStoreLoadsAsEmptyWithoutThrowing() {
        assertEquals(emptyList<Macro>(), Macro.listFromJson("{not json"))
        assertEquals(emptyList<Macro>(), Macro.listFromJson(""))
    }

    @Test fun malformedEntriesAreRepairedNotFatal() {
        val loaded = Macro.listFromJson(
            """[3, {"name":"  ","steps":[{"cmd":"  "},{"cmd":" ir tx "},7,{"cmd":"x","delayMs":999999},{"cmd":"y","delayMs":-5}]},
               {"steps":"nope"}]""",
        )
        assertEquals(
            listOf(
                Macro("Macro", listOf(MacroStep("ir tx", 500), MacroStep("x", 60_000), MacroStep("y", 0))),
                Macro("Macro", emptyList()),
            ),
            loaded,
        )
    }

    @Test fun defaultNameNeverReplacesASavedMacro() {
        assertEquals("Macro 1", Macro.defaultName(emptyList()))
        // Macro 1 was deleted: "Macro ${'$'}{size + 1}" was "Macro 2" and replaced the saved one.
        val left = listOf(Macro("Macro 2", listOf(MacroStep("uptime"))))
        assertEquals("Macro 3", Macro.defaultName(left))
        assertEquals("Macro 3", Macro.defaultName(listOf(Macro("a", emptyList()), Macro("Macro 2", emptyList()))))
        assertEquals("Macro 2", Macro.defaultName(listOf(Macro("x", emptyList()))))
    }

    @Test fun moveStepReordersWithinBounds() {
        val a = MacroStep("a"); val b = MacroStep("b"); val c = MacroStep("c")
        assertEquals(listOf(b, a, c), Macro.moveStep(listOf(a, b, c), 1, -1))
        assertEquals(listOf(a, c, b), Macro.moveStep(listOf(a, b, c), 1, 1))
        assertEquals(listOf(a, b, c), Macro.moveStep(listOf(a, b, c), 0, -1))
        assertEquals(listOf(a, b, c), Macro.moveStep(listOf(a, b, c), 5, 1))
    }
}
