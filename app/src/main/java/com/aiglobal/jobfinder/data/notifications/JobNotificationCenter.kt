package com.aiglobal.jobfinder.data.notifications

import android.Manifest
import android.annotation.SuppressLint
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
import com.aiglobal.jobfinder.MainActivity
import com.aiglobal.jobfinder.R

/**
 * Posts the "new strong match" notifications.
 *
 * The channel is created eagerly so the system settings screen shows it before the first
 * notification. Posting is gated on the runtime permission on Android 13+, and every post
 * is a no-op if it is missing rather than a crash — a user who declined permission simply
 * gets no notifications, which is the correct behaviour.
 */
class JobNotificationCenter(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "New job matches",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Strong matches discovered in the background"
        }
        manager.createNotificationChannel(channel)
    }

    /** True when the app may post notifications right now. */
    fun canNotify(): Boolean {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Posts one notification per entry. Ids are stable so a re-post replaces, never stacks. */
    @SuppressLint("MissingPermission") // guarded by canNotify(), which checks POST_NOTIFICATIONS
    fun post(notifications: List<JobNotification>) {
        if (notifications.isEmpty() || !canNotify()) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        notifications.forEach { notification ->
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Carry the posting id so tapping the alert opens that job, not just the app.
                putExtra(EXTRA_JOB_ID, notification.jobId)
            }
            val pending = PendingIntent.getActivity(
                context,
                notification.jobId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val built = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.body.replace("\n", " "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { manager.notify(notification.jobId.hashCode(), built) }
        }
    }

    /**
     * Posts the "an application is ready for your review" alerts. These are deliberately on
     * their own channel so the user can silence preparation nudges without silencing new-match
     * alerts. Tapping one opens the draft review screen for that application, not just the app.
     */
    @SuppressLint("MissingPermission")
    fun postDraftReviews(alerts: List<DraftReviewAlert>) {
        if (alerts.isEmpty() || !canNotify()) return
        ensureDraftChannel()
        val manager = NotificationManagerCompat.from(context)
        alerts.forEach { alert ->
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DRAFT_ID, alert.draftId)
            }
            val pending = PendingIntent.getActivity(
                context,
                alert.draftId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val built = NotificationCompat.Builder(context, DRAFT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.body.replace("\n", " "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { manager.notify(alert.draftId.hashCode(), built) }
        }
    }

    private fun ensureDraftChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DRAFT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            DRAFT_CHANNEL_ID,
            "Applications ready for review",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "An application has been prepared and needs your approval before anything is sent"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "job_matches"
        private const val DRAFT_CHANNEL_ID = "application_reviews"

        /**
         * Intent extra carrying the posting id, so tapping an alert opens that job. Public so
         * the activity that receives it reads the same constant the poster wrote.
         */
        const val EXTRA_JOB_ID = "com.aiglobal.jobfinder.extra.JOB_ID"

        /** Intent extra carrying the draft id, so tapping a review alert opens that draft. */
        const val EXTRA_DRAFT_ID = "com.aiglobal.jobfinder.extra.DRAFT_ID"
    }
}
