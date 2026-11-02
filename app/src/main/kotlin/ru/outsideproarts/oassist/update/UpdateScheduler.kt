package ru.outsideproarts.oassist.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import ru.outsideproarts.oassist.core.model.update.UpdateChannel
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences
import ru.outsideproarts.oassist.di.AppGraph

object UpdateScheduler {
    private const val JOB_ID = 7301
    fun configure(context: Context, preferences: UpdatePreferences, channel: UpdateChannel) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!preferences.automaticChecks) { scheduler.cancel(JOB_ID); return }
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == channel.checkIntervalMillis) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(channel.checkIntervalMillis)
            .setPersisted(true)
            .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler.schedule(job)
    }
}

class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            try { AppGraph.get().updates.check(manual = false) }
            finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Re-evaluate the default channel after replacing an APK; a manual choice remains in DataStore. */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AppGraph.get().updates.start()
    }
}
