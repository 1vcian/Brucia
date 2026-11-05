package app.brucia

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandSession
import app.brucia.session.StreamSubscriptions
import app.brucia.transport.Transport
import app.brucia.ui.ParkedLeaves
import app.brucia.wardrive.DeviceCaps
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Device streams across Activity recreation (rotation, resize, fold): the exact SUB frames the
 * device receives when a tool screen is started, recreated, stopped, left, or the link drops.
 *
 * The production pieces are real: [StreamSubscriptions] on a [CommandSession] over a fake
 * transport, and the [ParkedLeaves] behind `LeaveEffect`. [Host] plays the Compose/Activity part
 * the way `LeaveEffect` uses it (saved id per screen, enter on composition, leave on dispose with
 * the Activity's isChangingConfigurations, settle once the recreated UI has composed). Each [Tool]
 * mirrors its screen's start and leave hooks (SubGhzScreen, BleScreen, the Audio tools'
 * MicPcmWhileShown, RfTimelineScreen); StreamLeaveHooksTest checks the screens keep using LeaveEffect.
 */
class StreamRotationTest {
    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        var reply: (JSONObject) -> Unit = {}
        override var isOpen = true
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
        override fun write(bytes: ByteArray) {
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames.add(frame)
            reply(frame)
        }
    }

    private class Link(scope: CoroutineScope) {
        val wire = Wire()
        val client = CompanionClient(wire)
        var session: CommandSession? = CommandSession(client, wire)
        val streams = StreamSubscriptions(scope, { session }, { DeviceCaps(wifi = true, ble = true, cc1101 = true, mic = true) },
            onState = { _, _ -> }, dispatcher = scope.virtualUnconfined, timeoutMs = 5_000)
        val connected get() = session != null

        init {
            // The device ACKs every SUB at once.
            wire.reply = { frame ->
                if (frame.getInt("t") == Kind.SUB) session?.route(JSONObject().put("t", Kind.ACK).put("id", frame.getInt("id"))
                    .put("d", JSONObject().put("ok", true).put("out", "ok")))
            }
        }

        /** SUB frames as "stream on|off", in wire order. */
        fun subs(): List<String> = wire.frames.filter { it.getInt("t") == Kind.SUB }.map {
            val d = it.getJSONObject("d")
            "${d.getString("stream")} ${if (d.getBoolean("on")) "on" else "off"}"
        }

        /** WardriveViewModel.disconnectLink: detach, best-effort OFF on the closing transport. */
        fun disconnect() {
            streams.disconnect { stream -> client.sub(stream, false) }
            session?.close("Disconnected")
            session = null
            wire.isOpen = false
        }
    }

    /** One tool screen: what it starts when composed and what its LeaveEffect stops. */
    private abstract class Tool(val link: Link) {
        open fun composed() {}
        abstract fun leave()
    }

    /** SubGhzScreen: Listen is user-started; LeaveEffect stops raw and spectrum if they may run. */
    private class SubGhzListen(link: Link) : Tool(link) {
        fun listen() = link.streams.set("subghz.raw", true)
        fun stop() = link.streams.set("subghz.raw", false)
        override fun leave() {
            if (link.streams.mayRun("subghz.raw")) link.streams.set("subghz.raw", false)
            if (link.streams.mayRun("subghz.spectrum")) link.streams.set("subghz.spectrum", false)
        }
    }

    /** BleScreen: Scan toggle; LeaveEffect stops ble.scan if it may run. */
    private class BleScan(link: Link) : Tool(link) {
        fun scan(on: Boolean) = link.streams.set("ble.scan", on)
        override fun leave() { if (link.streams.mayRun("ble.scan")) link.streams.set("ble.scan", false) }
    }

    /** Audio tools (MicPcmWhileShown): start on composition unless on (startMicPcm), stop on leave. */
    private class AudioMic(link: Link) : Tool(link) {
        override fun composed() { if (link.connected && !link.streams.isOn("mic.pcm")) link.streams.set("mic.pcm", true) }
        override fun leave() { if (link.streams.mayRun("mic.pcm")) link.streams.set("mic.pcm", false) }
    }

    /** RfTimelineScreen: starts what is not on, remembers (retained) what it started, stops on leave. */
    private class RfTimeline(link: Link) : Tool(link) {
        var bleStartedHere = false // rememberRetained: one object across the recreation
        override fun composed() {
            if (!link.connected) return
            if (!link.streams.isOn("wifi.ap")) link.streams.set("wifi.ap", true)
            if (!link.streams.isOn("ble.scan")) { link.streams.set("ble.scan", true); bleStartedHere = true }
        }
        override fun leave() {
            if (link.streams.isOn("wifi.ap")) link.streams.set("wifi.ap", false)
            if (bleStartedHere) { link.streams.set("ble.scan", false); bleStartedHere = false }
        }
    }

    /** Activity + composition as LeaveEffect sees them. */
    private class Host {
        val leaves = ParkedLeaves()
        private var shown: Pair<String, Tool>? = null

        /** Open a tool: it composes (DisposableEffect body: enter, then its start effect). */
        fun open(tool: Tool) {
            val id = UUID.randomUUID().toString()
            shown = id to tool
            compose(id, tool)
        }

        private fun compose(id: String, tool: Tool) {
            leaves.enter(id)
            tool.composed()
        }

        /** Back / another tool: dispose outside a configuration change. */
        fun leave() {
            val (id, tool) = shown ?: return
            shown = null
            leaves.leave(id, changingConfigurations = false) { tool.leave() }
        }

        /**
         * Rotation: the old Activity disposes everything while changing configurations, the new one
         * restores the saved state; [toolComesBack] false is a tool left while that happened (the
         * restored UI no longer shows it). Then the first frames pass and parked leaves settle.
         */
        fun rotate(toolComesBack: Boolean = true) {
            val current = shown
            if (current != null) {
                val (id, tool) = current
                leaves.leave(id, changingConfigurations = true) { tool.leave() }
                if (toolComesBack) compose(id, tool) else shown = null
            }
            leaves.settle()
        }
    }

    @Test(timeout = 5000) fun listenSurvivesTwoRotationsThenStopSendsOneOff() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        host.rotate()
        assertEquals(listOf("subghz.raw on"), link.subs())
        assertTrue("UI re-binds to the running stream", link.streams.isOn("subghz.raw"))
        host.rotate()
        assertEquals(listOf("subghz.raw on"), link.subs())
        tool.stop()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
        host.leave()
        assertEquals("leaving after Stop sends nothing more", listOf("subghz.raw on", "subghz.raw off"), link.subs())
        assertEquals(0, host.leaves.size)
        assertTrue(link.streams.cleanupStreams().isEmpty())
    }

    @Test(timeout = 5000) fun listenRotateThenLeaveSendsOneOff() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        host.rotate()
        host.leave()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
        assertTrue(link.streams.activeStreams().isEmpty())
    }

    @Test(timeout = 5000) fun toolLeftDuringRotationIsStoppedWhenTheNewUiSettles() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        host.rotate(toolComesBack = false)
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
        assertEquals(0, host.leaves.size)
        host.rotate()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
    }

    @Test(timeout = 5000) fun listenThenDisconnectLeavesNothingRunningAndLaterLeaveSendsNothing() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        link.disconnect()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
        assertTrue(link.streams.cleanupStreams().isEmpty())
        host.rotate()
        host.leave()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
    }

    @Test(timeout = 5000) fun bleScanAcrossRotationThenStopAndLeave() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = BleScan(link)
        host.open(tool)
        tool.scan(true)
        host.rotate()
        host.rotate()
        assertEquals(listOf("ble.scan on"), link.subs())
        tool.scan(false)
        host.leave()
        assertEquals(listOf("ble.scan on", "ble.scan off"), link.subs())
    }

    @Test(timeout = 5000) fun bleScanRotateThenLeaveAndDisconnect() = runVirtual<Unit> {
        for (end in listOf("leave", "disconnect")) {
            val link = Link(this)
            val host = Host()
            val tool = BleScan(link)
            host.open(tool)
            tool.scan(true)
            host.rotate()
            if (end == "leave") host.leave() else link.disconnect()
            assertEquals(end, listOf("ble.scan on", "ble.scan off"), link.subs())
            host.leave()
            assertEquals(end, listOf("ble.scan on", "ble.scan off"), link.subs())
        }
    }

    @Test(timeout = 5000) fun audioMicStartedOnOpenIsNotRestartedByRotation() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        host.open(AudioMic(link))
        host.rotate()
        host.rotate()
        assertEquals(listOf("mic.pcm on"), link.subs())
        host.leave()
        assertEquals(listOf("mic.pcm on", "mic.pcm off"), link.subs())
    }

    @Test(timeout = 5000) fun audioMicThenDisconnectThenLeave() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        host.open(AudioMic(link))
        host.rotate()
        link.disconnect()
        host.rotate()
        host.leave()
        assertEquals(listOf("mic.pcm on", "mic.pcm off"), link.subs())
    }

    @Test(timeout = 5000) fun compositeTimelineStartsOnceAndStopsWhatItStartedOnLeave() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        host.open(RfTimeline(link))
        host.rotate()
        host.rotate()
        assertEquals(listOf("wifi.ap on", "ble.scan on"), link.subs())
        host.leave()
        assertEquals(listOf("wifi.ap on", "ble.scan on", "wifi.ap off", "ble.scan off"), link.subs())
    }

    @Test(timeout = 5000) fun stopAllAfterRotationCoversEveryStreamOnce() = runVirtual<Unit> {
        val link = Link(this)
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        link.streams.set("ble.scan", true)
        host.rotate()
        link.streams.stopAll()
        host.leave()
        assertEquals(listOf("subghz.raw on", "ble.scan on", "subghz.raw off", "ble.scan off").sorted(), link.subs().sorted())
        assertEquals(4, link.subs().size)
    }

    @Test(timeout = 5000) fun unconfirmedStartIsStillStoppedOnLeave() = runVirtual<Unit> {
        val link = Link(this)
        link.wire.reply = {} // the device never answers
        val host = Host()
        val tool = SubGhzListen(link)
        host.open(tool)
        tool.listen()
        clock.advanceBy(6_000) // ACK deadline passes: the radio may or may not run
        assertTrue(link.streams.activeStreams().isEmpty())
        host.rotate()
        assertEquals(listOf("subghz.raw on"), link.subs())
        host.leave()
        assertEquals(listOf("subghz.raw on", "subghz.raw off"), link.subs())
        link.streams.detach()
    }
}
