package com.vlesscardvpn.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.data.PublicConfigFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = AppRepository(applicationContext)
        try {
            Log.d("SubscriptionWorker", "Starting automatic background repository sync...")
            val sources = repo.subscriptionSources().ifEmpty { PublicConfigFetcher.DEFAULT_PUBLIC_SOURCES }
            val freshConfigs = PublicConfigFetcher.fetchCandidates(sources)
            if (freshConfigs.isEmpty()) return@withContext Result.retry()
            repo.mergeCandidates(freshConfigs.map { it.copy(source = "subscription-refresh", healthState = "UNKNOWN") })
            Log.i("SubscriptionWorker", "Synced ${freshConfigs.size} candidates; tunnel health not assumed")
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            Log.e("SubscriptionWorker", "Failed to sync configs (${e.javaClass.simpleName})")
            Result.retry()
        } finally {
            repo.close()
        }
    }

    companion object {
        private const val WORK_NAME = "SubscriptionUpdateWork"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                // Do not compete with the first UI frame on a cold app start.
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
