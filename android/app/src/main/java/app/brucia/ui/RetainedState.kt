package app.brucia.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

/**
 * In-memory values kept across Activity recreation (rotation, window resize, fold/unfold): no
 * Bundle size limit and no Parcelable requirement, so it fits scan results, decoded images and
 * editor buffers that rememberSaveable should not carry. Process death still clears it.
 *
 * Each owner registers under its own id and calls [release] when it leaves composition; the value
 * is dropped unless the Activity is only being recreated for a configuration change.
 */
class RetainedValues {
    private val values = HashMap<String, Any?>()

    val size: Int get() = values.size

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrPut(id: String, init: () -> T): T = values.getOrPut(id) { init() } as T

    /** The owner of [id] left composition: keep its value only across a configuration change. */
    fun release(id: String, changingConfigurations: Boolean) {
        if (!changingConfigurations) values.remove(id)
    }

    fun clear() = values.clear()
}

/**
 * Activity-scoped holder of [RetainedValues] and of the [ParkedLeaves] of [LeaveEffect] (survives
 * configuration changes like any ViewModel).
 */
class RetainedStateViewModel : ViewModel() {
    val values = RetainedValues()
    val leaves = ParkedLeaves()
    override fun onCleared() {
        values.clear()
        // The Activity finished for good before a recreated UI could claim these: leave for real.
        leaves.settle()
    }
}

/**
 * Like `remember { init() }`, but the value also survives a configuration change. Leaving the
 * screen for good (another tool, Back, finish) forgets it exactly as `remember` would, so a tool
 * opened again starts clean. The per-call-site id is itself saved, which keeps two instances of the
 * same composable (or a screen re-entered in the same frame) from sharing a value.
 */
@Composable
fun <T> rememberRetained(init: () -> T): T {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val store = viewModel<RetainedStateViewModel>().values
    val activity = LocalActivity.current
    val value = remember(id) { store.getOrPut(id, init) }
    DisposableEffect(id) {
        onDispose { store.release(id, changingConfigurations = activity?.isChangingConfigurations == true) }
    }
    return value
}

/**
 * `DisposableEffect(Unit) { onDispose { onLeave() } }` that is skipped while the Activity is only
 * being recreated for a configuration change, so work owned by a ViewModel (a running scan, a device
 * stream, a parse) keeps going across rotation and resize but still stops when the user leaves the
 * screen. A skipped [onLeave] is parked: the same screen composing again in the recreated Activity
 * (same saved id) claims it back, otherwise [SettleParkedLeaves] runs it once the new UI has
 * composed, so a tool left while the Activity was being recreated still stops its work.
 */
@Composable
fun LeaveEffect(onLeave: () -> Unit) {
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val leaves = viewModel<RetainedStateViewModel>().leaves
    val activity = LocalActivity.current
    val latest by rememberUpdatedState(onLeave)
    DisposableEffect(id) {
        leaves.enter(id)
        onDispose { leaves.leave(id, changingConfigurations = activity?.isChangingConfigurations == true) { latest() } }
    }
}

/**
 * Call once at the root of the Activity's content. After the first two frames of a (re)created UI
 * (every screen restored from the saved state has composed and been laid out by then), runs the
 * [LeaveEffect] actions no screen claimed back.
 */
@Composable
fun SettleParkedLeaves() {
    val leaves = viewModel<RetainedStateViewModel>().leaves
    LaunchedEffect(leaves) {
        withFrameNanos { }
        withFrameNanos { }
        leaves.settle()
    }
}
