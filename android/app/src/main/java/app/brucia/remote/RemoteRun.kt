package app.brucia.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * One Remote attempt as a single job: [start]'s `enter` (hand-over + confirmed `companion_exit`)
 * runs first, and only on success the mirror watchdog is launched as a child of the same job,
 * next to the mirror loop. A failed entry therefore never leaves a watchdog behind, a retry cannot
 * orphan an earlier one, and [stop] (Stop / disconnect) ends both at once (F34). The watchdog also
 * ends when the mirror loop returns.
 */
class RemoteRun(private val scope: CoroutineScope, private val context: CoroutineContext = EmptyCoroutineContext) {
    private var job: Job? = null

    /** Replace any previous attempt with a new one; `enter` returning false aborts before any watchdog. */
    @Synchronized fun start(enter: suspend () -> Boolean, watchdog: suspend () -> Unit, mirror: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(context) {
            if (!enter()) return@launch
            val wd = launch { watchdog() }
            try { mirror() } finally { wd.cancel() }
        }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
    }
}
