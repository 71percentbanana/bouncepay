package com.bouncepay.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bouncepay.MainActivity
import com.bouncepay.R
import com.bouncepay.mesh
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the node running with the screen off.
 *
 * Without it Android stops BLE advertising and scanning shortly after the app
 * leaves the foreground, and a relay in someone's pocket — the case the whole
 * product is about — would silently stop relaying. The ongoing notification is
 * the honest cost: the user can see the phone is carrying for the mesh.
 */
class MeshService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: RuntimeException) {
            // Android 12+ refuses a foreground start from the background (e.g. a
            // sticky restart), and 14+ refuses this type once Bluetooth
            // permission is revoked. Either way: stop quietly, the next app
            // launch starts it again.
            Log.w("MeshService", "could not enter the foreground", e)
            stopSelf()
            return
        }

        val node = mesh
        node.start()

        lifecycleScope.launch {
            combine(node.status, node.store.packets) { status, packets ->
                val carrying = packets.count {
                    it.state == com.bouncepay.model.PacketState.HELD ||
                        it.state == com.bouncepay.model.PacketState.FORWARDED
                }
                val role = when (status.role) {
                    Role.BRIDGE -> "Bridge"
                    Role.BRIDGE_FALLBACK -> "Bridge (fallback)"
                    Role.RELAY -> "Relay"
                }
                "$role · carrying $carrying · ${status.activity}"
            }.distinctUntilChanged().collect { text ->
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            mesh.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mesh.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesh relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone is relaying payments for the mesh"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bounce)
            .setContentTitle("BouncePay mesh is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop relaying", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 17
        private const val ACTION_STOP = "com.bouncepay.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
