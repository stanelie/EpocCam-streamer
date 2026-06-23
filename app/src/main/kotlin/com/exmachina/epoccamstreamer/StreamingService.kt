package com.exmachina.epoccamstreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("epoccam_stream", "EpocCam Streamer", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Live stream active" }
        )
        startForeground(1, NotificationCompat.Builder(this, "epoccam_stream")
            .setContentTitle("EpocCam Streaming")
            .setContentText("Live stream active — open app to stop")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
