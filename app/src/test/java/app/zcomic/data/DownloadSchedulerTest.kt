package app.zcomic.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import app.zcomic.DownloadJobService
import app.zcomic.DownloadForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadSchedulerTest {
    private lateinit var context: Context
    private lateinit var jobs: JobScheduler
    private lateinit var scheduler: DownloadScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        jobs = context.getSystemService(JobScheduler::class.java).forNamespace("zcomic-downloads")
        jobs.cancelAll()
        // Robolectric does not share namespace shadows across forNamespace() instances.
        scheduler = DownloadScheduler(context, jobs, usesForegroundService = false)
    }

    @Test
    fun schedulesPersistedUserInitiatedNetworkJobWithRemainingBytes() {
        scheduler.schedule(record("volume-1").copy(received = 1_024, total = 4_096))

        val job = jobs.allPendingJobs.single()
        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(DownloadJobService::class.java.name, job.service.className)
        assertEquals("queue", job.extras.getString(DownloadScheduler.TASK_ID))
        assertEquals(3_072L, job.estimatedNetworkDownloadBytes)
        assertEquals(0L, job.estimatedNetworkUploadBytes)
    }

    @Test
    fun schedulingExistingVolumeKeepsOriginalJobInsteadOfRestartingIt() {
        scheduler.schedule(record("volume-1"))
        val original = jobs.allPendingJobs.single()

        scheduler.schedule(record("volume-1").copy(received = 10, total = 20))

        assertEquals(1, jobs.allPendingJobs.size)
        assertSame(original, jobs.allPendingJobs.single())
    }

    @Test
    fun hundredsOfVolumesAndHashCollisionsShareOneSystemJob() {
        // Per-volume JobInfo would hit Android's per-app job limit on a large comic.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        scheduler.schedule(record("Aa"))
        scheduler.schedule(record("BB"))
        repeat(200) { scheduler.schedule(record("volume-$it")) }

        assertEquals(1, jobs.allPendingJobs.size)
        assertEquals("queue", jobs.allPendingJobs.single().extras.getString(DownloadScheduler.TASK_ID))
    }

    @Test
    fun enqueueAfterDrainFinishedReplacesEndingJobEvenBeforeSystemRemovesIt() {
        scheduler.schedule(record("volume-1"))
        val ending = jobs.allPendingJobs.single()
        scheduler.finished()

        scheduler.schedule(record("volume-2"))

        assertEquals(1, jobs.allPendingJobs.size)
        assertNotSame(ending, jobs.allPendingJobs.single())
        assertEquals("queue", jobs.allPendingJobs.single().extras.getString(DownloadScheduler.TASK_ID))
    }

    @Test
    fun foregroundModeCancelsLegacyJobAndStartsOneServiceForLargeQueue() {
        scheduler.schedule(record("legacy"))
        val foreground = DownloadScheduler(context, jobs, usesForegroundService = true)
        repeat(200) { foreground.schedule(record("volume-$it")) }
        val app = shadowOf(RuntimeEnvironment.getApplication())
        val start = app.nextStartedService
        assertEquals(DownloadForegroundService::class.java.name, start.component!!.className)
        assertEquals(1, start.getIntExtra(DownloadScheduler.GENERATION, 0))
        org.junit.Assert.assertNull(app.nextStartedService)
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    @Test
    fun foregroundStopAllowsRestartButOldServiceDestructionCannotClearNewRequest() {
        val foreground = DownloadScheduler(context, jobs, usesForegroundService = true)
        foreground.schedule(record("volume-1"))
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.nextStartedService
        foreground.foregroundStopped(1)
        foreground.schedule(record("volume-2"))
        assertEquals(2, app.nextStartedService.getIntExtra(DownloadScheduler.GENERATION, 0))
        foreground.foregroundStopped(1)
        foreground.schedule(record("volume-3"))
        org.junit.Assert.assertNull(app.nextStartedService)
        foreground.cancelAll()
        assertEquals(DownloadForegroundService::class.java.name, app.nextStoppedService.component!!.className)
    }

    @Test
    fun onlyActiveQueueAllowsRecoveryAndEmptyQueueOrUserPauseDisablesIt() {
        val foreground = DownloadScheduler(context, jobs, usesForegroundService = true)
        foreground.cancelAll()
        assertTrue(!DownloadScheduler.recoveryPending(context))
        listOf("completed", "paused", "failed").forEach { status ->
            foreground.schedule(record("inactive").copy(status = status))
        }
        assertTrue(!DownloadScheduler.recoveryPending(context))
        org.junit.Assert.assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)

        foreground.schedule(record("active"))
        assertTrue(DownloadScheduler.recoveryPending(context))
        foreground.finished(hasPending = true)
        assertTrue(DownloadScheduler.recoveryPending(context))
        foreground.finished(hasPending = false)
        assertTrue(!DownloadScheduler.recoveryPending(context))

        foreground.schedule(record("another"))
        assertTrue(DownloadScheduler.recoveryPending(context))
        foreground.cancelAll()
        assertTrue(!DownloadScheduler.recoveryPending(context))
    }

    private fun record(id: String) = DownloadRecord(id, "comic", "漫画", "卷 01", 1,
        "https://example.invalid/c/comic")
}
