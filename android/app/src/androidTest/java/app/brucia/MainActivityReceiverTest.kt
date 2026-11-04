package app.brucia

import android.Manifest
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.brucia.DeviceState.registeredReceivers
import app.brucia.DeviceState.waitUntil
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity receiver lifecycle across recreation, observed through the system's registered
 * receiver list (no hooks): exactly one USB permission receiver while the Activity exists, none
 * after destroy; the attach receiver follows resume/pause.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityReceiverTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    @Test
    fun recreateKeepsOneUsbPermissionReceiverAndDestroyUnregistersIt() {
        assertEquals("no leftover receiver before launch", 0, registeredReceivers(USB_PERMISSION))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitUntil("permission receiver registered") { registeredReceivers(USB_PERMISSION) == 1 }
        assertEquals(1, registeredReceivers(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        repeat(2) {
            scenario.recreate()
            assertEquals("one permission receiver after recreate", 1, registeredReceivers(USB_PERMISSION))
            assertEquals("one attach receiver after recreate", 1, registeredReceivers(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        }

        // Paused (still created): the attach receiver goes, the permission receiver stays.
        scenario.moveToState(Lifecycle.State.STARTED)
        assertEquals(1, registeredReceivers(USB_PERMISSION))
        assertEquals(0, registeredReceivers(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        scenario.close()
        assertEquals("permission receiver unregistered on destroy", 0, registeredReceivers(USB_PERMISSION))
        assertEquals(0, registeredReceivers(UsbManager.ACTION_USB_DEVICE_ATTACHED))
    }

    private companion object {
        const val USB_PERMISSION = "app.brucia.USB_PERMISSION"
    }
}
