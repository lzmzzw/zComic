package app.zcomic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.zcomic.data.DownloadRecord
import app.zcomic.data.DownloadRuntime
import app.zcomic.data.DownloadScheduler
import app.zcomic.data.DownloadNetwork
import app.zcomic.data.DownloadDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diagnostics by lazy { DownloadDiagnostics.get(this) }
    private data class Execution(val params: JobParameters, val job: Job, val network: DownloadNetwork)
    private val running = mutableMapOf<Int, Execution>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "漫画下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.extras.getString(DownloadScheduler.TASK_ID) != "queue") return false
        setNotification(params, params.jobId + 1, notification(emptyList()), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val network = DownloadNetwork(params.network)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val runtime = DownloadRuntime.get(this@DownloadJobService)
                runtime.scheduler.started()
                diagnostics.started()
                withContext(network.transport) {
                    val updates = launch {
                        runtime.db.dao().downloads().sample(1_000).collectLatest { tasks ->
                            val active = tasks.filter { it.status in listOf("queued", "running") }
                            setNotification(params, params.jobId + 1, notification(active), JOB_END_NOTIFICATION_POLICY_REMOVE)
                            val transferred = network.transport.downloadedBytes
                            updateTransferredNetworkBytes(params, transferred, 0)
                            val estimate = estimatedTransferBytes(active, transferred)
                            if (estimate != null) updateEstimatedNetworkBytes(params, estimate, 0)
                        }
                    }
                    try {
                        runtime.queue.drain { retry ->
                            updates.cancel()
                            if (running[params.jobId]?.params === params) {
                                updateTransferredNetworkBytes(params, network.transport.downloadedBytes, 0)
                                running.remove(params.jobId)
                                diagnostics.finished()
                                jobFinished(params, retry)
                            }
                        }
                    } finally { updates.cancel() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Unexpected framework/storage errors retain the durable queue for a later retry.
                diagnostics.failed()
            } finally {
                network.close()
                if (running[params.jobId]?.params === params) {
                    running.remove(params.jobId)
                    DownloadRuntime.get(this@DownloadJobService).scheduler.finished()
                    jobFinished(params, true)
                }
            }
        }
        running[params.jobId] = Execution(params, job, network)
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.let { execution ->
            execution.job.cancel()
            execution.network.close()
        }
        diagnostics.stopped(params.stopReason)
        // Room state survives both system stops and explicit notification pauses.
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onNetworkChanged(params: JobParameters) {
        // Binder may supply a new parameter instance; keep the original for jobFinished.
        running[params.jobId]?.network?.update(params.network)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notification(tasks: List<DownloadRecord>): Notification {
        val task = tasks.firstOrNull { it.status == "running" } ?: tasks.firstOrNull()
        val total = tasks.sumOf { it.total }
        val received = tasks.sumOf { it.received }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = Intent(this, DownloadActionReceiver::class.java).apply {
            action = "app.zcomic.PAUSE_DOWNLOAD"
            data = android.net.Uri.Builder().scheme("zcomic").authority("download").appendPath("queue").build()
            putExtra(DownloadScheduler.TASK_ID, "queue")
        }
        val control = PendingIntent.getBroadcast(this, 0, pause,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (total > 0)
            ((received.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val text = when {
            task == null -> "准备下载"
            task.error.isNotBlank() -> task.error
            task.total > 0 -> "剩余 ${tasks.size} 卷 · ${received / 1048576} / ${total / 1048576} MB"
            else -> "等待下载"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.let { "${it.comicTitle} · ${it.volumeTitle}" } ?: "zComic 下载")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, tasks.isEmpty() || tasks.any { it.total <= 0 })
            .addAction(Notification.Action.Builder(null, "暂停全部", control).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "comic-downloads"

        /** Estimated total for this execution, never the shrinking number of remaining bytes. */
        internal fun estimatedTransferBytes(tasks: List<DownloadRecord>, transferred: Long): Long? {
            if (tasks.any { it.total <= 0 }) return null
            return transferred + tasks.sumOf { (it.total - it.received).coerceAtLeast(0) }
        }
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.zcomic.PAUSE_DOWNLOAD") return
        val id = intent.getStringExtra(DownloadScheduler.TASK_ID) ?: return
        val pending = goAsync()
        DownloadRuntime.get(context).scope.launch {
            try {
                if (id == "queue") DownloadRuntime.get(context).queue.pauseAll()
                else DownloadRuntime.get(context).queue.pause(id)
            }
            finally { pending.finish() }
        }
    }
}
