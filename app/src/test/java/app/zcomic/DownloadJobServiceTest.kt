package app.zcomic

import android.app.job.JobParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadJobServiceTest {
    private lateinit var controller: ServiceController<DownloadJobService>
    private lateinit var service: DownloadJobService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        controller = Robolectric.buildService(DownloadJobService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun onStopCancelsByJobIdWhenBinderSuppliesDifferentParameterInstance() {
        // Android can reconstruct JobParameters for the stop callback. Object identity
        // must not leave the original coroutine downloading after the system stops it.
        val start = Shadow.newInstanceOf(JobParameters::class.java)
        val stop = Shadow.newInstanceOf(JobParameters::class.java)
        assertNotSame(start, stop)
        assertEquals(start.jobId, stop.jobId)
        val execution = Job()
        val running = ReflectionHelpers.getField<MutableMap<Int, Any>>(service, "running")
        val type = DownloadJobService::class.java.declaredClasses.single { it.simpleName == "Execution" }
        val constructor = type.getDeclaredConstructor(JobParameters::class.java, Job::class.java)
            .apply { isAccessible = true }
        // Seed only the active execution to avoid starting the account/runtime/network stack.
        running[start.jobId] = constructor.newInstance(start, execution)

        assertTrue(service.onStopJob(stop))

        assertTrue(execution.isCancelled)
        assertTrue(running.isEmpty())
    }
}
