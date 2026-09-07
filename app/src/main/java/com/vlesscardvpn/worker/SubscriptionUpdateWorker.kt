package com.vlesscardvpn.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.data.PublicConfigFetcher
import kotlinx.coroutines.Dispatchers
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
            val freshConfigs = PublicConfigFetcher.fetchAndFilterWorkingConfigs(
                maxWorkingCount = 150
            ) { progress, working, msg ->
                Log.d("SubscriptionWorker", "Progress: $progress, working: $working ($msg)")
            }

            if (freshConfigs.isNotEmpty()) {
                val existing = repo.getAllConfigs()
                // Update or insert free configs without duplicating
                freshConfigs.forEach { cfg ->
                    val found = existing.find {
                        it.address == cfg.address && it.port == cfg.port && it.uuid == cfg.uuid
                    }
                    if (found != null) {
                        repo.updateConfig(found.copy(pingMs = cfg.pingMs, lastCheck = System.currentTimeMillis()))
                    } else {
                        repo.addConfig(cfg)
                    }
                }
                Log.i("SubscriptionWorker", "Successfully synced ${freshConfigs.size} configs from public repositories")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("SubscriptionWorker", "Failed to sync configs: ${e.message}")
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
                .build()

            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
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
                .build()

            val request = OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
