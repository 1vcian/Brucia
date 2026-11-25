package app.brucia

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.brucia.features.BuiltinFeatures
import app.brucia.features.FeatureScreenBinding
import app.brucia.features.FeatureScreenRegistry
import app.brucia.features.gadgets.GadgetsRoute
import app.brucia.features.pcap.pcapDissectFeature
import app.brucia.ui.ConsoleScreen
import app.brucia.ui.FeatureScreen
import app.brucia.wardrive.WardriveViewModel

/**
 * Application composition root for existing screens. The registry/host do not depend on the
 * ViewModel: only these legacy adapters do, until each feature gains its own small inputs.
 * Call during composition with current values/callbacks; do not remember stale connection or
 * update state. New screens register by stable catalog ID without changing MainActivity's router.
 */
internal fun builtinFeatureScreens(
    vm: WardriveViewModel,
    connected: Boolean,
    updateLabel: String,
    onConnect: () -> Unit,
    onFlash: () -> Unit,
    onExportLog: () -> Unit,
    onNavigate: (String) -> Unit,
): FeatureScreenRegistry<FeatureScreen> = FeatureScreenRegistry.of(
    BuiltinFeatures.routeCatalog,
    listOf(
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.wardrive", screen = { BruciaScreen(vm, onConnect, onGoCrack = { onNavigate("org.brucia.wifi.crack") }) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.bleir.ir", screen = { IrScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.subghz.rf", screen = { SubGhzScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.nfc.nfc", screen = { NfcScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.bleir.badusb", screen = { BadUsbScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.bleir.ble", screen = { BleScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.deviceinfo", screen = { DeviceScreen(vm, onConnect, updateLabel = updateLabel, onOpenUpdates = { onNavigate("org.brucia.device.updates") }) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.crack", screen = { CrackScreen(vm) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.console", screen = {
            val log by vm.console.collectAsState()
            ConsoleScreen(
                connected = connected,
                log = log,
                onConnect = onConnect,
                onSend = vm::sendConsole,
                onClear = vm::clearConsole,
                onExportLog = onExportLog,
            )
        }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.remote", screen = { RemoteScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.files", screen = { FilesScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.sniffer", screen = { StationSnifferScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.updates", screen = { UpdatesScreen(vm, onFlash = onFlash) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.24ghz.nrf24", handlesCapability = true, screen = { Nrf24Screen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.radio.fm", screen = { FmScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.probes", screen = { WifiProbeScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.spam.all", screen = { SpamScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.spam.spamble", screen = { SpamScreen(vm, onConnect, filter = "ble") }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.spam.spamwifi", screen = { SpamScreen(vm, onConnect, filter = "wifi") }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.spam.spamnfc", screen = { SpamScreen(vm, onConnect, filter = "nfc") }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.spam.spamir", screen = { SpamScreen(vm, onConnect, filter = "ir") }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.monitor", screen = { WifiMonitorScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.mitm", screen = { MitmScreen(vm, connected) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.scripts", screen = { ScriptsScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.gadgets", screen = {
            val device by vm.device.collectAsState()
            GadgetsRoute(
                connected = connected, caps = device?.caps,
                espNowAvailable = device?.caps?.wifi == true && device?.features?.omits(app.brucia.wardrive.DeviceFeatures.ESP_NOW) != true,
                ibutton = vm.ibutton, clock = vm.clock, speaker = vm.speaker,
                mic = vm.micRecorder, espNow = vm.espNow, onConnect = onConnect,
            )
        }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.report", screen = { ReportScreen(vm) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.radio.lora", screen = { LoRaScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.ethernet", screen = { EthernetScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.utilities", screen = { UtilitiesScreen() }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.bleconsole", screen = { BleConsoleScreen(vm) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.audio.audio", screen = { AudioDashboardScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.macros", screen = { MacroScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.rfmap3d", screen = { RfMap3dScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.bugsweep", screen = { BugSweepScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.wids", screen = { WidsScreen(vm, onConnect) }),
        pcapDissectFeature(),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.rffingerprint", screen = { RfFingerprintScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.rftimeline", screen = { RfTimelineScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.tools.geosignals", screen = { GeoSignalsScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.audio.sstv", screen = { SstvScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.audio.tone", screen = { ToneScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.audio.oscilloscope", screen = { OscilloscopeScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.evilportal", screen = { EvilPortalScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.channels", screen = { ChannelAnalyzerScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.networkscan", screen = { NetworkScanScreen() }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.24ghz.espnow", screen = { EspnowScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.mac", screen = { MacScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.connect", screen = { WifiJoinScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.brucegotchi", screen = { BrucegotchiScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.device.theme", screen = { ThemeScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.bleir.irremote", screen = { IrRemoteScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.wifi.karma", screen = { KarmaScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.bleir.bleremote", screen = { BleHidScreen(vm, onConnect) }),
        FeatureScreenBinding<FeatureScreen>("org.brucia.intercept.responder", screen = { ResponderScreen(vm, onConnect) }),
    ),
)
