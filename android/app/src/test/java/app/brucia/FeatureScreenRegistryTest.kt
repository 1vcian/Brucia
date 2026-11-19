package app.brucia

import app.brucia.features.BuiltinFeatures
import app.brucia.features.FeatureCategory
import app.brucia.features.FeatureDescriptor
import app.brucia.features.FeatureRegistry
import app.brucia.features.FeatureScreenBinding
import app.brucia.features.FeatureScreenRegistry
import org.junit.Assert.*
import org.junit.Test

class FeatureScreenRegistryTest {
    private val first = FeatureDescriptor("test.tools.first", 10, "First", "Local")
    private val second = FeatureDescriptor("test.tools.second", 20, "Second", "Device", "wifi")
    private fun catalog(vararg features: FeatureDescriptor) =
        FeatureRegistry.of(listOf(FeatureCategory("Tools", "Tools", features.toList())))

    @Test fun joinsByStableIdRegardlessOfBindingOrder() {
        val registry = FeatureScreenRegistry.of(catalog(first, second), listOf(
            FeatureScreenBinding(second.id, handlesCapability = true, screen = "device"),
            FeatureScreenBinding(first.id, screen = "local"),
        ))
        assertEquals(listOf(first, second), registry.destinations.map { it.feature })
        assertEquals("local", registry.byTabId(10)?.screen)
        assertSame(registry.byId(second.id), registry.byTabId(20))
        assertEquals("wifi", registry.byId(second.id)?.feature?.cap)
        assertEquals(true, registry.byTabId(20)?.handlesCapability)
        assertEquals(false, registry.byTabId(10)?.handlesCapability)
    }

    @Test fun missingScreenIsRejectedBeforeNavigation() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FeatureScreenRegistry.of(catalog(first, second), listOf(FeatureScreenBinding(first.id, screen = "one")))
        }
        assertTrue(error.message!!.contains(second.id))
    }

    @Test fun danglingScreenIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FeatureScreenRegistry.of(catalog(first), listOf(FeatureScreenBinding(second.id, screen = "two")))
        }
        assertTrue(error.message!!.contains("no catalog feature"))
    }

    @Test fun duplicateScreenIsRejectedRatherThanOverwritten() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FeatureScreenRegistry.of(catalog(first), listOf(
                FeatureScreenBinding(first.id, screen = "one"), FeatureScreenBinding(first.id, screen = "two"),
            ))
        }
        assertTrue(error.message!!.contains("Duplicate screen"))
    }

    @Test fun callerMutationDoesNotChangeResolvedScreens() {
        val bindings = mutableListOf(FeatureScreenBinding(first.id, screen = "one"))
        val registry = FeatureScreenRegistry.of(catalog(first), bindings)
        bindings.clear()
        bindings += FeatureScreenBinding(first.id, screen = "replacement")
        assertEquals("one", registry.byId(first.id)?.screen)
    }

    @Test fun newFeatureRunsThroughRegistrationAndUnknownRoutesDoNothing() {
        var calls = 0
        val extension = FeatureDescriptor("example.extra.viewer", 900, "Viewer", "Custom")
        val registry = FeatureScreenRegistry.of(catalog(extension), listOf(
            FeatureScreenBinding(extension.id, screen = { calls++ }),
        ))
        assertNull(registry.byId("unknown.screen"))
        assertNull(registry.byTabId(-1))
        assertEquals(0, calls)
        registry.byTabId(900)!!.screen()
        assertEquals(1, calls)
    }

    @Test fun builtinsKeepAllLegacyRoutesWithoutAddingAuxiliaryToolsToTheGrid() {
        val catalog = BuiltinFeatures.registry
        val routes = BuiltinFeatures.routeCatalog
        assertEquals(52, catalog.features.size)
        assertEquals((0..53).toSet(), routes.features.map { it.tabId }.toSet())
        assertNull(catalog.byTabId(16))
        assertNull(catalog.byTabId(36))
        assertEquals("org.brucia.spam.all", routes.byTabId(16)?.id)
        assertEquals("org.brucia.device.theme", routes.byTabId(36)?.id)
        for (feature in catalog.features) assertEquals(feature, routes.byId(feature.id))
    }
}
