package xyz.wraithnet.phantom.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import xyz.wraithnet.phantom.data.PhantomUpdates
import java.util.concurrent.TimeUnit

/**
 * Background OTA runs only on Wi-Fi/Ethernet; never restarts an active VPN.
 * Android may request confirmation and the install receiver notifies the user.
 */
class PhantomAutoUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apk_updates", true)) return Result.success()
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return Result.retry()
        val caps = manager.getNetworkCapabilities(network) ?: return Result.retry()
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Result.success()
        // Installing an APK kills its running process; wait for VPN to be idle.
        if (prefs.getBoolean("vpn_running", false)) return Result.success()
        // A differently signed legacy/CI APK cannot be upgraded in-place.
        // Do not repeatedly transfer a huge installer that Android must reject.
        if (PhantomUpdates.hasOfficialSigningCertificate(ctx) != true) {
            return Result.success()
        }
        return try {
            val channel = PhantomUpdates.selectedChannel(ctx)
            val next = PhantomUpdates.check(ctx, channel) ?: return Result.success()
            if (Build.VERSION.SDK_INT < 31 ||
                !ctx.packageManager.canRequestPackageInstalls()) return Result.success()
            val apk = PhantomUpdates.download(ctx, next, onProgress = { })
            // A user can opt out while a large beta APK is downloading.
            // Never install a previously selected beta after switching to Stable.
            if (prefs.getBoolean("vpn_running", false) ||
                !prefs.getBoolean("auto_apk_updates", true) ||
                PhantomUpdates.selectedChannel(ctx) != channel) return Result.success()
            if (PhantomUpdates.openInstaller(ctx, apk, next, allowPermissionPrompt = false))
                Result.success() else Result.retry()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "phantom_auto_apk_update"
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val enabled = context.getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
                .getBoolean("auto_apk_updates", true)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PhantomAutoUpdateWorker>(8, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
