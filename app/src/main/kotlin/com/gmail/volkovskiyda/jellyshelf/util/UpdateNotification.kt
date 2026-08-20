package com.gmail.volkovskiyda.jellyshelf.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.R

/**
 * The one notification this app posts itself: a new version is available.
 *
 * Low importance on purpose — an update is worth finding when the user next looks at their phone,
 * and is never worth a sound or a heads-up card over whatever they are doing. The whole point of
 * the background check is to reach someone who rarely opens the app, not to interrupt them.
 *
 * The channel is created here, at post time, rather than in `Application.onCreate`: it is the
 * app's first and only channel, and creating it on every cold start to serve a notification most
 * launches never post would be work for its own sake. `createNotificationChannel` is idempotent.
 */
object UpdateNotification {

    /** On the launch intent: show the update dialog straight away — see [MainActivity]. */
    const val EXTRA_SHOW_UPDATE = "com.gmail.volkovskiyda.jellyshelf.SHOW_UPDATE"

    /**
     * Posts, or silently does nothing if the permission is absent — the caller decides whether
     * that is worth knowing, and [shouldNotifyAboutUpdate][com.gmail.volkovskiyda.jellyshelf
     * .domain.shouldNotifyAboutUpdate] already declines to stamp a version it could not announce.
     */
    fun post(context: Context, versionName: String) {
        // Checked here as well as by the caller's policy: from API 33 posting without the
        // permission throws, and a guard the caller can forget is not a guard. Inline rather than
        // behind a helper because lint follows the check only within the function that posts —
        // which is the same argument, made by a tool.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.update_notification_channel_description) },
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, versionName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "updates"

    /** Fixed, so a second check replaces the first notification rather than stacking one. */
    private const val NOTIFICATION_ID = 1
}
