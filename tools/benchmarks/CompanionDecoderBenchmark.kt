import app.brucia.protocol.CompanionClient
import app.brucia.transport.Transport
import java.lang.management.ManagementFactory

/** Synthetic framed bytes, no USB, device, radio or network. Measures the production decoder. */
private class Pipe(val frame: ByteArray, val chunk: Int, val frames: Int) : Transport {
    var offset = 0
    override val isOpen get() = offset < frame.size * frames
    override fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val n = minOf(chunk, buf.size, frame.size * frames - offset)
        repeat(n) { buf[it] = frame[(offset + it) % frame.size] }
        offset += n
        return n
    }
}
fun main() {
    val memory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val tid = Thread.currentThread().id
    val rows = mutableListOf<String>()
    for (payloadSize in listOf(128, 1024, 8192)) for (chunk in listOf(1, 64, 4096)) {
        val prefix = "{\"v\":1,\"t\":3,\"id\":7,\"d\":{\"padding\":\""
        val suffix = "\"}}"
        val payload = (prefix + "x".repeat(payloadSize - prefix.length - suffix.length) + suffix).toByteArray()
        val frame = ByteArray(4) { ((payload.size ushr (8 * it)) and 255).toByte() } + payload
        fun trial(count: Int): Pair<Long, Long> {
            val client = CompanionClient(Pipe(frame, chunk, count))
            val allocated = memory.getThreadAllocatedBytes(tid)
            val start = System.nanoTime()
            repeat(count) { check(client.readMessage(10_000)?.getInt("id") == 7) }
            return (System.nanoTime() - start) to (memory.getThreadAllocatedBytes(tid) - allocated)
        }
        trial(10)
        val count = if (chunk == 1) 20 else 200
        val runs = List(3) { trial(count) }
        val nanos = runs.map { it.first }.sorted()[1]
        val allocated = runs.map { it.second }.sorted()[1] / count
        val rate = frame.size.toDouble() * count * 1e9 / nanos
        rows += "{\"payload_bytes\":$payloadSize,\"read_chunk\":$chunk,\"frames_per_trial\":$count,\"median_bytes_per_second\":$rate,\"allocated_bytes_per_frame\":$allocated,\"elapsed_ns\":${runs.map { it.first }}}"
    }
    println("{\"rows\":[${rows.joinToString(",")}],\"java\":\"${System.getProperty("java.version")}\"}")
}
