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
 * Started/updated/stopped by the connection owner through the [start] / [update] / [stop] helpers.
 */
class ConnectionService : Service() {

    private val policy = WakeLockPolicy()
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Connected"
                val streams = intent?.getStringExtra(EXTRA_STREAMS) ?: ""
                try {
                    ensureChannel()
                    // Best-effort: upgrade to a foreground service when the platform allows it. If it
                    // is refused (e.g. Android 14+ connectedDevice precondition), we simply do not
                    // get background survival - the app keeps working. Started via startService(), so
                    // there is no "must call startForeground" contract to violate, hence no crash.
                    startForegroundCompat(buildNotification(status, streams))
                    acquireWakeLock()
                } catch (e: Throwable) {
                    // Foreground upgrade refused (common on Android 14+ / some ROMs): the app keeps
                    // working but loses background survival. Log it so "stops logging with screen off"
                    // reports are diagnosable.
                    app.brucia.BruciaLog.w("Service", "foreground start / wakelock failed", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
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

    private fun buildNotification(status: String, streams: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val pi = android.app.PendingIntent.getActivity(this, 0, openIntent, flags)
        val text = if (streams.isBlank()) "No active streams" else "Streams: $streams"
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
        private const val ACTION_STOP = "app.brucia.service.STOP"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_STREAMS = "streams"

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
        fun start(context: Context, status: String, streams: String) {
            try {
                val i = Intent(context, ConnectionService::class.java)
                    .putExtra(EXTRA_STATUS, status)
                    .putExtra(EXTRA_STREAMS, streams)
                context.startService(i)
            } catch (e: Throwable) {
                app.brucia.BruciaLog.w("Service", "startService failed", e)
            }
        }

        /** Update the notification content (connection status / active streams changed). */
        fun update(context: Context, status: String, streams: String) = start(context, status, streams)

        /** Stop the service, remove the notification and release the wake lock. */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                // If the process is not allowed to start a service in the background, fall back.
                context.stopService(Intent(context, ConnectionService::class.java))
            }
        }
    }
}
