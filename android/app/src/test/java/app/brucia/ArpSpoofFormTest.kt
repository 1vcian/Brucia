package app.brucia

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The MITM ARP-spoof card keeps its inputs while a request detail is open (the list, card
 * included, leaves composition), and a rotation keeps the SSID and addresses but not the
 * password. Real Compose runtime with a saveable-state registry, no UI nodes, virtual clock.
 */
class ArpSpoofFormTest {
    private class Node
    private class NoNodes : AbstractApplier<Node>(Node()) {
        override fun insertTopDown(index: Int, instance: Node) {}
        override fun insertBottomUp(index: Int, instance: Node) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    private object Frames : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R { yield(); return onFrame(0L) }
    }

    private fun registry(restored: Map<String, List<Any?>>? = null) = SaveableStateRegistry(restored) { true }

    /**
     * Composes [content] under [registry], lets [script] drive it with the selected-detail switch,
     * and disposes the composition afterwards.
     */
    private fun compose(
        registry: SaveableStateRegistry,
        content: @Composable (selected: String?) -> Unit,
        script: suspend (select: suspend (String?) -> Unit) -> Unit = {},
    ) = runVirtual {
        val recomposer = Recomposer(coroutineContext + Frames)
        val loop = launch(Frames) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var selected by mutableStateOf<String?>(null)
        suspend fun settle() { Snapshot.sendApplyNotifications(); repeat(20) { yield() } }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content(selected) }
            }
            script { selected = it; settle() }
        } finally {
            composition.dispose(); recomposer.cancel(); loop.cancel()
        }
    }

    private fun ArpSpoofForm.type() {
        ssid = "lab-net"; password = "hunter22"; target = "192.168.1.42"; gateway = "192.168.1.1"
    }

    @Test fun formOwnedAboveTheSwitchKeepsTheInputsAcrossADetail() {
        val seen = mutableListOf<ArpSpoofForm>()
        compose(registry(), { selected ->
            val form = rememberArpSpoofForm() // MitmScreen: above the list/detail switch
            ListDetailSwitch(selected, detail = {}) { seen += form }
        }) { select ->
            seen.last().type()
            select("txn 42") // open a request detail: the list and the card leave composition
            select(null)     // Back to list
        }
        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
        with(seen[1]) {
            assertEquals("lab-net", ssid)
            assertEquals("hunter22", password)
            assertEquals("192.168.1.42", target)
            assertEquals("192.168.1.1", gateway)
        }
    }

    @Test fun formOwnedInsideTheListIsLostAcrossADetail() {
        // Control for the harness: the previous shape (state remembered in the card, inside the
        // list) comes back empty after a detail, even with a saveable-state registry.
        val seen = mutableListOf<ArpSpoofForm>()
        compose(registry(), { selected ->
            ListDetailSwitch(selected, detail = {}) { seen += rememberArpSpoofForm() }
        }) { select ->
            seen.last().type()
            select("txn 42")
            select(null)
        }
        assertEquals(2, seen.size)
        assertNotSame(seen[0], seen[1])
        assertEquals("", seen[1].ssid)
    }

    @Test fun rotationKeepsSsidAndAddressesButNotThePassword() {
        val forms = mutableListOf<ArpSpoofForm>()
        // One content lambda for both compositions: the saved key is the position in the tree.
        val screen: @Composable (String?) -> Unit = { forms += rememberArpSpoofForm() }
        val before = registry()
        var saved: Map<String, List<Any?>> = emptyMap()
        compose(before, screen) {
            forms.last().type()
            saved = before.performSave() // the Activity is recreated: state saved, UI rebuilt
        }
        compose(registry(saved), screen)
        assertEquals(2, forms.size)
        assertNotSame(forms[0], forms[1])
        with(forms[1]) {
            assertEquals("lab-net", ssid)
            assertEquals("", password)
            assertEquals("192.168.1.42", target)
            assertEquals("192.168.1.1", gateway)
        }
    }
}
