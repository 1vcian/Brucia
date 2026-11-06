package app.brucia.files

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Owns queued transfers and their local processing. The link is captured by [submit], not its job.
 * Owner keys supersede older consumers of the same result (capture import, editor, destination).
 * The processing mutex also prevents overlapping writes to a destination after cancellation:
 * blocking IO must finish/close before a newer request enters. It cannot roll back a provider write.
 */
class FileTransferService(
    private val scope: CoroutineScope,
    private val connect: () -> FileTransferLink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val processing = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val owners = mutableMapOf<String, Job>()
    private var generation = 0L

    class Request internal constructor(
        private val link: FileTransferLink,
        private val context: CoroutineContext,
    ) {
        fun checkActive() {
            context.ensureActive()
            if (!link.isCurrent()) throw IOException("Connection changed; transfer stopped.")
        }
        suspend fun read(path: String): ByteArray {
            checkActive()
            return link.read(path).also { checkActive() }
        }
        suspend fun write(path: String, bytes: ByteArray) {
            checkActive()
            link.write(path, bytes)
            checkActive()
        }
    }

    @Synchronized fun <T> submit(
        owner: String? = null,
        onDiscard: (T) -> Unit = {},
        work: suspend Request.() -> T,
        onResult: (Result<T>) -> Unit,
    ): Job {
        owner?.let { owners[it]?.cancel() }
        val epoch = generation
        val connection = runCatching { connect() }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var prepared: Result<T>? = null
            var delivered = false
            try {
                val result = try {
                    val link = connection.getOrThrow()
                    val value = processing.withLock {
                        withContext(dispatcher) {
                            val request = Request(link, currentCoroutineContext())
                            request.checkActive()
                            request.work().also {
                                prepared = Result.success(it)
                                request.checkActive()
                            }
                        }
                    }
                    Result.success(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                currentCoroutineContext().ensureActive()
                synchronized(this@FileTransferService) {
                    if (epoch != generation) throw CancellationException("Transfer cancelled")
                    // Even a completed IO result must not publish into a replacement connection.
                    if (connection.getOrNull()?.isCurrent() == false) throw CancellationException("Connection changed")
                    delivered = result.isSuccess
                    onResult(result)
                }
            } finally {
                val value = prepared
                if (!delivered && value != null) withContext(NonCancellable + dispatcher) { onDiscard(value.getOrThrow()) }
            }
        }
        jobs += job
        if (owner != null) owners[owner] = job
        job.invokeOnCompletion {
            synchronized(this) {
                jobs -= job
                if (owner != null && owners[owner] === job) owners.remove(owner)
            }
        }
        job.start()
        return job
    }

    /** Bidirectional cancellation: caller cancellation stops IO; Stop all cancels the caller wait. */
    suspend fun <T> await(owner: String? = null, work: suspend Request.() -> T): T =
        suspendCancellableCoroutine { continuation ->
            val job = submit(owner, work = work) { result ->
                if (continuation.isActive) continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation { job.cancel() }
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) continuation.cancel(cause)
            }
        }

    /** True while any submitted transfer has not completed. */
    val active: Boolean @Synchronized get() = jobs.isNotEmpty()

    /**
     * [cancelAll], then wait until every cancelled transfer has finished, including the upload
     * abort a cancelled upload sends from its cleanup (SessionFileLink.write).
     */
    suspend fun cancelAllAndJoin() {
        val pending = synchronized(this) { jobs.toList() }
        cancelAll()
        pending.forEach { it.join() }
    }

    @Synchronized fun cancelAll() {
        generation++
        val pending = jobs.toList()
        jobs.clear()
        owners.clear()
        pending.forEach { it.cancel() }
    }
}
