package app.brucia.features.example

import app.brucia.features.FeatureCategory
import app.brucia.features.FeatureRegistry
import app.brucia.features.FeatureScreenRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test

class FixtureRegistrationTest {
    @Test fun resolvesGeneratedFeature() {
        val catalog = FeatureRegistry.of(listOf(FeatureCategory("Examples", "Examples", listOf(fixtureDescriptor))))
        val routes = FeatureScreenRegistry.of(catalog, listOf(fixtureBinding(packetCount = 1)))
        assertNotNull(routes.byTabId(fixtureDescriptor.tabId))
    }
}
