package com.vlesscardvpn.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig

/**
 * Stub AutoTestWorker for periodic ping tests on saved servers.
 * TODO: Schedule via WorkManager in MainActivity.
 * Use for background auto ping and update UI via DB observer.
 */
class AutoTestWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // In real: load from repository
        val stubConfigs = listOf(
            VlessConfig(name = "Test1", address = "1.1.1.1", port = 443, uuid = "stub"),
            VlessConfig(name = "Test2", address = "8.8.8.8", port = 443, uuid = "stub")
        )

        stubConfigs.forEach { cfg ->
            val ping = PingTester.pingConfig(cfg)
            // TODO: persist ping result
            // Latency checked safely without sensitive prints
        }
        return Result.success()
    }
}