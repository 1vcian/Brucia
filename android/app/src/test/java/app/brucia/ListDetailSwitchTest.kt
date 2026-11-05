package app.brucia

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * MITM list -> detail -> Back must return to the same list scroll position. Runs the real
 * Compose runtime (Recomposer + Composition, no UI nodes, no Android) on the virtual clock.
 */
class ListDetailSwitchTest {
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

    /** Compose [content] with a [selected] switch; returns every list state the list branch saw. */
    private fun scrollThenOpenAndCloseDetail(
        content: @Composable (selected: String?, list: @Composable (LazyListState) -> Unit) -> Unit,
    ): List<LazyListState> = runVirtual {
        val recomposer = Recomposer(coroutineContext + Frames)
        val loop = launch(Frames) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var selected by mutableStateOf<String?>(null)
        val seen = mutableListOf<LazyListState>()
        suspend fun settle() { Snapshot.sendApplyNotifications(); repeat(20) { yield() } }
        try {
            composition.setContent { content(selected) { seen += it } }
            seen.last().requestScrollToItem(7, 12) // the user scrolled down the transaction list
            selected = "txn 42"; settle()          // open a detail: the list leaves composition
            assertEquals(1, seen.size)
            selected = null; settle()              // Back to list
            seen
        } finally {
            composition.dispose(); recomposer.cancel(); loop.cancel()
        }
    }

    @Test fun backFromDetailKeepsTheListScrollPosition() {
        val seen = scrollThenOpenAndCloseDetail { selected, list ->
            ListDetailSwitch(selected, detail = {}, list = list)
        }
        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
        assertEquals(7, seen[1].firstVisibleItemIndex)
        assertEquals(12, seen[1].firstVisibleItemScrollOffset)
    }

    @Test fun stateRememberedInsideTheListBranchIsLost() {
        // Control for the harness: the previous MitmScreen shape (state owned by the list that
        // leaves composition while the detail is shown) comes back at the top.
        val seen = scrollThenOpenAndCloseDetail { selected, list ->
            if (selected == null) list(rememberLazyListState())
        }
        assertEquals(2, seen.size)
        assertNotSame(seen[0], seen[1])
        assertEquals(0, seen[1].firstVisibleItemIndex)
    }
}
