package app.brucia.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.brucia.DeviceState
import app.brucia.DeviceState.waitUntil
import app.brucia.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ConnectionService on a real Android runtime. MainActivity is in the foreground (as on Connect),
 * so the service may upgrade to foreground. Observed from outside: the posted notification, the
 * ActivityManager service record (`stopIfKilled`, starts) and the PowerManager wake lock list.
 * No USB device is involved: the service only receives the owner/status commands.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionServiceInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private lateinit var activity: ActivityScenario<MainActivity>

    private val companion = ConnectionServiceCommand.OWNER_COMPANION

    @Before
    fun setUp() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        stopServiceCompletely()
    }

    @After
    fun tearDown() {
        stopServiceCompletely()
        activity.close()
    }

    @Test
    fun companionStartShowsForegroundNotificationNotStickyWithWakeLock() {
        ConnectionService.start(ctx, companion, "Connected", "wifi, gps")
        waitUntil("connection notification") { notification() != null }
        val n = notification()!!
        assertEquals("Brucia - Connected", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Streams: wifi, gps", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue("posted as the foreground-service notification", (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0)
        assertTrue("ongoing", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        DeviceState.awaitStartsHandled()
        val record = DeviceState.serviceRecord()
        assertTrue("service running: $record", record.isNotEmpty())
        assertTrue("isForeground=true in: $record", record.contains("isForeground=true"))
        // START_NOT_STICKY on the last start: the system will not restart it after process death.
        assertTrue("stopIfKilled=true in: $record", record.contains("stopIfKilled=true"))
        waitUntil("wake lock held") { DeviceState.wakeLockHeld(WAKE_LOCK) }
    }

    @Test
    fun companionStopRemovesNotificationReleasesWakeLockAndStops() {
        ConnectionService.start(ctx, companion, "Connected", "")
        waitUntil("connection notification") { notification() != null }
        assertEquals("No active streams", notification()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        waitUntil("wake lock held") { DeviceState.wakeLockHeld(WAKE_LOCK) }

        ConnectionService.stop(ctx, companion)
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() }
        awaitNotificationRemoved()
        assertFalse("wake lock released", DeviceState.wakeLockHeld(WAKE_LOCK))
        assertNull(ConnectionService.live)
    }

    @Test
    fun startWithoutStatusStopsWithoutNotificationOrWakeLock() {
        ctx.startService(Intent(ctx, ConnectionService::class.java))
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() && ConnectionService.live == null }
        assertNull(notification())
        assertFalse(DeviceState.wakeLockHeld(WAKE_LOCK))
    }

    /** System restart after process death: fresh instance, no owner, start delivered with a null intent. */
    @Test
    fun nullIntentOnFreshInstanceShowsNothingTakesNoWakeLockNotSticky() {
        val created = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = created.countDown()
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onNullBinding(name: ComponentName?) = created.countDown()
        }
        // Bind only creates the instance (onBind returns null) without delivering any start.
        assertTrue(ctx.bindService(Intent(ctx, ConnectionService::class.java), conn, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("service created", created.await(10, TimeUnit.SECONDS))
            val svc = ConnectionService.live
            assertNotNull(svc)
            val result = onMain { svc!!.onStartCommand(null, 0, 1) }
            assertEquals(Service.START_NOT_STICKY, result)
            assertNull("no connection advertised", notification())
            assertFalse("no wake lock", DeviceState.wakeLockHeld(WAKE_LOCK))
        } finally {
            ctx.unbindService(conn)
        }
        waitUntil("service destroyed after unbind") { ConnectionService.live == null }
    }

    /** A null-intent start while a link is shown (stale state) ends it: notification and wake lock gone. */
    @Test
    fun nullIntentWhileShownStopsServiceAndReleasesWakeLock() {
        ConnectionService.start(ctx, companion, "Connected", "")
        waitUntil("connection notification") { notification() != null }
        waitUntil("wake lock held") { DeviceState.wakeLockHeld(WAKE_LOCK) }
        DeviceState.awaitStartsHandled()
        val lastStartId = DeviceState.lastStartId(DeviceState.serviceRecord())
        val svc = ConnectionService.live!!
        assertEquals(Service.START_NOT_STICKY, onMain { svc.onStartCommand(null, 0, lastStartId) })
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() }
        awaitNotificationRemoved()
        assertFalse(DeviceState.wakeLockHeld(WAKE_LOCK))
    }

    @Test
    fun ownerRulesCompanionVersusFlashTokens() {
        ConnectionService.start(ctx, companion, "Connected", "")
        waitUntil("companion shown") { title() == "Brucia - Connected" }

        // A flash takes over the shown companion link.
        ConnectionService.start(ctx, ConnectionServiceCommand.flashOwner(5), "Flashing", "")
        waitUntil("flash 5 shown") { title() == "Brucia - Flashing" }
        assertEquals(FLASH_TEXT, text())

        // Companion update / stop and a stale flash stop / older flash start are ignored.
        ConnectionService.update(ctx, companion, "Connected again", "wifi")
        ConnectionService.stop(ctx, companion)
        ConnectionService.stop(ctx, ConnectionServiceCommand.flashOwner(3))
        ConnectionService.start(ctx, ConnectionServiceCommand.flashOwner(4), "Old flash", "")
        settle()
        assertEquals("Brucia - Flashing", title())
        assertEquals(FLASH_TEXT, text())
        assertTrue("service still running", DeviceState.serviceRecord().isNotEmpty())
        assertTrue("flash keeps the wake lock", DeviceState.wakeLockHeld(WAKE_LOCK))

        // The same flash may update; a newer flash token replaces it.
        ConnectionService.update(ctx, ConnectionServiceCommand.flashOwner(5), "Flashing 50%", "")
        waitUntil("flash 5 update") { title() == "Brucia - Flashing 50%" }
        ConnectionService.start(ctx, ConnectionServiceCommand.flashOwner(6), "Flashing retry", "")
        waitUntil("flash 6 shown") { title() == "Brucia - Flashing retry" }

        // The replaced flash can no longer stop it; its successor can.
        ConnectionService.stop(ctx, ConnectionServiceCommand.flashOwner(5))
        settle()
        assertEquals("Brucia - Flashing retry", title())
        ConnectionService.stop(ctx, ConnectionServiceCommand.flashOwner(6))
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() }
        awaitNotificationRemoved()
        assertFalse(DeviceState.wakeLockHeld(WAKE_LOCK))
    }

    private fun notification(): Notification? =
        nm.activeNotifications.firstOrNull { it.id == NOTIF_ID && it.packageName == ctx.packageName }?.notification

    /**
     * The stopped service's notification must disappear. Bounded poll, not a single read: the
     * service calls stopForeground(STOP_FOREGROUND_REMOVE) before stopSelf, but system_server
     * drops the service record synchronously while NotificationManagerService cancels the
     * notification later on its own handler, so right after the record is gone the notification
     * can still be listed for a moment. A notification left behind (e.g. STOP_FOREGROUND_DETACH)
     * never goes away and fails here after the deadline.
     */
    private fun awaitNotificationRemoved() =
        waitUntil("notification removed", detail = { "still listed: ${notification()}" }) { notification() == null }

    private fun title(): String? = notification()?.extras?.getString(Notification.EXTRA_TITLE)
    private fun text(): String? = notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Waits until every start sent so far was delivered and returned, then lets the main thread idle. */
    private fun settle() {
        Thread.sleep(300)
        DeviceState.awaitStartsHandled()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun stopServiceCompletely() {
        ctx.stopService(Intent(ctx, ConnectionService::class.java))
        waitUntil("service gone") { DeviceState.serviceRecord().isEmpty() && ConnectionService.live == null }
        waitUntil("notification gone") { notification() == null }
    }

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private companion object {
        const val NOTIF_ID = 42
        const val WAKE_LOCK = "brucia:connection"
        const val FLASH_TEXT = "Firmware update in progress - keep the device plugged in"
    }
}
