package app.brucia

import app.brucia.features.BuiltinFeatures
import app.brucia.features.FeatureCategory
import app.brucia.features.FeatureDescriptor
import app.brucia.features.FeatureRegistry
import app.brucia.features.FeatureRegistryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureRegistryTest {

    // A custom fixture set: proves the registry is reusable without touching the impl.
    private fun fixture(): List<FeatureCategory> = listOf(
        FeatureCategory("Alpha", "A", listOf(
            FeatureDescriptor("com.example.a.one", 100, "One", "First"),
            FeatureDescriptor("com.example.a.two", 101, "Two", "Second", "wifi"),
        )),
        FeatureCategory("Beta", "B", listOf(
            FeatureDescriptor("com.example.b.three", 102, "Three", "Third"),
        )),
    )

    @Test fun customFixtureBuildsAndLooksUp() {
        val reg = FeatureRegistry.of(fixture())
        assertEquals(3, reg.features.size)
        assertEquals("One", reg.byId("com.example.a.one")?.title)
        assertEquals("wifi", reg.byTabId(101)?.cap)
    }

    @Test fun categoryAndToolOrderPreserved() {
        val reg = FeatureRegistry.of(fixture())
        assertEquals(listOf("Alpha", "Beta"), reg.categories.map { it.name })
        assertEquals(
            listOf("com.example.a.one", "com.example.a.two", "com.example.b.three"),
            reg.features.map { it.id },
        )
    }

    @Test fun unknownLookupReturnsAbsence() {
        val reg = FeatureRegistry.of(fixture())
        assertNull(reg.byId("com.example.missing"))
        assertNull(reg.byTabId(9999))
    }

    @Test fun duplicateStableIdAcrossCategoriesRejected() {
        val input = listOf(
            FeatureCategory("A", "A", listOf(FeatureDescriptor("com.dup.id", 1, "X", "x"))),
            FeatureCategory("B", "B", listOf(FeatureDescriptor("com.dup.id", 2, "Y", "y"))),
        )
        val ex = assertThrows { FeatureRegistry.of(input) }
        assertTrue(ex.message!!.contains("Duplicate stable id"))
    }

    @Test fun duplicateTabIdAcrossCategoriesRejected() {
        val input = listOf(
            FeatureCategory("A", "A", listOf(FeatureDescriptor("com.a.one", 5, "X", "x"))),
            FeatureCategory("B", "B", listOf(FeatureDescriptor("com.b.two", 5, "Y", "y"))),
        )
        val ex = assertThrows { FeatureRegistry.of(input) }
        assertTrue(ex.message!!.contains("Duplicate tab id"))
    }

    @Test fun blankIdRejected() {
        val input = listOf(
            FeatureCategory("A", "A", listOf(FeatureDescriptor("   ", 1, "X", "x"))),
        )
        val ex = assertThrows { FeatureRegistry.of(input) }
        assertTrue(ex.message!!.contains("Malformed or blank"))
    }

    @Test fun malformedIdRejected() {
        val input = listOf(
            FeatureCategory("A", "A", listOf(FeatureDescriptor("No Dots Here", 1, "X", "x"))),
        )
        val ex = assertThrows { FeatureRegistry.of(input) }
        assertTrue(ex.message!!.contains("Malformed or blank"))
    }

    @Test fun inputMutationAfterConstructionDoesNotAffectRegistry() {
        val innerA = mutableListOf(
            FeatureDescriptor("com.example.a.one", 100, "One", "First"),
        )
        val categories = mutableListOf(
            FeatureCategory("Alpha", "A", innerA),
        )
        val reg = FeatureRegistry.of(categories)

        // Mutate caller-owned lists after construction.
        innerA.add(FeatureDescriptor("com.example.a.sneaky", 200, "Sneaky", "x"))
        categories.add(FeatureCategory("Beta", "B", mutableListOf(
            FeatureDescriptor("com.example.b.late", 201, "Late", "y"),
        )))

        assertEquals(1, reg.categories.size)
        assertEquals(1, reg.features.size)
        assertEquals(1, reg.categories[0].features.size)
        assertNull(reg.byId("com.example.a.sneaky"))
        assertNull(reg.byTabId(200))
    }

    @Test fun builtinRegistryHasExpectedShape() {
        val reg = BuiltinFeatures.registry
        assertEquals(11, reg.categories.size)
        assertEquals(52, reg.features.size)
        // Mixed tools remain reachable: actual capture/playback operations own their gates.
        for (tab in listOf(7, 27, 28)) assertNull(reg.byTabId(tab)?.cap)
        // Null cap keeps its meaning (ESP-NOW is always available).
        assertNull(reg.byTabId(32)?.cap)
        // Legacy-tab -> stable-id adapter round-trips.
        val wardrive = reg.byTabId(0)!!
        assertEquals("org.brucia.wifi.wardrive", wardrive.id)
        assertSame(wardrive, reg.byId("org.brucia.wifi.wardrive"))
        // First and last in navigation order.
        assertEquals("Wardrive", reg.features.first().title)
        assertEquals("BLE console", reg.features.last().title)
        // BLE console: phone-side Bluetooth, no device capability gate (the device's BLE API is optional).
        assertEquals("org.brucia.tools.bleconsole", reg.byTabId(52)?.id)
        assertNull(reg.byTabId(52)?.cap)
        // IR power spam is reachable from the Spam category too (same run as the IR screen).
        val spamIr = reg.byTabId(53)!!
        assertEquals("org.brucia.spam.spamir", spamIr.id)
        assertEquals("ir", spamIr.cap)
        assertTrue(reg.categories.first { it.name == "Spam" }.features.contains(spamIr))
    }

    private fun assertThrows(block: () -> Unit): FeatureRegistryException {
        try {
            block()
        } catch (e: FeatureRegistryException) {
            return e
        }
        throw AssertionError("Expected FeatureRegistryException")
    }
}
