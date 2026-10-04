package app.zcomic

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
import app.zcomic.data.BackgroundDownloadSupport
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
        DownloadNotification.createChannel(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.extras.getString(DownloadScheduler.TASK_ID) != "queue") return false
        // An upgraded Xiaomi device can still have v8's persisted job after a reboot.
        // Keep its Room queue for the next visible service start; never launch dataSync from boot.
        if (BackgroundDownloadSupport.isXiaomi()) return false
        setNotification(params, DownloadNotification.ID, DownloadNotification.build(this, emptyList()), JOB_END_NOTIFICATION_POLICY_REMOVE)
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
                            setNotification(params, DownloadNotification.ID, DownloadNotification.build(this@DownloadJobService, active), JOB_END_NOTIFICATION_POLICY_REMOVE)
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

    companion object {
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
