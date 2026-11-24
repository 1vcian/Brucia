package app.brucia.features.gadgets

import app.brucia.wardrive.DeviceCaps

/** Only the state displayed by the existing Gadgets page. Null caps mean unknown. */
internal data class GadgetsState(
    val connected: Boolean,
    val caps: DeviceCaps?,
    val ibuttonStatus: String,
    val ibuttonBusy: Boolean,
    val clockStatus: String,
    val speakerStatus: String,
    val speakerFile: String,
    val micStatus: String,
    val espnowStatus: String,
)

/** Explicit operations for the page, not access to an application-wide ViewModel. */
internal data class GadgetsActions(
    val connect: () -> Unit,
    val readIButton: () -> Unit,
    val writeIButton: (String) -> Unit,
    val stopIButton: () -> Unit,
    val getClock: () -> Unit,
    val syncClock: () -> Unit,
    val playTone: (Int, Int) -> Unit,
    val playFile: (String) -> Unit,
    val stopFile: () -> Unit,
    val recordMic: (Int) -> Unit,
    val sendEspNow: (String) -> Unit,
)
