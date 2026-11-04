package app.brucia.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Metadata and selectable assets are published and invalidated as one value. */
data class ReleaseUpdateState(
    val current: String = "",
    val checking: Boolean = false,
    val release: ReleaseCatalog.Release? = null,
    val selectedBoard: ReleaseCatalog.Board? = null,
    val error: String? = null,
) {
    val latest: String get() = release?.tag.orEmpty()
    val notes: String get() = release?.notes.orEmpty()
    val apkUrl: String? get() = release?.apk
    val apkIntegrity: AssetIntegrity get() = release?.apkIntegrity ?: AssetIntegrity.NONE
    val newer: Boolean get() = AppUpdater.isNewer(current, latest)
    val boards: List<ReleaseCatalog.Board> get() = release?.boards.orEmpty()
    val firmwareUrl: String? get() = boards.firstOrNull { it.env == SUPPORTED_BOARD }?.url
    // An explicit unsupported selection must never fall back to another board.
    val flashBoard: ReleaseCatalog.Board? get() = if (selectedBoard == null) boards.firstOrNull { it.env == SUPPORTED_BOARD }
        else selectedBoard.takeIf { it.env == SUPPORTED_BOARD && it in boards }
    val flashUrl: String? get() = flashBoard?.url
    /** Expected size/digest of the image behind [flashUrl], from the same snapshot. */
    val flashIntegrity: AssetIntegrity get() = flashBoard?.integrity ?: AssetIntegrity.NONE

    companion object { const val SUPPORTED_BOARD = "lilygo-t-embed-cc1101" }
}

/** Call refresh/select on the owning (UI) scope. Blocking HTTP runs on [dispatcher]. */
class ReleaseUpdateService(
    private val scope: CoroutineScope,
    private val fetch: () -> ReleaseCatalog.Release,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(ReleaseUpdateState())
    val state = mutableState.asStateFlow()

    /** Single-flight refresh. Old assets stop being actionable before any IO starts. */
    fun refresh(current: String): Job? {
        if (state.value.checking) return null
        val pending = ReleaseUpdateState(current = current, checking = true)
        mutableState.value = pending
        return scope.launch {
            try {
                val snapshot = withContext(dispatcher) { fetch() }
                ensureActive()
                mutableState.compareAndSet(pending, pending.copy(checking = false, release = snapshot))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.compareAndSet(pending, pending.copy(checking = false, error = e.message ?: "Check failed"))
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    mutableState.compareAndSet(pending, pending.copy(checking = false, error = "Check cancelled"))
                }
            }
        }
    }

    /** Reject clicks from a menu rendered for an obsolete release. null means Auto. */
    fun select(board: ReleaseCatalog.Board?): Boolean {
        val before = state.value
        if (before.checking || before.release == null || (board != null && board !in before.boards)) return false
        return mutableState.compareAndSet(before, before.copy(selectedBoard = board))
    }
}
