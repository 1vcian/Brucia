package app.brucia.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** APK download progress; release metadata belongs to ReleaseUpdateService. */
data class AppDownloadState(
    val downloading: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
)

/**
 * Single-flight APK download and installer handoff. A request while one runs is refused (null),
 * from any thread. The URL and integrity passed to [start] stay fixed for that download. Job or
 * [scope] cancellation stops the copy at the next read, never reaches [install], and always ends
 * with `downloading = false`, including a job cancelled before its body started.
 */
class AppDownloadService(
    private val scope: CoroutineScope,
    private val download: (url: String, integrity: AssetIntegrity, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) -> File,
    private val install: suspend (File) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(AppDownloadState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var active: Any? = null

    fun start(url: String, integrity: AssetIntegrity): Job? {
        val run = Any()
        synchronized(lock) {
            if (active != null) return null
            active = run
            mutableState.value = AppDownloadState(downloading = true)
        }
        val job = scope.launch(dispatcher) {
            val error = try {
                val apk = download(url, integrity, { d, t -> progress(run, d, t) }, { !isActive })
                ensureActive()
                install(apk)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: InterruptedException) {
                CANCELLED
            } catch (e: IntegrityException) {
                "${e.message}. Nothing was installed."
            } catch (e: Exception) {
                e.message ?: "Download failed"
            }
            end(run, error)
        }
        job.invokeOnCompletion { cause -> if (cause != null) end(run, CANCELLED) }
        return job
    }

    /** Clear a finished result; never hides a running download. */
    fun reset() {
        synchronized(lock) { if (active == null) mutableState.value = AppDownloadState() }
    }

    private fun progress(run: Any, done: Long, total: Long) {
        synchronized(lock) { if (active === run) mutableState.value = mutableState.value.copy(downloaded = done, total = total) }
    }

    private fun end(run: Any, error: String?) {
        synchronized(lock) {
            if (active !== run) return
            active = null
            mutableState.value = mutableState.value.copy(downloading = false, error = error)
        }
    }

    private companion object { const val CANCELLED = "Download cancelled." }
}
