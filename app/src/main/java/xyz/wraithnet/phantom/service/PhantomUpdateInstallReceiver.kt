package xyz.wraithnet.phantom.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import xyz.wraithnet.phantom.R
import kotlinx.coroutines.flow.MutableStateFlow

/** OS callback for an already verified self-update. */
class PhantomUpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESULT = "xyz.wraithnet.phantom.UPDATE_INSTALL_RESULT"
        private const val CHANNEL = "phantom_updates"
        @Volatile var isForeground = false
            private set
        private var pendingConfirmation: Intent? = null
        internal val installError = MutableStateFlow<String?>(null)

        fun setForeground(context: Context, visible: Boolean) {
            isForeground = visible
            if (visible) openPendingConfirmation(context)
        }

        fun openPendingConfirmation(context: Context) {
            if (!isForeground) return
            val confirmation = pendingConfirmation ?: return
            // The receiver and activity callbacks execute on Main. Keep the
            // notification as a fallback if an OEM suppresses activity launch.
            if (runCatching { context.startActivity(confirmation) }.isSuccess) {
                pendingConfirmation = null
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                pendingConfirmation = null
                installError.value = null
                context.getSystemService(NotificationManager::class.java).cancel(827)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    pendingConfirmation = confirmation
                    openPendingConfirmation(context)
                    notifyUser(context, "Подтвердите обновление Phantom", confirmation)
                }
            }
            else -> {
                pendingConfirmation = null
                installError.value = "Android не завершил установку (код $status). Можно повторить установку."
                notifyUser(context, "Не удалось установить обновление Phantom", null)
            }
        }
    }

    private fun notifyUser(context: Context, message: String, confirmation: Intent?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления Phantom", NotificationManager.IMPORTANCE_HIGH)
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val action = confirmation?.let {
            PendingIntent.getActivity(context, 827, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(context, CHANNEL)
        else Notification.Builder(context)
        builder.setSmallIcon(R.drawable.ic_stat_phantom)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setAutoCancel(true)
        if (action != null) builder.setContentIntent(action)
        manager.notify(827, builder.build())
    }
}
