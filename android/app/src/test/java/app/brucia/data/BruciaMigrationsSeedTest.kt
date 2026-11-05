package app.brucia.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Accumulator seed for v1 rows (MIGRATION_1_2); the migration itself runs on the emulator. */
class BruciaMigrationsSeedTest {

    @Test
    fun rowWithAFixBecomesOneWeightedSighting() {
        val s = BruciaMigrations.seedFromV1(rssi = -60, lat = 45.4642, lon = 9.19)
        val w = Math.pow(10.0, -6.0)
        assertEquals(w, s.wSum, 0.0)
        assertEquals(45.4642 * w, s.wLat, 0.0)
        assertEquals(9.19 * w, s.wLon, 0.0)
        assertEquals(1, s.seen)
        // wLat / wSum is the position the v2 code shows as the estimate.
        assertEquals(45.4642, s.wLat / s.wSum, 1e-12)
        assertEquals(9.19, s.wLon / s.wSum, 1e-12)
    }

    @Test
    fun rowWithoutAFixGetsZeroWeight() {
        assertEquals(BruciaMigrations.Seed(0.0, 0.0, 0.0, 1), BruciaMigrations.seedFromV1(-40, null, null))
        assertEquals(BruciaMigrations.Seed(0.0, 0.0, 0.0, 1), BruciaMigrations.seedFromV1(-40, 1.0, null))
        assertEquals(BruciaMigrations.Seed(0.0, 0.0, 0.0, 1), BruciaMigrations.seedFromV1(-40, null, 1.0))
    }

    @Test
    fun migrationsChainOneToThree() {
        val steps = BruciaMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3), steps)
    }
}
