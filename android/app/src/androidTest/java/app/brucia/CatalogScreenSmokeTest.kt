package app.brucia

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import app.brucia.features.BuiltinFeatures
import app.brucia.features.FeatureDescriptor
import app.brucia.ui.BruciaTheme
import app.brucia.ui.FeatureScreenHost
import app.brucia.wardrive.WardriveViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One fresh Activity and real disconnected ViewModel per route, including the two shortcuts. */
@RunWith(Parameterized::class)
class CatalogScreenSmokeTest(private val id: String, private val feature: FeatureDescriptor) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun opensOffline() {
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[WardriveViewModel::class.java] }
        val registry = builtinFeatureScreens(vm, false, "", {}, {}, {}, {})
        check(registry.byId(id)?.feature == feature)
        compose.setContent {
            BruciaTheme { FeatureScreenHost(registry, feature.tabId, caps = null, onHome = {}) }
        }
        compose.onRoot().assertExists()
        compose.waitForIdle()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun routes() = BuiltinFeatures.routeCatalog.features.map { arrayOf<Any>(it.id, it) }
    }
}
