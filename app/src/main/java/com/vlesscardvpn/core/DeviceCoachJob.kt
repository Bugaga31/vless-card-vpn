package com.vlesscardvpn.core

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

/** Optional system-batched snapshot. No continuous foreground service or VPN dependency. */
class DeviceCoachJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = scope.launch {
            try { if (DeviceCoach.backgroundEnabled(this@DeviceCoachJob)) runCatching { DeviceCoach.sample(this@DeviceCoachJob) } }
            finally { if (isActive) withContext(Dispatchers.Main) { jobFinished(params, false) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
