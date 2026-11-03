package com.vlesscardvpn.core

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Optional system-batched snapshots. Independent job cancellation; never a VPN service. */
class DeviceCoachJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (params.jobId == com.vlesscardvpn.watson.WatsonMonitor.JOB_ID) {
                    com.vlesscardvpn.watson.WatsonMonitor.refresh(this@DeviceCoachJob)
                } else if (DeviceCoach.backgroundEnabled(this@DeviceCoachJob) &&
                    !GameSession.active() && PhonePower.refresh(this@DeviceCoachJob).allowLight) {
                    runCatching { DeviceCoach.sample(this@DeviceCoachJob) }
                }
            } finally {
                tasks.remove(params.jobId, currentCoroutineContext()[Job])
                if (isActive) withContext(Dispatchers.Main) { jobFinished(params, false) }
            }
        }
        tasks.put(params.jobId, task)?.cancel()
        task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel()
        return false
    }
    override fun onDestroy() { scope.cancel(); tasks.clear(); super.onDestroy() }
}
