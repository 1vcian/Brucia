package app.brucia

import app.brucia.audio.SpeakerService
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SpeakerServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val notConnected = "Connect the device over USB first."

    private fun CoroutineScope.svc(r: CommandRunner = runner) = SpeakerService(this, r, { connected })
    private fun SpeakerService.state() = status.value to busy.value

    private suspend fun SpeakerService.result(reply: Pair<Boolean, String>, call: SpeakerService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    /** Asserts [call] sets [text] at once and sends nothing. */
    private suspend fun SpeakerService.rejects(text: String, call: SpeakerService.() -> Unit) {
        val n = runner.calls.size
        call()
        assertEquals(text to false, state())
        delay(20)
        assertEquals(n, runner.calls.size)
    }

    @Test fun volumeClampsTextsAndTimeout() = runBlocking {
        val svc = svc()
        assertEquals("Volume 100%", svc.result(true to "") { setVolume(150) })
        assertEquals(100, svc.volume.value)
        assertEquals("volume 0%", svc.result(true to "volume 0%") { setVolume(-3) })
        assertEquals(0, svc.volume.value)
        assertEquals("Volume failed.", svc.result(false to "") { setVolume(40) })
        assertEquals("busy", svc.result(false to "busy") { setVolume(41) })
        assertEquals(listOf("speaker_volume 100" to 10_000L, "speaker_volume 0" to 10_000L,
            "speaker_volume 40" to 10_000L, "speaker_volume 41" to 10_000L), runner.calls.toList())
    }

    @Test fun volumeUpdatesWithoutConnection() = runBlocking {
        connected = false
        val svc = svc()
        assertEquals(100, svc.volume.value)
        svc.rejects(notConnected) { setVolume(42) }
        assertEquals(42, svc.volume.value)
    }

    @Test fun toneTextsRejectionsAndTimeout() = runBlocking {
        val svc = svc()
        svc.tone(440, 500)
        assertEquals("Playing 440Hz for 500ms..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Tone played.", svc.status.value)
        assertEquals("played 20000Hz for 10ms", svc.result(true to "played 20000Hz for 10ms") { tone(20_000, 10) })
        assertEquals("Tone failed.", svc.result(false to "") { tone(1, 1) })
        assertEquals(listOf("speaker_tone 440 500" to 15_000L, "speaker_tone 20000 10" to 15_000L,
            "speaker_tone 1 1" to 15_000L), runner.calls.toList())
        for ((f, ms) in listOf(0 to 100, -1 to 100, 440 to 0, 440 to -5)) {
            svc.rejects("Enter a frequency and duration.") { tone(f, ms) }
        }
        svc.rejects(SpeakerService.TONE_USAGE) { tone(20_001, 100) }
        assertEquals("usage: speaker_tone <freqHz 1..20000> <ms>", SpeakerService.TONE_USAGE)
        connected = false
        svc.rejects(notConnected) { tone(0, 0) }
    }

    @Test fun playFileTextsAndTimeout() = runBlocking {
        val svc = svc()
        svc.playFile("  /music/a.wav ")
        assertEquals("Playing /music/a.wav..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Playback started.", svc.status.value)
        assertEquals("file not found", svc.result(false to "file not found") { playFile("x.mp3") })
        assertEquals("Playback failed.", svc.result(false to "") { playFile("y.mp3") })
        assertEquals(listOf("speaker_play /music/a.wav" to 600_000L, "speaker_play x.mp3" to 600_000L,
            "speaker_play y.mp3" to 600_000L), runner.calls.toList())
        svc.rejects("Enter a file path first.") { playFile("   ") }
        connected = false
        svc.rejects(notConnected) { playFile("a.wav") }
    }

    @Test fun songLifecycleKeepsStatusOnSuccessAndClearsOnFailOrStop() = runBlocking {
        val svc = svc()
        assertEquals("Volume 50%", svc.result(true to "") { setVolume(50) })
        assertEquals("Volume 50%", svc.result(true to "playing") {
            playSong("Mario", "mario:d=4,o=5,b=100:e")
            assertEquals("Mario", songPlaying.value)
        })
        assertEquals("Mario", svc.songPlaying.value)
        assertEquals("Volume 50%", svc.result(true to "stopped") { stopSong() })
        assertEquals("", svc.songPlaying.value)
        assertEquals("song failed", svc.result(false to "song failed") { playSong("Bad", "x") })
        assertEquals("", svc.songPlaying.value)
        assertEquals("Song failed.", svc.result(false to "") { playSong("Bad", "y") })
        assertEquals(listOf("speaker_volume 50", "speaker_song mario:d=4,o=5,b=100:e", "speaker_stop",
            "speaker_song x", "speaker_song y"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 10_000L })
    }

    @Test fun songWhileDisconnected() = runBlocking {
        val svc = svc()
        svc.playSong("A", "a")
        until { runner.calls.size == 1 }
        connected = false
        svc.rejects(notConnected) { playSong("B", "b") }
        assertEquals("A", svc.songPlaying.value)
        svc.stopSong()
        assertEquals("" to (notConnected to false), svc.songPlaying.value to svc.state())
        delay(20)
        assertEquals(1, runner.calls.size)
    }

    @Test fun supersededSongStartNeverClearsNewerSong() = runBlocking {
        // Replies that outlive cancellation, so the superseded start still maps its result. A
        // detached scope keeps a failed assertion from waiting on those uncancellable calls.
        val conts = CopyOnWriteArrayList<Continuation<Pair<Boolean, String>>>()
        val scope = CoroutineScope(coroutineContext + Job())
        val svc = scope.svc { _, _ -> suspendCoroutine { conts += it } }
        svc.playSong("A", "a")
        until { conts.size == 1 }
        svc.stopSong()
        until { conts.size == 2 }
        svc.playSong("C", "c")
        until { conts.size == 3 }
        conts[0].resume(false to "late")
        conts[1].resume(true to "stopped")
        delay(20)
        assertEquals("C" to ("" to true), svc.songPlaying.value to svc.state())
        conts[2].resume(false to "")
        until { !svc.busy.value }
        assertEquals("" to "Song failed.", svc.songPlaying.value to svc.status.value)
        scope.cancel()
    }

    @Test fun dtmfKeepsStatusAndIsSkippedWhileDisconnected() = runBlocking {
        val svc = svc()
        assertEquals("Tone failed.", svc.result(false to "") { tone(440, 100) })
        assertEquals("Tone failed.", svc.result(true to "dtmf") { dtmf('5') })
        assertEquals("Tone failed.", svc.result(false to "usage") { dtmf('#', 400) })
        assertEquals(listOf("speaker_dtmf 5 200", "speaker_dtmf # 400"), runner.cmds.drop(1))
        assertTrue(runner.calls.drop(1).all { it.second == 10_000L })
        connected = false
        svc.rejects("Tone failed.") { dtmf('1') }
    }

    @Test fun dtmfAndStopSongDoNotTakeOverAPendingTone() = runBlocking {
        val svc = svc()
        svc.tone(440, 5_000)
        until { runner.calls.size == 1 }
        svc.dtmf('7')
        svc.stopSong()
        until { runner.calls.size == 3 }
        runner.reply(1, true, "dtmf")
        runner.reply(2, true, "stopped")
        delay(20)
        assertEquals("Playing 440Hz for 5000ms..." to true, svc.state())
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Tone played.", svc.status.value)
    }

    @Test fun stopSupersedesWaitingCommand() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.tone(440, 5_000)
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "late")
        delay(20)
        assertEquals("Stopped." to false, svc.state())
    }
}
