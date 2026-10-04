package app.zcomic.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import app.zcomic.DownloadJobService
import app.zcomic.DownloadForegroundService
import java.io.IOException

/** One device-selected executor drains the durable Room queue, independent of its size. */
internal class DownloadScheduler(private val context: Context,
    private val jobs: JobScheduler = context.getSystemService(JobScheduler::class.java).forNamespace("zcomic-downloads"),
    val usesForegroundService: Boolean = BackgroundDownloadSupport.isXiaomi()
) {
    private val service = ComponentName(context, DownloadJobService::class.java)
    private var finishing = false
    private var foregroundRequested = false
    private var foregroundGeneration = 0

    fun schedule(task: DownloadRecord) {
        if (task.status !in listOf("queued", "running")) return
        if (usesForegroundService) {
            // Retire v7/v8's persisted UIDT job when migrating this device to the service.
            jobs.cancelAll()
            if (!finishing && foregroundRequested) return
            val next = foregroundGeneration + 1
            recoveryState(context).edit().putBoolean(RECOVERY_PENDING, true).apply()
            try {
                context.startForegroundService(Intent(context, DownloadForegroundService::class.java)
                    .putExtra(GENERATION, next))
            } catch (_: IllegalStateException) {
                throw IOException("后台下载启动受限，请返回应用后点击继续，并检查后台下载设置")
            } catch (_: SecurityException) {
                throw IOException("后台下载权限受限，请检查后台下载设置")
            }
            foregroundGeneration = next
            foregroundRequested = true
            finishing = false
            return
        }
        if (!finishing && jobs.getPendingJob(QUEUE_JOB_ID) != null) return
        val extras = PersistableBundle().apply { putString(TASK_ID, "queue") }
        val job = JobInfo.Builder(QUEUE_JOB_ID, service)
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setExtras(extras)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .apply { if (task.total > task.received) setEstimatedNetworkBytes(task.total - task.received, 0) }
            .build()
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS)
            throw IOException("后台下载启动失败，请保持应用可见后点击继续")
        finishing = false
    }

    fun started() { finishing = false }
    fun finished(hasPending: Boolean = true) {
        finishing = true
        if (usesForegroundService && !hasPending)
            recoveryState(context).edit().putBoolean(RECOVERY_PENDING, false).apply()
    }

    fun foregroundStopped(generation: Int) {
        if (generation == foregroundGeneration) { foregroundRequested = false; finishing = true }
    }

    fun cancelAll() {
        jobs.cancelAll()
        if (usesForegroundService) {
            foregroundRequested = false
            recoveryState(context).edit().putBoolean(RECOVERY_PENDING, false).apply()
            finishing = true
            DownloadDiagnostics.get(context).finished()
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }

    companion object {
        const val TASK_ID = "download_id"
        const val QUEUE_JOB_ID = 1
        const val GENERATION = "download_generation"
        private const val RECOVERY_PENDING = "pending"
        private fun recoveryState(context: Context) =
            context.getSharedPreferences("download_recovery", Context.MODE_PRIVATE)
        fun recoveryPending(context: Context): Boolean = recoveryState(context).getBoolean(RECOVERY_PENDING, false)
    }
}
