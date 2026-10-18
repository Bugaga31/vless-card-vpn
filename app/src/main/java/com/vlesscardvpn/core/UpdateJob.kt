package com.vlesscardvpn.core

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

/** Android batches this hourly unmetered task. Never opens the installer in the background. */
class UpdateJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!Updater.autoDownload(this)) return false
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { Store.init(this@UpdateJob); Updater.check(ctx = this@UpdateJob)?.join() }
            finally { if (isActive) withContext(Dispatchers.Main) { jobFinished(params, false) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); Updater.cancelBackground(); return true }
    override fun onDestroy() { task?.cancel(); super.onDestroy() }
}
