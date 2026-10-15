package com.aiglobal.jobfinder.data.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime notification-permission question, kept in one place so the ViewModel, the
 * settings screen and the activity that actually requests it all agree.
 *
 * On Android 13 (API 33) and above the app must ask for `POST_NOTIFICATIONS`; below that the
 * permission is implicit. The request itself is launched from the activity, because only an
 * activity can show the system dialog.
 */
object NotificationPermission {

    /** True when the OS requires an explicit runtime grant. */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** True when the app may post notifications right now. */
    fun isGranted(context: Context): Boolean {
        if (!isRequired()) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS
}