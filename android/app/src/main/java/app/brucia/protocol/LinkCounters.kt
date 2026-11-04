package app.brucia.protocol

import java.util.concurrent.atomic.AtomicLong

/**
 * App-side companion link counters since the app started (all links, kept across reconnects).
 * Local only: never sent anywhere; shown in Diagnostics and written into log exports/reports.
 * Each counter is one atomic increment on the reader/writer path (no allocation); Long does not
 * overflow in practice, and [snapshot] reads a consistent-enough view for display.
 */
class LinkCounters {
    private val links = AtomicLong()
    private val framesReceived = AtomicLong()
    private val framesSent = AtomicLong()
    private val malformedFrames = AtomicLong()
    private val invalidLengthFrames = AtomicLong()
    private val timeouts = AtomicLong()
    private val unmatchedReplies = AtomicLong()

    /** A companion link was opened. */
    fun noteLink() { links.incrementAndGet() }
    /** A complete, valid-length frame was decoded into a JSON object. */
    fun noteFrameReceived() { framesReceived.incrementAndGet() }
    /** A frame was handed to the transport. */
    fun noteFrameSent() { framesSent.incrementAndGet() }
    /** A complete frame whose payload is not a JSON object; skipped, the next boundary is known. */
    fun noteMalformedFrame() { malformedFrames.incrementAndGet() }
    /** A length prefix of 0 or above the frame limit; the buffer is dropped and the link resets. */
    fun noteInvalidLength() { invalidLengthFrames.incrementAndGet() }
    /** An awaited command got no reply within its timeout. */
    fun noteTimeout() { timeouts.incrementAndGet() }
    /** An ACK/ERR arrived with no pending request (late after a timeout/cancel, or unsolicited). */
    fun noteUnmatchedReply() { unmatchedReplies.incrementAndGet() }

    fun snapshot() = LinkCountersSnapshot(
        links = links.get(),
        framesReceived = framesReceived.get(),
        framesSent = framesSent.get(),
        malformedFrames = malformedFrames.get(),
        invalidLengthFrames = invalidLengthFrames.get(),
        timeouts = timeouts.get(),
        unmatchedReplies = unmatchedReplies.get(),
    )
}

data class LinkCountersSnapshot(
    val links: Long = 0,
    val framesReceived: Long = 0,
    val framesSent: Long = 0,
    val malformedFrames: Long = 0,
    val invalidLengthFrames: Long = 0,
    val timeouts: Long = 0,
    val unmatchedReplies: Long = 0,
) {
    /** Display/export lines for the app side of Diagnostics. */
    fun lines(): List<String> = listOf(
        "App link since app start: $links link(s), $framesReceived frames in, $framesSent frames out",
        "App skipped frames: $malformedFrames malformed, $invalidLengthFrames invalid length (link reset)",
        "App commands: $timeouts timed out, $unmatchedReplies replies with no pending command",
    )
}
