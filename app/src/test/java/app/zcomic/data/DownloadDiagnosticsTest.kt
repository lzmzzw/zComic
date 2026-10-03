package app.zcomic.data

import android.app.job.JobParameters
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("download-diagnostics", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun systemStopReasonSurvivesRestartAndIsNotOverwrittenByStartingAgain() {
        val diagnostics = DownloadDiagnostics(context)
        diagnostics.started()
        diagnostics.stopped(JobParameters.STOP_REASON_BACKGROUND_RESTRICTION)
        val reason = diagnostics.lastStop.value
        assertTrue(reason.contains("系统限制后台运行"))
        val restored = DownloadDiagnostics(context)
        restored.started()
        assertEquals(reason, restored.lastStop.value)
        restored.finished()
        assertEquals(reason, DownloadDiagnostics(context).lastStop.value)
    }

    @Test fun uncleanProcessExitIsDistinguishedFromAnOrderlyFinish() {
        val diagnostics = DownloadDiagnostics(context)
        diagnostics.started()
        assertTrue(DownloadDiagnostics(context).lastStop.value.contains("进程被结束"))
        diagnostics.finished()
        assertTrue(DownloadDiagnostics(context).lastStop.value.contains("进程被结束"))
    }

    @Test fun notificationPauseDoesNotReplaceSystemDiagnosticWithAnAppCancellation() {
        val diagnostics = DownloadDiagnostics(context)
        diagnostics.stopped(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        val before = diagnostics.lastStop.value
        diagnostics.started()
        diagnostics.stopped(JobParameters.STOP_REASON_CANCELLED_BY_APP)
        assertEquals(before, diagnostics.lastStop.value)
    }
}
