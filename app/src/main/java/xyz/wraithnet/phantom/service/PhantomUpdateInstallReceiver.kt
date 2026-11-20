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

/** OS callback for an already verified self-update. */
class PhantomUpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESULT = "xyz.wraithnet.phantom.UPDATE_INSTALL_RESULT"
        private const val CHANNEL = "phantom_updates"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    notifyUser(context, "Подтвердите обновление Phantom", confirmation)
                }
            }
            else -> notifyUser(context, "Не удалось установить обновление Phantom", null)
        }
    }

    private fun notifyUser(context: Context, message: String, confirmation: Intent?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления Phantom", NotificationManager.IMPORTANCE_HIGH)
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            confirmation?.let { runCatching { context.startActivity(it) } }
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
            .setContentTitle("Phantom VPN")
            .setContentText(message)
            .setAutoCancel(true)
        if (action != null) builder.setContentIntent(action)
        manager.notify(827, builder.build())
    }
}
