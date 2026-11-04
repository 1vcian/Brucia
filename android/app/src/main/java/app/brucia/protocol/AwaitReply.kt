package app.brucia.protocol

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Register before sending; release callback ownership on timeout, cancellation or send failure.
 * [onTimeout] runs once when no reply arrived within [timeoutMs] (not on cancellation).
 */
internal suspend fun <T> awaitReply(
    pending: MutableMap<Int, (Boolean, T) -> Unit>, id: Int, timeoutMs: Long,
    timeoutValue: T, onTimeout: () -> Unit = {}, send: () -> Unit,
): Pair<Boolean, T> = try {
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            pending[id] = { ok, value ->
                if (continuation.isActive) continuation.resumeWith(Result.success(ok to value))
            }
            continuation.invokeOnCancellation { pending.remove(id) }
            send()
        }
    } ?: run {
        onTimeout()
        false to timeoutValue
    }
} finally {
    pending.remove(id)
}
