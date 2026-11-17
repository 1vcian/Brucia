package app.brucia

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.brucia.wardrive.WardriveViewModel
import app.brucia.wifi.PcapWriter
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real VM + import service + ContentResolver, offline; synthetic input only. */
@RunWith(AndroidJUnit4::class)
class WifiTargetImportFlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    @Test
    fun offlineDocumentImportClearsTheSameTargetThatTextImportAndExportUse() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File.createTempFile("target-import-", ".pcap", context.cacheDir)
        try {
            file.writeBytes(PcapWriter.globalHeader()) // valid empty capture, no private data
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: WardriveViewModel
                fun selectText() = scenario.onActivity {
                    vm.importPmkid("00".repeat(16) + "*001122334455*66772b112233*4e6574")
                }
                scenario.onActivity { vm = ViewModelProvider(it)[WardriveViewModel::class.java] }
                selectText()
                assertEquals("Net (PMKID)", vm.handshakeInfo.value)
                assertTrue(vm.exportHashcat()!!.startsWith("WPA*01*"))

                scenario.onActivity { vm.importPcap(Uri.fromFile(file)) }
                DeviceState.waitUntil("empty document selection") { vm.handshakeInfo.value == "no handshake found in file" }
                assertNull(vm.exportHashcat())

                selectText()
                assertNotNull(vm.exportHashcat())
                assertTrue(file.delete())
                scenario.onActivity { vm.importPcap(Uri.fromFile(file)) }
                DeviceState.waitUntil("provider error selection") { vm.handshakeInfo.value == "read error" }
                assertNull(vm.exportHashcat())
                assertNotEquals("imports do not require a connection", "Connected", vm.status.value)
            }
        } finally {
            file.delete()
        }
    }
}
