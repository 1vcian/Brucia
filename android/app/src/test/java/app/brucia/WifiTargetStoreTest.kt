package app.brucia

import app.brucia.wardrive.WifiTargetStore
import app.brucia.wifi.Handshake
import app.brucia.wifi.PmkidTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F38: the imported Wi-Fi crack target is one generation-fenced value. These lock the invariants the
 * previous three-field code (loadedHandshake / loadedPmkid / _handshakeInfo) violated: a slow or
 * failed import must not relabel or revive a target set by a newer operation, and the label must
 * always match what Run/export would act on.
 */
class WifiTargetStoreTest {

    private fun handshake(ssid: String) = Handshake(
        ssid, ByteArray(6), ByteArray(6), ByteArray(32), ByteArray(32), ByteArray(99), ByteArray(16), 2,
    )

    private fun pmkid(ssid: String) = PmkidTarget(ByteArray(16), ByteArray(6), ByteArray(6), ssid)

    /** A lenta -> B: a slow import that began first must not overwrite a target a later op installed. */
    @Test
    fun slowImportCannotOverwriteANewerTarget() {
        val store = WifiTargetStore()
        val slow = store.begin() // import A starts (slow background parse)
        store.set(WifiTargetStore.Target(pmkid = pmkid("B-net")), "B-net (PMKID)") // import B lands first

        // A finishes last and tries to install its result: it must be dropped.
        val installed = store.commit(slow, WifiTargetStore.Target(handshake = handshake("A-net")), "A-net (WPA2)")

        assertFalse("superseded import must not commit", installed)
        assertEquals("B-net (PMKID)", store.info.value)
        assertEquals("B-net", store.current()?.pmkid?.ssid)
        assertNull(store.current()?.handshake)
    }

    /** The latest import to begin still wins even if it commits in order. */
    @Test
    fun latestImportOwnsTheSlot() {
        val store = WifiTargetStore()
        val a = store.begin()
        val b = store.begin()
        assertFalse(store.commit(a, WifiTargetStore.Target(handshake = handshake("A")), "A"))
        assertTrue(store.commit(b, WifiTargetStore.Target(handshake = handshake("B")), "B"))
        assertEquals("B", store.current()?.handshake?.ssid)
    }

    /** errore: a failed/empty import clears the previous target instead of leaving it stale. */
    @Test
    fun failedImportClearsThePreviousTarget() {
        val store = WifiTargetStore()
        store.set(WifiTargetStore.Target(pmkid = pmkid("old")), "old (PMKID)")
        assertNotNullTarget(store)

        // importPcap that found no handshake: commit a null target under this op's token.
        val token = store.begin()
        assertTrue(store.commit(token, null, "no handshake found in file"))

        assertNull("Run/export must see no target after a failed import", store.current())
        assertNull(store.export())
        assertEquals("no handshake found in file", store.info.value)
    }

    /** A pasted invalid line is a failed import: no target, label says why. */
    @Test
    fun invalidLineLeavesNoTarget() {
        val store = WifiTargetStore()
        store.set(WifiTargetStore.Target(handshake = handshake("prev")), "prev (WPA2)")
        store.set(null, "invalid PMKID line")
        assertNull(store.current())
        assertNull(store.export())
        assertEquals("invalid PMKID line", store.info.value)
    }

    /** A non-capture file pull reports what it downloaded but keeps the loaded target. */
    @Test
    fun nonCapturePullKeepsTheTarget() {
        val store = WifiTargetStore()
        store.set(WifiTargetStore.Target(handshake = handshake("home")), "home (WPA2)")
        val pull = store.begin()
        assertTrue(store.touchInfo(pull, "pulled 1234 B: notes.bin"))

        assertEquals("pulled 1234 B: notes.bin", store.info.value)
        assertEquals("home", store.current()?.handshake?.ssid) // target untouched
    }

    /** A superseded pull cannot relabel a newer target, even with touchInfo. */
    @Test
    fun supersededPullCannotRelabel() {
        val store = WifiTargetStore()
        val pull = store.begin() // a slow pull starts
        store.set(WifiTargetStore.Target(pmkid = pmkid("fresh")), "fresh (PMKID)") // a newer import lands
        assertFalse(store.touchInfo(pull, "pulled 10 B: junk.bin"))
        assertEquals("fresh (PMKID)", store.info.value)
        assertEquals("fresh", store.current()?.pmkid?.ssid)
    }

    /** PCAP vs PMKID: each type replaces the other cleanly; never both loaded at once. */
    @Test
    fun handshakeAndPmkidAreMutuallyExclusive() {
        val store = WifiTargetStore()
        store.set(WifiTargetStore.Target(handshake = handshake("wpa")), "wpa (WPA2)")
        assertEquals("wpa", store.current()?.handshake?.ssid)
        assertNull(store.current()?.pmkid)

        store.set(WifiTargetStore.Target(pmkid = pmkid("pmk")), "pmk (PMKID)")
        assertNull("the old handshake must be gone", store.current()?.handshake)
        assertEquals("pmk", store.current()?.pmkid?.ssid)
    }

    /** export follows the current target and is null when there is none. */
    @Test
    fun exportFollowsTheCurrentTarget() {
        val store = WifiTargetStore()
        assertNull(store.export())
        store.set(WifiTargetStore.Target(pmkid = pmkid("x")), "x (PMKID)")
        assertTrue(store.export()!!.startsWith("WPA*"))
        store.set(null, "cleared")
        assertNull(store.export())
    }

    private fun assertNotNullTarget(store: WifiTargetStore) {
        assertTrue("precondition: a target is loaded", store.current() != null)
    }
}
