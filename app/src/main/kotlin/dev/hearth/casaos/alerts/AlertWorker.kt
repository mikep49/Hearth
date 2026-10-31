package dev.hearth.casaos.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.MainActivity
import dev.hearth.casaos.R
import dev.hearth.casaos.data.Alert
import dev.hearth.casaos.data.AlertReading
import dev.hearth.casaos.data.AlertSettings
import dev.hearth.casaos.data.AlertStorage
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.DRIVES_EVERY_MS
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.TOTAL_STORAGE
import dev.hearth.casaos.data.evaluateAlerts
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Checks every signed-in server in the background and notifies about what changed. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HearthApp).container
        val settings = container.prefs.alerts.value
        if (!settings.active) return Result.success()
        val servers = container.session.profiles.value.filter { it.signedIn }
        coroutineScope { servers.map { async { check(it, settings) } }.awaitAll() }
        return Result.success()
    }

    private suspend fun check(profile: ServerProfile, settings: AlertSettings) {
        val container = (applicationContext as HearthApp).container
        val now = System.currentTimeMillis()
        val readDrives = settings.storage && now - container.alerts.get(profile.id).drivesCheckedAtMs >= DRIVES_EVERY_MS
        val reading = read(container.clientFor(profile.id), readDrives) ?: return
        // Signed out while checking: that server's alerts were just cleared.
        if (container.session.profile(profile.id)?.signedIn != true) return
        val alerts = container.alerts.update(profile.id) { prev ->
            val (next, alerts) = evaluateAlerts(prev, reading, settings, now)
            (if (readDrives && reading is AlertReading.Online) next.copy(drivesCheckedAtMs = now) else next) to alerts
        }
        alerts.forEach { AlertNotifications.show(applicationContext, profile, it) }
    }

    /** One reading of the server, or null when it can't say anything (e.g. the login ended). */
    private suspend fun read(client: CasaClient, readDrives: Boolean): AlertReading? = try {
        val stats = client.utilization()
        val apps = client.apps()
        val volumes = buildMap {
            stats.disk?.takeIf { it.totalBytes > 0 }?.let { put(TOTAL_STORAGE, it.percent) }
            if (readDrives) {
                // Optional: a server that can't list its drives still gets the total checked.
                runCatching { client.drives() }.getOrNull()?.forEach { drive ->
                    drive.volumes.filter { it.totalBytes > 0 }.forEach { put(it.mountPoint, it.percent) }
                }
            }
        }
        AlertReading.Online(apps, volumes)
    } catch (e: CasaException.Unauthorized) {
        null
    } catch (e: CasaException.Parse) {
        null
    } catch (e: CasaException) {
        // Can't connect, or CasaOS answers with an error (e.g. its gateway is up but its services aren't).
        AlertReading.Offline
    }
}

class PrefsAlertStorage(context: Context) : AlertStorage {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    override var saved: String?
        get() = prefs.getString("state_by_server", null)
        // Synchronous, so a check's result is on disk before the process can be stopped.
        set(value) { prefs.edit().putString("state_by_server", value).commit() }
}

/** Starts and stops the background check to match the alert settings. */
object AlertSchedule {
    private const val WORK = "alerts"

    /** WorkManager's shortest period. */
    const val EVERY_MINUTES = 15L

    fun sync(context: Context, settings: AlertSettings) {
        val work = WorkManager.getInstance(context)
        if (!settings.active) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertWorker>(EVERY_MINUTES, TimeUnit.MINUTES)
            // With no network at all the server can't be reached, but it isn't the server's fault.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** The notifications themselves. Each kind has its own channel, so it can be silenced in Android's settings. */
object AlertNotifications {
    const val EXTRA_SERVER = "dev.hearth.casaos.alert_server"
    const val EXTRA_TAB = "dev.hearth.casaos.alert_tab"
    const val TAB_HOME = "home"
    const val TAB_APPS = "apps"

    private const val CHANNEL_OFFLINE = "server_offline"
    private const val CHANNEL_STORAGE = "storage_full"
    private const val CHANNEL_APPS = "app_stopped"
    private const val CHANNEL_UPDATES = "app_updates"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_OFFLINE, "Server offline", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_STORAGE, "Storage almost full", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_APPS, "App stopped", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /** Whether Android lets Hearth post notifications at all. */
    fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(context: Context, server: ServerProfile, alert: Alert) {
        if (!allowed(context)) return
        val label = server.label
        val (key, channel, tab) = when (alert) {
            Alert.ServerOffline, Alert.BackOnline -> Triple("offline", CHANNEL_OFFLINE, TAB_HOME)
            is Alert.StorageFull -> Triple("storage:${alert.volume}", CHANNEL_STORAGE, TAB_HOME)
            is Alert.AppStopped -> Triple("app:${alert.appId}", CHANNEL_APPS, TAB_APPS)
            is Alert.UpdatesAvailable -> Triple("updates", CHANNEL_UPDATES, TAB_APPS)
        }
        val (title, text) = when (alert) {
            Alert.ServerOffline -> "$label is offline" to "Hearth couldn't reach it in the last two checks."
            Alert.BackOnline -> "$label is back online" to "Hearth can reach it again."
            is Alert.StorageFull -> {
                val what = if (alert.volume == TOTAL_STORAGE) "Storage" else alert.volume
                "$what is ${alert.percent.roundToInt()}% full" to "Free up space before apps run out of room."
            }
            is Alert.AppStopped ->
                if (alert.restarting) "${alert.appName} keeps restarting" to "It may be crashing. Its logs can tell you why."
                else "${alert.appName} stopped" to "It was running and wasn't stopped from Hearth."
            is Alert.UpdatesAvailable ->
                if (alert.appNames.size == 1) "Update available for ${alert.appNames[0]}" to "Update it from Apps."
                else "${alert.appNames.size} app updates available" to alert.appNames.joinToString(", ")
        }
        val id = "${server.id}|$key".hashCode()
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, server.id)
            .putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(label)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addExtras(Bundle().apply { putString(EXTRA_NOTIFICATION_SERVER, server.id) })
            .setCategory(if (alert is Alert.UpdatesAvailable) NotificationCompat.CATEGORY_RECOMMENDATION else NotificationCompat.CATEGORY_STATUS)
            // Server and app names stay off a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Hearth alert")
                    .build(),
            )
            .apply {
                // Replaces the offline alert quietly, and goes away by itself.
                if (alert == Alert.BackOnline) setSilent(true).setTimeoutAfter(60 * 60 * 1000L)
            }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check above and here.
        }
    }

    /** Removes [server]'s notifications, e.g. when it's signed out. */
    fun cancelAll(context: Context, server: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.notification.extras.getString(EXTRA_NOTIFICATION_SERVER) == server }
            .forEach { manager.cancel(it.id) }
    }

    private const val EXTRA_NOTIFICATION_SERVER = "dev.hearth.casaos.notification_server"
}
