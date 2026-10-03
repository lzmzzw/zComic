package app.zcomic.data

import android.app.job.JobScheduler
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerTest {
    private val clock = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(clock)
    private lateinit var context: Context
    private lateinit var db: ComicDatabase
    private lateinit var jobs: JobScheduler
    private lateinit var scheduler: DownloadScheduler
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ComicDatabase::class.java)
            .setQueryCoroutineContext(dispatcher)
            .build()
        jobs = context.getSystemService(JobScheduler::class.java).forNamespace("zcomic-downloads")
        jobs.cancelAll()
        scheduler = DownloadScheduler(context, jobs)
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        jobs.cancelAll()
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun restoresOnlyQueuedAndInterruptedRunningRecordsAfterManagerRecreation() = runTest(dispatcher) {
        listOf("queued", "running", "paused", "failed", "completed").forEach { status ->
            db.dao().putDownload(record(status).copy(status = status, received = 100, total = 500))
        }

        manager().restore()

        assertEquals(setOf("queue"), scheduledIds())
        assertEquals(100L, db.dao().download("running")!!.received)
        assertEquals("paused", db.dao().download("paused")!!.status)
    }

    @Test
    fun restoringHundredsOfPersistedVolumesUsesOnlyOneSystemJob() = runTest(dispatcher) {
        repeat(200) { index ->
            db.dao().putDownload(record("volume-$index").copy(
                status = if (index % 2 == 0) "queued" else "running",
                received = index.toLong(), total = 1_000))
        }

        manager().restore()

        assertEquals(200, db.dao().pendingDownloads().size)
        assertEquals(1, jobs.allPendingJobs.size)
        assertEquals(setOf("queue"), scheduledIds())
        assertEquals(199L, db.dao().download("volume-199")!!.received)
    }

    @Test
    fun pauseWaitsForOldExecutionBeforeWritingPausedAndResumeRetainsProgress() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val queue = manager { entered.complete(Unit); awaitCancellation() }
        val task = record("pause").copy(received = 100, total = 500)
        db.dao().putDownload(task)
        scheduler.schedule(task)
        val execution = launch { queue.execute(task.id) }
        entered.await()

        queue.pause(task.id)
        runCurrent()

        assertTrue(execution.isCancelled)
        assertEquals("paused", db.dao().download(task.id)!!.status)
        assertEquals(100L, db.dao().download(task.id)!!.received)
        assertEquals(setOf("queue"), scheduledIds())
        // A late system start cannot revive a user's paused task.
        assertFalse(queue.execute(task.id))
        assertEquals("paused", db.dao().download(task.id)!!.status)

        queue.resume(task.id)
        assertEquals("queued", db.dao().download(task.id)!!.status)
        assertEquals(100L, db.dao().download(task.id)!!.received)
        assertEquals(setOf("queue"), scheduledIds())
    }

    @Test
    fun cancelWaitsForOldExecutionAndCannotRecreateDeletedRecord() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val queue = manager { entered.complete(Unit); awaitCancellation() }
        val task = record("cancel")
        db.dao().putDownload(task)
        scheduler.schedule(task)
        val execution = launch { queue.execute(task.id) }
        entered.await()

        queue.cancel(task.id)
        runCurrent()

        assertTrue(execution.isCancelled)
        assertNull(db.dao().download(task.id))
        assertEquals(setOf("queue"), scheduledIds())
        assertFalse(queue.execute(task.id))
        assertNull(db.dao().download(task.id))
    }

    @Test
    fun systemInterruptionKeepsQueuedRecordAndItsResumeOffset() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val queue = manager { entered.complete(Unit); awaitCancellation() }
        val task = record("stopped").copy(received = 123, total = 456)
        db.dao().putDownload(task)
        val execution = launch { queue.execute(task.id) }
        entered.await()

        execution.cancelAndJoin()

        assertEquals("queued", db.dao().download(task.id)!!.status)
        assertEquals(123L, db.dao().download(task.id)!!.received)
        assertEquals(456L, db.dao().download(task.id)!!.total)
        manager().restore()
        assertEquals(setOf("queue"), scheduledIds())
    }

    @Test
    fun atMostTwoExecutionsEnterTransferAndWaitingThirdCanStartAfterInterruption() = runTest(dispatcher) {
        var entered = 0
        val queue = manager { entered++; awaitCancellation() }
        val tasks = (1..3).map { record("volume-$it") }
        tasks.forEach { db.dao().putDownload(it) }
        val executions = tasks.map { task -> launch { queue.execute(task.id) } }
        runCurrent()

        assertEquals(2, entered)
        assertEquals(listOf("running", "running", "queued"), tasks.map { db.dao().download(it.id)!!.status })

        executions.first().cancelAndJoin()
        runCurrent()

        assertEquals(3, entered)
        assertEquals("running", db.dao().download(tasks.last().id)!!.status)
        executions.drop(1).forEach { it.cancelAndJoin() }
        assertEquals(listOf("queued", "queued", "queued"), tasks.map { db.dao().download(it.id)!!.status })
    }

    @Test
    fun drainSurvivesIndividualPauseAndCancelAndContinuesWithQueuedVolumes() = runTest(dispatcher) {
        var entered = 0
        val queue = manager { entered++; awaitCancellation() }
        val tasks = (1..4).map { index -> record("volume-$index").copy(createdAt = index.toLong()) }
        tasks.forEach { db.dao().putDownload(it) }
        queue.restore()
        val finishes = mutableListOf<Boolean>()
        val draining = launch { queue.drain { finishes += it } }
        runCurrent()
        assertEquals(2, entered)

        queue.pause(tasks[0].id)
        runCurrent()
        assertTrue(draining.isActive)
        assertEquals("paused", db.dao().download(tasks[0].id)!!.status)
        assertEquals("running", db.dao().download(tasks[1].id)!!.status)
        assertEquals(3, entered)
        assertEquals("running", db.dao().download(tasks[2].id)!!.status)

        queue.cancel(tasks[1].id)
        runCurrent()
        assertTrue(draining.isActive)
        assertNull(db.dao().download(tasks[1].id))
        assertEquals(4, entered)
        assertEquals("running", db.dao().download(tasks[2].id)!!.status)
        assertEquals("running", db.dao().download(tasks[3].id)!!.status)
        assertEquals(1, jobs.allPendingJobs.size)

        queue.pause(tasks[2].id)
        queue.cancel(tasks[3].id)
        draining.join()

        assertEquals(listOf(false), finishes)
        assertEquals("paused", db.dao().download(tasks[0].id)!!.status)
        assertEquals("paused", db.dao().download(tasks[2].id)!!.status)
        assertNull(db.dao().download(tasks[1].id))
        assertNull(db.dao().download(tasks[3].id))
    }

    @Test
    fun resumingSameVolumeDuringActiveDrainStartsItsNewRevision() = runTest(dispatcher) {
        var entered = 0
        val queue = manager { entered++; awaitCancellation() }
        val first = record("volume-1").copy(createdAt = 1, received = 64, total = 256)
        val second = record("volume-2").copy(createdAt = 2)
        db.dao().putDownload(first)
        db.dao().putDownload(second)
        queue.restore()
        val finishes = mutableListOf<Boolean>()
        val draining = launch { queue.drain { finishes += it } }
        runCurrent()
        assertEquals(2, entered)

        queue.pause(first.id)
        queue.resume(first.id)
        runCurrent()
        assertTrue(draining.isActive)
        assertEquals("queued", db.dao().download(first.id)!!.status)
        assertEquals("running", db.dao().download(second.id)!!.status)

        // The current pass has already attempted this ID. Resuming must cause a
        // fresh attempt in the same drain instead of leaving a queued volume idle.
        queue.pause(second.id)
        runCurrent()
        assertEquals(3, entered)
        assertTrue(draining.isActive)
        assertEquals("running", db.dao().download(first.id)!!.status)
        assertEquals(64L, db.dao().download(first.id)!!.received)
        assertEquals(1, jobs.allPendingJobs.size)
        assertTrue(finishes.isEmpty())

        queue.pause(first.id)
        draining.join()
        assertEquals(listOf(false), finishes)
        assertEquals("paused", db.dao().download(first.id)!!.status)
        assertEquals("paused", db.dao().download(second.id)!!.status)
    }

    @Test
    fun stoppingWholeDrainKeepsAllUnfinishedRecordsQueuedWithoutCompletionCallback() = runTest(dispatcher) {
        var entered = 0
        val queue = manager { entered++; awaitCancellation() }
        val tasks = (1..3).map { index -> record("volume-$index").copy(
            createdAt = index.toLong(), received = index.toLong(), total = 100) }
        tasks.forEach { db.dao().putDownload(it) }
        val finishes = mutableListOf<Boolean>()
        val draining = launch { queue.drain { finishes += it } }
        runCurrent()
        assertEquals(2, entered)

        draining.cancelAndJoin()

        assertTrue(finishes.isEmpty())
        assertEquals(listOf("queued", "queued", "queued"), tasks.map { db.dao().download(it.id)!!.status })
        assertEquals(listOf(1L, 2L, 3L), tasks.map { db.dao().download(it.id)!!.received })
    }

    private fun manager(authenticated: suspend (suspend () -> Unit) -> Unit = { it() }) =
        DownloadManager(context, db, ComicFiles(context), KmoeClient(server.url("/").toString().trimEnd('/')),
            scheduler, authenticated)

    private fun scheduledIds() = jobs.allPendingJobs.mapNotNull {
        it.extras.getString(DownloadScheduler.TASK_ID)
    }.toSet()

    private fun record(id: String) = DownloadRecord(id, "comic", "漫画", "卷 01", 1,
        server.url("/c/comic").toString())
}
