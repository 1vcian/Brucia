package app.brucia

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brucia.features.FeatureCategory
import app.brucia.features.FeatureDescriptor
import app.brucia.features.FeatureRegistry
import app.brucia.features.FeatureScreenBinding
import app.brucia.features.FeatureScreenRegistry
import app.brucia.ui.BruciaTheme
import app.brucia.ui.Button
import app.brucia.ui.FeatureScreen
import app.brucia.ui.FeatureScreenHost
import app.brucia.wardrive.DeviceCaps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureScreenHostTest {
    @get:Rule val rule = createComposeRule()

    private fun catalog(vararg features: FeatureDescriptor) =
        FeatureRegistry.of(listOf(FeatureCategory("Fixtures", "Fixtures", features.toList())))

    @Test fun registeredScreensHaveIndependentStateAndDisposeOnNavigation() {
        var tab by mutableIntStateOf(100)
        var disposals = 0
        // Deliberately the same renderer for both routes: without a stable route key, Compose
        // would reuse its saved counter and keep the old screen's lifecycle effects alive.
        val shared: FeatureScreen = {
            var count by rememberSaveable { mutableIntStateOf(0) }
            DisposableEffect(Unit) { onDispose { disposals++ } }
            Button(onClick = { count++ }) { Text("Count $count") }
        }
        val registry = FeatureScreenRegistry.of(catalog(
            FeatureDescriptor("fixture.first", 100, "First", ""),
            FeatureDescriptor("fixture.second", 200, "Second", ""),
        ), listOf(
            FeatureScreenBinding("fixture.first", screen = shared),
            FeatureScreenBinding("fixture.second", screen = shared),
        ))
        rule.setContent { BruciaTheme { FeatureScreenHost(registry, tab, null, onHome = {}) } }
        rule.onNodeWithText("Count 0").performClick()
        rule.onNodeWithText("Count 1").assertExists()
        rule.runOnIdle { tab = 200 }
        rule.onNodeWithText("Count 0").assertExists()
        rule.runOnIdle { assertEquals(1, disposals); tab = 100 }
        rule.onNodeWithText("Count 0").assertExists()
        rule.runOnIdle { assertEquals(2, disposals) }
    }

    @Test fun capabilityChangesGateOnlyScreensThatDelegateToTheHost() {
        var tab by mutableIntStateOf(100)
        var caps by mutableStateOf<DeviceCaps?>(null)
        val registry = FeatureScreenRegistry.of(catalog(
            FeatureDescriptor("fixture.gated", 100, "Gated", "", "wifi"),
            FeatureDescriptor("fixture.self", 200, "Self", "", "nrf24"),
            FeatureDescriptor("fixture.local", 300, "Local", ""),
        ), listOf(
            FeatureScreenBinding<FeatureScreen>("fixture.gated", screen = { Text("Device screen") }),
            FeatureScreenBinding<FeatureScreen>("fixture.self", handlesCapability = true, screen = { Text("Own module UI") }),
            FeatureScreenBinding<FeatureScreen>("fixture.local", screen = { Text("Local screen") }),
        ))
        rule.setContent { BruciaTheme { FeatureScreenHost(registry, tab, caps, onHome = {}) } }
        rule.onNodeWithText("Device screen").assertExists() // disconnected / unknown caps
        rule.runOnIdle { caps = DeviceCaps() }
        rule.onNodeWithText("Device screen").assertDoesNotExist()
        rule.onNodeWithText("This device has no Wi-Fi radio.").assertExists()
        rule.runOnIdle { caps = DeviceCaps(wifi = true) }
        rule.onNodeWithText("Device screen").assertExists()
        rule.runOnIdle { caps = DeviceCaps(); tab = 200 }
        rule.onNodeWithText("Own module UI").assertExists()
        rule.runOnIdle { tab = 300 }
        rule.onNodeWithText("Local screen").assertExists()
    }

    @Test fun unknownRouteNeverComposesAnotherFeatureAndCanReturnHome() {
        var compositions = 0
        var home = 0
        val registry = FeatureScreenRegistry.of(catalog(
            FeatureDescriptor("fixture.device", 100, "Device", ""),
        ), listOf(
            FeatureScreenBinding<FeatureScreen>("fixture.device", screen = { compositions++; Text("Unexpected") }),
        ))
        rule.setContent { BruciaTheme { FeatureScreenHost(registry, 999, null, onHome = { home++ }) } }
        rule.onNodeWithText("Tool not available").assertExists()
        rule.onNodeWithText("Unexpected").assertDoesNotExist()
        rule.onNodeWithText("Back to Home").performClick()
        rule.runOnIdle { assertEquals(0, compositions); assertEquals(1, home) }
    }
}
