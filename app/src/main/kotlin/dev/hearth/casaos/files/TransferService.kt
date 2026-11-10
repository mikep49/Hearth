package dev.hearth.casaos.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.MainActivity
import dev.hearth.casaos.R
import dev.hearth.casaos.alerts.AlertNotifications
import dev.hearth.casaos.data.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Hearth running while a download or upload is under way, so it isn't paused when you switch apps or the
 * phone sleeps. Shows the transfer's progress as a notification, with a button to cancel it. The transfer itself
 * runs in [dev.hearth.casaos.data.Transfers]; this service starts with it and stops itself when it's done.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStart = 0

    private val transfers get() = (application as HearthApp).container.transfers

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A foreground service alone doesn't stop the processor sleeping once the screen is off.
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hearth:transfer").apply {
                setReferenceCounted(false)
                acquire(MAX_AWAKE_MS)
            }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (intent?.action == ACTION_CANCEL) transfers.cancel()
        // Android expects this soon after every start, even when the transfer has finished in the meantime.
        try {
            ServiceCompat.startForeground(
                this,
                TransferNotifications.ID_PROGRESS,
                TransferNotifications.progress(this, transfers.current.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: Exception) {
            // Not allowed from the background (a Cancel tapped on a notification that outlived its transfer).
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watching == null) {
            watching = scope.launch {
                // A state flow only ever hands over the latest value, so progress made during the delay is skipped.
                transfers.current.collect { transfer ->
                    if (transfer == null) {
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Stays up if another transfer has asked for the service since.
                        stopSelf(lastStart)
                    } else {
                        TransferNotifications.post(this@TransferService, TransferNotifications.ID_PROGRESS, TransferNotifications.progress(this@TransferService, transfer))
                        // Android drops notification updates that come too fast.
                        delay(500)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 and later limit how long this kind of service runs while Hearth is in the background. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        transfers.cancel("Stopped: Android limits how long a transfer can run in the background")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "dev.hearth.casaos.transfer_cancel"

        /** The longest Android lets a data sync service run in the background in a day. */
        private const val MAX_AWAKE_MS = 6 * 60 * 60 * 1000L

        /** Call as a transfer starts, while Hearth is on screen. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}

object TransferNotifications {
    const val ID_PROGRESS = 0x7e41
    private const val ID_RESULT = 0x7e42
    private const val CHANNEL = "transfers"

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(context: Context): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, ID_PROGRESS, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification for [transfer], or a placeholder if it has just finished. */
    fun progress(context: Context, transfer: Transfer?): Notification {
        channel(context)
        val cancel = PendingIntent.getService(
            context,
            ID_PROGRESS,
            Intent(context, TransferService::class.java).setAction(TransferService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val p = transfer?.progress
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(transfer?.label ?: "Finishing up")
            .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply { if (transfer != null) addAction(0, "Cancel", cancel) }
            .build()
    }

    /** Tells the user how a transfer ended, if they weren't looking at Hearth when it did. */
    fun result(context: Context, message: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        channel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle("Hearth").bigText(message))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, ID_RESULT, notification)
    }

    fun post(context: Context, id: Int, notification: Notification) {
        if (!AlertNotifications.allowed(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check above and here.
        }
    }
}
