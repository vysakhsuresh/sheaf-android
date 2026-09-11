package com.layerbit.sheaf.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.layerbit.sheaf.R

/**
 * The progress notification a long operation runs behind.
 *
 * It exists so the work survives, not to be read: a foreground service is what stops Android
 * from killing a four-hundred-page OCR the moment the user switches to another app. The
 * channel is deliberately low importance and silent - this is a status line, not an event
 * worth interrupting anyone for.
 */
object JobNotifications {

    private const val CHANNEL_ID = "sheaf.jobs"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // NotificationChannel is API 26+, which is minSdk, so no version guard is needed here.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.job_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.job_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, title: String, progress: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            builder.setContentText(context.getString(R.string.job_progress, progress, total))
            builder.setProgress(total, progress, false)
        } else {
            // Total unknown until the document is opened and its pages counted.
            builder.setProgress(0, 0, true)
        }

        // The type argument exists from API 29 and is required from 34. The two-argument
        // overload is still correct below that, and passing a type there would not compile.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }
}
