package xyz.wraithnet.phantom.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import xyz.wraithnet.phantom.data.Subscription
import xyz.wraithnet.phantom.data.SubscriptionFetcher
import xyz.wraithnet.phantom.data.SubscriptionParser
import xyz.wraithnet.phantom.data.SubscriptionMutations
import xyz.wraithnet.phantom.data.SubscriptionStore

/** Network-constrained periodic refresh, even when the UI is not open. */
class SubscriptionRefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SubscriptionStore(applicationContext)
        val now = System.currentTimeMillis()
        val replacements = mutableMapOf<String, Pair<Subscription, Subscription>>()
        var failure = false
        for (old in store.read()) {
            if (!old.source.startsWith("http")) continue
            val interval = old.updateIntervalSeconds.coerceAtLeast(900L) * 1000
            if (now - old.updatedAt < interval) continue
            try {
                val downloaded = SubscriptionFetcher.download(applicationContext, old.source, store.deviceId())
                val parsed = SubscriptionParser.parse(downloaded.body,
                    old.customTitle ?: downloaded.title ?: old.title)
                replacements[old.id] = old to parsed.copy(
                    id = old.id,
                    source = old.source,
                    title = old.customTitle ?: downloaded.title ?: old.title,
                    updatedAt = System.currentTimeMillis(),
                    updateIntervalSeconds = old.customIntervalSeconds ?: downloaded.updateIntervalSeconds ?: old.updateIntervalSeconds,
                    customTitle = old.customTitle,
                    customIntervalSeconds = old.customIntervalSeconds,
                    uploadBytes = downloaded.uploadBytes ?: old.uploadBytes,
                    downloadBytes = downloaded.downloadBytes ?: old.downloadBytes,
                    totalBytes = downloaded.totalBytes ?: old.totalBytes,
                    expiresAt = downloaded.expiresAt,
                    announcement = downloaded.announcement,
                    supportUrl = downloaded.supportUrl,
                    webPageUrl = downloaded.webPageUrl,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = true
            }
        }
        if (replacements.isNotEmpty()) {
            // Reconcile against the latest committed subscriptions in one
            // transaction. Never resurrect a deleted item or overwrite a URL
            // that the user changed while this worker downloaded updates.
            store.update { current ->
                replacements.values.fold(current) { latest, (original, downloaded) ->
                    SubscriptionMutations.replaceDownloaded(latest, original, downloaded)
                }
            }
        }
        return if (failure) Result.retry() else Result.success()
    }
}
