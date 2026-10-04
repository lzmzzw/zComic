package app.zcomic

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import app.zcomic.data.DownloadScheduler
import app.zcomic.data.DownloadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadForegroundServiceTest {
    private lateinit var controller: ServiceController<DownloadForegroundService>
    private lateinit var service: DownloadForegroundService
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        controller = Robolectric.buildService(DownloadForegroundService::class.java).create()
        service = controller.get()
        DownloadScheduler(service, usesForegroundService = true).cancelAll()
    }
    @After fun tearDown() { controller.destroy(); Dispatchers.resetMain() }

    @Test fun startingPostsOngoingNotificationAndRepeatedStartKeepsOneExecutionAndWakeLease() {
        allowRecovery()
        assertEquals(Service.START_STICKY, service.onStartCommand(startIntent(), 0, 1))
        val notification = shadowOf(service).lastForegroundNotification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("暂停全部", notification.actions.single().title.toString())
        val worker = ReflectionHelpers.getField<Job>(service, "worker")
        val lease = ReflectionHelpers.getField<DownloadWakeLease>(service, "lease")
        val lock = ReflectionHelpers.getField<PowerManager.WakeLock>(lease, "lock")
        assertTrue(lock.isHeld)

        service.onStartCommand(startIntent(), 0, 2)

        assertSame(worker, ReflectionHelpers.getField<Job>(service, "worker"))
        assertSame(lease, ReflectionHelpers.getField<DownloadWakeLease>(service, "lease"))
        controller.destroy()
        assertTrue(worker.isCancelled)
        assertFalse(lock.isHeld)
        // Avoid destroying the same controller twice.
        controller = Robolectric.buildService(DownloadForegroundService::class.java).create()
    }

    @android.annotation.SuppressLint("NewApi")
    @Test fun systemTimeoutStopsForegroundAndReleasesCpuWithoutWaitingForTransfersToFlush() {
        allowRecovery()
        service.onStartCommand(startIntent(), 0, 1)
        val worker = ReflectionHelpers.getField<Job>(service, "worker")
        val lease = ReflectionHelpers.getField<DownloadWakeLease>(service, "lease")
        val lock = ReflectionHelpers.getField<PowerManager.WakeLock>(lease, "lock")

        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(worker.isCancelled)
        assertFalse(lock.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun manifestDeclaresOnlyDataSyncForTheForegroundDownloadService() {
        val info = service.packageManager.getServiceInfo(android.content.ComponentName(service,
            DownloadForegroundService::class.java), 0)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertFalse(info.exported)
    }

    @Test fun emptyQueueDoesNotPostNotificationOrRequestStickyRecovery() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertNull(ReflectionHelpers.getField<Job?>(service, "worker"))
        assertNull(ReflectionHelpers.getField<DownloadWakeLease?>(service, "lease"))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    private fun allowRecovery() {
        DownloadScheduler(service, usesForegroundService = true).schedule(
            DownloadRecord("test", "comic", "漫画", "卷 01", 1, "https://example.invalid/c/comic"))
    }

    private fun startIntent() = Intent(service, DownloadForegroundService::class.java)
        .putExtra(DownloadScheduler.GENERATION, 1)
}
