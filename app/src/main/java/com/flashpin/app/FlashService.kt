package com.flashpin.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class FlashService : Service() {

    companion object {
        const val CHANNEL_ID = "flashpin_channel"
        const val NOTIF_ID = 1001
        const val ACTION_TOGGLE = "com.flashpin.app.TOGGLE"
        const val ACTION_REFRESH = "com.flashpin.app.REFRESH"
    }

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                torchOn = enabled
                postNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findFlashCamera()
        createChannel()
        startInForeground()
        cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            toggleTorch()
        }
        // Always re-assert the notification (also covers the user swiping it away)
        startInForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            cameraManager.unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun findFlashCamera(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toggleTorch() {
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, !torchOn)
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, "Flashlight button", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Permanent flashlight button on the lock screen"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val i = Intent(this, FlashService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val rv = RemoteViews(packageName, R.layout.notification_card).apply {
            setTextViewText(R.id.title, if (torchOn) "Flashlight turned on" else "Flashlight")
            setTextViewText(R.id.subtitle, if (torchOn) "Tap the button to turn off" else "Tap the button to turn on")
            setImageViewResource(
                R.id.power_btn_icon,
                R.drawable.ic_power
            )
            setInt(
                R.id.power_btn, "setBackgroundResource",
                if (torchOn) R.drawable.bg_btn_on else R.drawable.bg_btn_off
            )
            setOnClickPendingIntent(R.id.power_btn, servicePendingIntent(ACTION_TOGGLE, 1))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flash)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setContentTitle("Flashlight")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // If the user swipes it away (allowed on Android 14+), bring it right back
            .setDeleteIntent(servicePendingIntent(ACTION_REFRESH, 2))
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun postNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}
