package app.brucia.features.gadgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.brucia.audio.MicRecordService
import app.brucia.audio.SpeakerService
import app.brucia.device.ClockService
import app.brucia.device.EspNowService
import app.brucia.ibutton.IButtonService
import app.brucia.wardrive.DeviceCaps

/**
 * Device-backed feature adapter. The app injects its existing service instances so global Stop,
 * disconnect, deadlines and late-reply fencing retain their original owner. Leaving Gadgets
 * does not stop its one-shot operations or playback; rotation only recreates the presentation.
 */
@Composable
internal fun GadgetsRoute(
    connected: Boolean,
    caps: DeviceCaps?,
    ibutton: IButtonService,
    clock: ClockService,
    speaker: SpeakerService,
    mic: MicRecordService,
    espNow: EspNowService,
    onConnect: () -> Unit,
) {
    val ibuttonStatus by ibutton.status.collectAsState()
    val ibuttonBusy by ibutton.busy.collectAsState()
    val clockStatus by clock.status.collectAsState()
    val speakerStatus by speaker.status.collectAsState()
    val speakerFile by speaker.filePlaying.collectAsState()
    val micStatus by mic.status.collectAsState()
    val espnowStatus by espNow.status.collectAsState()
    GadgetsScreen(
        state = GadgetsState(
            connected, caps, ibuttonStatus, ibuttonBusy, clockStatus,
            speakerStatus, speakerFile, micStatus, espnowStatus,
        ),
        actions = GadgetsActions(
            connect = onConnect,
            readIButton = ibutton::read,
            writeIButton = ibutton::write,
            stopIButton = ibutton::stop,
            getClock = clock::get,
            syncClock = { clock.syncFromPhone() },
            playTone = speaker::tone,
            playFile = speaker::playFile,
            stopFile = speaker::stopFile,
            recordMic = mic::record,
            sendEspNow = espNow::send,
        ),
    )
}
