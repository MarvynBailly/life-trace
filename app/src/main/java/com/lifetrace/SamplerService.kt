package com.lifetrace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Foreground service that samples + uploads telemetry every SAMPLE_MS.
 * Uses a background HandlerThread; no coroutine deps.
 */
class SamplerService : Service() {

    private val channelId = "lifetrace_sampler"
    private val sampleMs = 15 * 60 * 1000L  // 15 minutes
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    @Volatile private var lastStatus = "starting..."

    private val sampleTask = object : Runnable {
        override fun run() {
            try {
                lastStatus = Net.collectAndUpload(applicationContext)
            } catch (e: Exception) {
                lastStatus = "upload error: ${e.message}"
            }
            updateNotification()
            handler.postDelayed(this, sampleMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Config.init(applicationContext)
        createChannel()
        startForeground(1, buildNotification(lastStatus))
        thread = HandlerThread("sampler")
        thread.start()
        handler = Handler(thread.looper)
        handler.post(sampleTask)  // first sample immediately
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "LifeTrace", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Background life-tracking"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else Notification.Builder(this)
        return b.setContentTitle("LifeTrace running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(lastStatus))
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, SamplerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
