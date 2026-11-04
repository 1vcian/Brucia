package app.brucia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.brucia.MainActivity
import app.brucia.R

/**
 * Foreground service that keeps the Brucia process alive and the CPU awake while a device is linked
 * over USB, so capture and streaming keep running when the screen is off or the app is backgrounded.
 * It shows a persistent, silent notification with the current connection status and the active
 * streams, and holds a PARTIAL_WAKE_LOCK (reason-counted via [WakeLockPolicy]) for the session.
 *
 * Started/updated/stopped by the USB device owner (companion link, or a firmware flash so the phone
 * cannot sleep mid-flash) through the [start] / [update] / [stop] helpers, each naming its owner.
 */
/**
 * What a start command means for [ConnectionService]. Pure so the rules are JVM-testable.
 *
 * Every command names the owner it speaks for: [OWNER_COMPANION] for the companion link, or
 * [flashOwner] with the flash operation token while a firmware flash holds the USB device. The
 * service remembers the owner it currently shows ([decide]'s `current`):
 * - a null intent (system restart after the owning process died) or a missing status with no
 *   current owner never shows a connection;
 * - a stop ends the service only for its own owner (or with no owner named), so a companion
 *   disconnect during a flash, or a late stop from an older flash, keeps the flash's wake lock;
 * - while a flash is shown, only that flash or a newer flash token may replace it.
 */
internal object ConnectionServiceCommand {
    sealed interface Decision
    data object Stop : Decision
    /** Not for the current owner: leave notification and wake lock as they are. */
    data object Ignore : Decision
    data class Show(val status: String, val streams: String, val owner: String) : Decision

    const val ACTION_STOP = "app.brucia.service.STOP"
    const val OWNER_COMPANION = "companion"
    private const val FLASH_PREFIX = "flash:"

    fun flashOwner(token: Long): String = FLASH_PREFIX + token

    /** Flash token named by [owner], or null when [owner] is not a flash owner. */
    fun flashToken(owner: String?): Long? =
        owner?.takeIf { it.startsWith(FLASH_PREFIX) }?.removePrefix(FLASH_PREFIX)?.toLongOrNull()

    fun decide(
        hasIntent: Boolean,
        action: String?,
        status: String?,
        streams: String?,
        owner: String? = null,
        current: String? = null,
    ): Decision = when {
        !hasIntent -> Stop
        action == ACTION_STOP -> if (owner == null || current == null || owner == current) Stop else Ignore
        status.isNullOrBlank() -> if (current == null) Stop else Ignore
        else -> {
            val who = owner ?: OWNER_COMPANION
            val shownFlash = flashToken(current)
            val newFlash = flashToken(who)
            val replaces = shownFlash == null || who == current || (newFlash != null && newFlash > shownFlash)
            if (replaces) Show(status, streams.orEmpty(), who) else Ignore
        }
    }
}

class ConnectionService : Service() {

    private val policy = WakeLockPolicy()
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    // Owner whose status is shown and who holds the wake lock (companion link or a flash token).
    private var owner: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val cmd = ConnectionServiceCommand.decide(
            intent != null, intent?.action, intent?.getStringExtra(EXTRA_STATUS), intent?.getStringExtra(EXTRA_STREAMS),
            intent?.getStringExtra(EXTRA_OWNER), owner,
        )) {
            ConnectionServiceCommand.Stop -> {
                owner = null
                releaseWakeLock()
                stopForegroundCompat()
                // Only if no newer start is queued (e.g. a flash taking over right after the link stop).
                stopSelf(startId)
            }
            ConnectionServiceCommand.Ignore ->
                app.brucia.BruciaLog.d("Service", "ignored ${intent?.action ?: "update"} for ${intent?.getStringExtra(EXTRA_OWNER)}; shown: $owner")
            is ConnectionServiceCommand.Show -> {
                owner = cmd.owner
                try {
                    ensureChannel()
                    // Best-effort: upgrade to a foreground service when the platform allows it. If it
                    // is refused (e.g. Android 14+ connectedDevice precondition), we simply do not
                    // get background survival - the app keeps working. Started via startService(), so
                    // there is no "must call startForeground" contract to violate, hence no crash.
                    startForegroundCompat(buildNotification(cmd.status, cmd.streams, ConnectionServiceCommand.flashToken(cmd.owner) != null))
                    acquireWakeLock()
                } catch (e: Throwable) {
                    // Foreground upgrade refused (common on Android 14+ / some ROMs): the app keeps
                    // working but loses background survival. Log it so "stops logging with screen off"
                    // reports are diagnosable.
                    app.brucia.BruciaLog.w("Service", "foreground start / wakelock failed", e)
                }
            }
        }
        // Not sticky: the link lives in the app process. A system restart after process death has
        // no transport, so it must not come back advertising a connection or holding a wake lock.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (live === this) live = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (!policy.add("connection")) return // already held
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wl = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "brucia:connection")
            wl?.setReferenceCounted(false)
            wl?.acquire(SESSION_TIMEOUT_MS)
            wakeLock = wl
        } catch (e: Exception) {
            app.brucia.BruciaLog.w("Service", "WakeLock acquire denied", e)
        }
    }

    private fun releaseWakeLock() {
        policy.clear()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {
            app.brucia.BruciaLog.w("Service", "WakeLock release failed", e)
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Device connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the Brucia device link while it is active"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String, streams: String, flashing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val pi = android.app.PendingIntent.getActivity(this, 0, openIntent, flags)
        val text = when {
            flashing -> "Firmware update in progress - keep the device plugged in"
            streams.isBlank() -> "No active streams"
            else -> "Streams: $streams"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("Brucia - $status")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Returns true if the foreground service started; false if the platform refused it. */
    private fun startForegroundCompat(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (_: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException (missing qualifying
            // permission for the connectedDevice type) / MissingForegroundServiceTypeException.
            false
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "brucia_connection"
        private const val NOTIF_ID = 42
        private const val SESSION_TIMEOUT_MS = 12L * 60L * 60L * 1000L // 12h safety cap
        private const val ACTION_STOP = ConnectionServiceCommand.ACTION_STOP
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_STREAMS = "streams"
        private const val EXTRA_OWNER = "owner"

        /**
         * The created instance, for instrumentation tests only: a system restart's null-intent start
         * cannot be requested through the public API, so the test delivers it to this instance.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var live: ConnectionService? = null
            private set

        /**
         * Start (or update) the connection service with [status] and [streams] text.
         *
         * We deliberately use startService(), NOT startForegroundService(): the latter imposes a
         * hard contract that the service MUST call startForeground() within ~5s or the OS crashes
         * the app. On devices/OS versions where the connectedDevice foreground type is refused (e.g.
         * an Android 14+ precondition not met), honoring that contract is impossible and the app
         * crashed on Connect. With startService() the service upgrades to foreground when allowed and
         * simply degrades (no background survival) when not - it can never crash the Connect flow.
         * Called from a foreground Activity (the Connect tap), so the background-start limit does
         * not apply. Fully wrapped so nothing here can propagate.
         */
        fun start(context: Context, owner: String, status: String, streams: String) {
            try {
                val i = Intent(context, ConnectionService::class.java)
                    .putExtra(EXTRA_STATUS, status)
                    .putExtra(EXTRA_STREAMS, streams)
                    .putExtra(EXTRA_OWNER, owner)
                context.startService(i)
            } catch (e: Throwable) {
                app.brucia.BruciaLog.w("Service", "startService failed", e)
            }
        }

        /** Update the notification content (connection status / active streams changed). */
        fun update(context: Context, owner: String, status: String, streams: String) = start(context, owner, status, streams)

        /**
         * Stop the service for [owner]: removes the notification and releases the wake lock only if
         * [owner] is the one currently shown (see [ConnectionServiceCommand.decide]).
         */
        fun stop(context: Context, owner: String) {
            try {
                context.startService(
                    Intent(context, ConnectionService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_OWNER, owner)
                )
            } catch (_: Exception) {
                // If the process is not allowed to start a service in the background, fall back.
                context.stopService(Intent(context, ConnectionService::class.java))
            }
        }
    }
}
