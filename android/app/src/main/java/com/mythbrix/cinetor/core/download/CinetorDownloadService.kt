package com.mythbrix.cinetor.core.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythbrix.cinetor.CinetorApplication
import com.mythbrix.cinetor.MainActivity
import com.mythbrix.cinetor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Notification channel + builders shared by [CinetorDownloadService] and [ResumeDownloadsWorker]. */
object DownloadNotifications {
    const val CHANNEL = "downloads"
    const val ID = 4207

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Download progress"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code,
            Intent(context, CinetorDownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Grouped progress: one ongoing notification summarising every running download. Adult titles are never named. */
    fun build(context: Context, infos: List<DownloadInfo>, summary: EngineSummary): Notification {
        ensureChannel(context)
        val running = infos.filter { it.record.status == DownloadStatus.DOWNLOADING }
        val total = running.sumOf { maxOf(it.record.totalBytes, 0) }
        val done = running.sumOf { it.record.downloadedBytes }
        val speed = running.sumOf { it.live?.currentBps ?: 0 }
        val anyPaused = infos.any { it.record.status == DownloadStatus.PAUSED }
        val title = when {
            running.isEmpty() && summary.blockedReason != null -> summary.blockedReason
            running.isEmpty() -> "Downloads queued"
            running.size == 1 -> "Downloading ${safeTitle(running[0].record)}"
            else -> "Downloading ${running.size} titles"
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cinetor)
            .setContentTitle(title)
            .setContentText(
                if (running.isEmpty()) "${summary.pending} waiting"
                else "${formatBytes(done)} / ${formatBytes(total)} · ${formatSpeed(speed)}" + if (summary.pending > 0) " · ${summary.pending} queued" else "",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup("cinetor_downloads")
            .setGroupSummary(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
        else if (running.isNotEmpty()) b.setProgress(0, 0, true)
        if (running.isNotEmpty()) b.addAction(0, "Pause", action(context, CinetorDownloadService.ACTION_PAUSE_ALL, 1))
        if (anyPaused || running.isEmpty()) b.addAction(0, "Resume", action(context, CinetorDownloadService.ACTION_RESUME_ALL, 2))
        b.addAction(0, "Cancel", action(context, CinetorDownloadService.ACTION_CANCEL_ALL, 3))
        return b.build()
    }

    private fun safeTitle(r: DownloadRecord) = if (r.adult) "private title" else r.title

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Foreground service (type dataSync) that keeps the process alive while the [DownloadEngine] runs, with a grouped
 * progress notification and Pause / Resume / Cancel actions. Stops itself when nothing is running.
 */
class CinetorDownloadService : Service() {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val module = (application as CinetorApplication).container.downloads
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        startInForeground(DownloadNotifications.build(this, module.engine.downloads.value, module.engine.summary.value))
        when (intent?.action) {
            ACTION_PAUSE_ALL -> s.launch { module.engine.pauseAll() }
            ACTION_RESUME_ALL -> s.launch { module.engine.resumeAll() }
            ACTION_CANCEL_ALL -> s.launch {
                module.engine.downloads.value.filter { it.record.isActive || it.record.status == DownloadStatus.PAUSED }
                    .forEach { module.engine.cancel(it.record.id) }
            }
        }
        if (loop?.isActive != true) {
            loop = s.launch {
                // Short grace period so a just-enqueued download has time to start.
                delay(2_000)
                combine(module.engine.downloads, module.engine.summary) { d, sum -> d to sum }.collect { (d, sum) ->
                    if (sum.active == 0) {
                        if (sum.pending > 0) module.scheduleResumeWork()
                        ServiceCompat.stopForeground(this@CinetorDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (DownloadNotifications.canPost(this@CinetorDownloadService)) {
                        runCatching {
                            NotificationManagerCompat.from(this@CinetorDownloadService)
                                .notify(DownloadNotifications.ID, DownloadNotifications.build(this@CinetorDownloadService, d, sum))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DownloadNotifications.ID, n, type)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.mythbrix.cinetor.download.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.mythbrix.cinetor.download.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.mythbrix.cinetor.download.CANCEL_ALL"

        /** Starts the service; returns false when Android forbids a background FGS start (→ WorkManager path). */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, CinetorDownloadService::class.java))
        }.isSuccess
    }
}

/**
 * Resumes queued downloads when constraints are met (network / unmetered / charging) — also after reboot or
 * process death, since WorkManager persists the request. Runs in the foreground (dataSync) until idle.
 */
class ResumeDownloadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val module = (applicationContext as CinetorApplication).container.downloads
        module.engine.schedule()
        if (module.engine.summary.value.active == 0) return Result.success()
        if (CinetorDownloadService.start(applicationContext)) return Result.success()
        runCatching { setForeground(foregroundInfo(module)) }
        module.engine.summary.first { it.active == 0 }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo((applicationContext as CinetorApplication).container.downloads)

    private fun foregroundInfo(module: DownloadsModule): ForegroundInfo {
        val n = DownloadNotifications.build(applicationContext, module.engine.downloads.value, module.engine.summary.value)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(DownloadNotifications.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DownloadNotifications.ID, n)
        }
    }

    companion object {
        const val NAME = "cinetor-resume-downloads"

        fun enqueue(context: Context, policy: QueuePolicy) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (policy.wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED)
                .setRequiresCharging(policy.chargingOnly)
                .setRequiresStorageNotLow(true)
                .build()
            val req = OneTimeWorkRequestBuilder<ResumeDownloadsWorker>().setConstraints(c).build()
            // APPEND_OR_REPLACE: never cancels a running resume worker (it may be the caller), queues after it instead.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
