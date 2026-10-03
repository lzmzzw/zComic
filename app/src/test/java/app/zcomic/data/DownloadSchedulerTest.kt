package app.zcomic.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import app.zcomic.DownloadJobService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        scheduler = DownloadScheduler(context, jobs)
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

    private fun record(id: String) = DownloadRecord(id, "comic", "漫画", "卷 01", 1,
        "https://example.invalid/c/comic")
}
