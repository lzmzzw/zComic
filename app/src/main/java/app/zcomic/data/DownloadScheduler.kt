package app.zcomic.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import app.zcomic.DownloadJobService
import java.io.IOException

/** A single persisted UIDT job drains the Room queue, independent of its size. */
internal class DownloadScheduler(context: Context,
    private val jobs: JobScheduler = context.getSystemService(JobScheduler::class.java).forNamespace("zcomic-downloads")
) {
    private val service = ComponentName(context, DownloadJobService::class.java)
    private var finishing = false

    fun schedule(task: DownloadRecord) {
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
    fun finished() { finishing = true }

    fun cancelAll() = jobs.cancelAll()

    companion object {
        const val TASK_ID = "download_id"
        const val QUEUE_JOB_ID = 1
    }
}
