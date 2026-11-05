package app.brucia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.brucia.mitm.MitmTxn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MITM request list: new traffic is added at the top (newest first). With rows keyed by the
 * entry id the row the user is looking at stays the first visible one, both while the list is
 * shown and when it comes back from a request detail. Needs real layout, so it runs on a device.
 */
@RunWith(AndroidJUnit4::class)
class MitmRowKeysTest {
    @get:Rule val rule = createComposeRule()

    private fun txn(id: Long) = MitmTxn(
        id = id, timeMs = 0, method = "GET", scheme = "http", host = "example.test", path = "/$id",
        status = 200, reqBytes = 0, respBytes = 0, durationMs = 0, decrypted = true, note = "",
        reqHeaders = "", respHeaders = "", reqPreview = "", respPreview = "",
    )

    private fun newest(from: Long, to: Long) = (to downTo from).map(::txn)

    /** Scrolls to a row, adds 3 entries live and 5 while a detail is open; returns the row ids seen first. */
    private fun firstRowIdsAsTrafficArrives(rows: LazyListScope.(List<MitmTxn>) -> Unit): List<Long> {
        var txns by mutableStateOf(newest(1, 40))
        var selected by mutableStateOf<String?>(null)
        lateinit var state: LazyListState
        rule.setContent {
            Box(Modifier.height(300.dp)) {
                ListDetailSwitch(selected, detail = { Text("detail") }) { listState ->
                    state = listState
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        item { Box(Modifier.height(120.dp)) } // the cards above the log in MitmScreen
                        rows(txns)
                    }
                }
            }
        }
        // First visible item index 0 is the header; row i is txns[i - 1].
        fun firstRowId() = rule.runOnIdle { txns[state.firstVisibleItemIndex - 1].id }
        rule.runOnIdle { state.requestScrollToItem(11) }
        val seen = mutableListOf(firstRowId())
        rule.runOnIdle { txns = newest(41, 43) + txns }       // traffic while the list is shown
        seen += firstRowId()
        rule.runOnIdle { selected = "txn" }                   // open a request detail
        rule.runOnIdle { txns = newest(44, 48) + txns }       // traffic while the detail is open
        rule.runOnIdle { selected = null }                    // Back to list
        seen += firstRowId()
        return seen
    }

    @Test fun keyedRowsKeepTheVisibleRowWhenTrafficArrives() {
        val seen = firstRowIdsAsTrafficArrives { txns -> mitmRows(txns) { Text(it.path, Modifier.height(40.dp)) } }
        assertEquals(listOf(30L, 30L, 30L), seen)
    }

    @Test fun unkeyedRowsShiftWhenTrafficArrives() {
        // Control for the test: the previous `items(txns)` keeps the index, so the rows move.
        val seen = firstRowIdsAsTrafficArrives { txns -> items(txns) { Text(it.path, Modifier.height(40.dp)) } }
        assertEquals(30L, seen[0])
        assertNotEquals(30L, seen[1])
        assertNotEquals(30L, seen[2])
    }
}
