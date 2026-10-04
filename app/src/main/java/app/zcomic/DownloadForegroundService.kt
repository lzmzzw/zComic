package app.zcomic

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.zcomic.data.DownloadDiagnostics
import app.zcomic.data.DownloadRuntime
import app.zcomic.data.DownloadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Visible dataSync executor for Xiaomi firmware that interrupts the UIDT path. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class DownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diagnostics by lazy { DownloadDiagnostics.get(this) }
    private var worker: Job? = null
    private var lease: DownloadWakeLease? = null
    private var latestStartId = 0
    private var generation = 0

    override fun onCreate() { super.onCreate(); DownloadNotification.createChannel(this) }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        generation = intent?.getIntExtra(DownloadScheduler.GENERATION, generation) ?: generation
        // A system restart must not create an idle, permanently sticky service.
        if (!DownloadScheduler.recoveryPending(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startForeground(DownloadNotification.FOREGROUND_ID, DownloadNotification.build(this, emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isActive == true) return START_STICKY
        val ownLease = DownloadWakeLease(this).also { it.renew() }
        lease = ownLease
        val execution = scope.launch(start = CoroutineStart.LAZY) {
            val own = currentCoroutineContext()[Job]
            try {
                val runtime = DownloadRuntime.get(this@DownloadForegroundService)
                runtime.scheduler.started()
                diagnostics.started()
                coroutineScope {
                    var transferring = true
                    val renewal = launch {
                        while (isActive) {
                            delay(DownloadWakeLease.RENEW_MS)
                            if (transferring) ownLease.renew()
                        }
                    }
                    val updates = launch {
                        runtime.db.dao().downloads().sample(1_000).collectLatest { tasks ->
                            val active = tasks.filter { it.status in listOf("queued", "running") }
                            getSystemService(NotificationManager::class.java).notify(DownloadNotification.FOREGROUND_ID,
                                DownloadNotification.build(this@DownloadForegroundService, active))
                        }
                    }
                    try {
                        while (isActive) {
                            transferring = true
                            ownLease.renew()
                            var retry = false
                            runtime.queue.drain { pending ->
                                retry = pending
                                if (!pending && worker === own) {
                                    updates.cancel()
                                    worker = null
                                    diagnostics.finished()
                                    stopSelfResult(latestStartId)
                                }
                            }
                            if (!retry) break
                            runtime.scheduler.started()
                            transferring = false
                            ownLease.close()
                            delay(30_000)
                        }
                    } finally { updates.cancel(); renewal.cancel() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diagnostics.failed() }
            finally {
                ownLease.close()
                if (lease === ownLease) lease = null
                if (worker === own) {
                    worker = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
        worker = execution
        execution.start()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync quota: stop immediately, let cancellation flush durable state.
        diagnostics.foregroundTimeout()
        scope.cancel()
        lease?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        diagnostics.foregroundInterrupted()
        scope.cancel()
        lease?.close()
        DownloadRuntime.get(this).scheduler.foregroundStopped(generation)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
