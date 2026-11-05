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
}
