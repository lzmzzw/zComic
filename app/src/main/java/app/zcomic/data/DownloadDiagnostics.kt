package app.zcomic.data

import android.app.job.JobParameters
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded local diagnostics: no URLs, account data, response bodies or exception messages. */
internal class DownloadDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences("download-diagnostics", Context.MODE_PRIVATE)
    private val _lastStop = MutableStateFlow(prefs.getString("last_stop", "").orEmpty())
    val lastStop = _lastStop.asStateFlow()

    init {
        if (prefs.getBoolean("running", false)) record("上次下载进程被结束，已保留进度")
    }

    fun started() { prefs.edit { putBoolean("running", true) } }
    fun finished() { prefs.edit { putBoolean("running", false) } }

    fun stopped(reason: Int) {
        finished()
        if (reason == JobParameters.STOP_REASON_CANCELLED_BY_APP) return
        val detail = when (reason) {
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "网络连接不可用"
            JobParameters.STOP_REASON_BACKGROUND_RESTRICTION,
            JobParameters.STOP_REASON_APP_STANDBY -> "系统限制后台运行"
            JobParameters.STOP_REASON_DEVICE_STATE -> "系统电量、温度或设备状态限制"
            JobParameters.STOP_REASON_TIMEOUT -> "系统运行时限"
            JobParameters.STOP_REASON_USER -> "系统中停止了下载"
            else -> "系统暂停下载"
        }
        record("$detail（原因 $reason），已保留进度")
    }

    fun failed() { finished(); record("后台任务异常退出，已保留进度并等待重试") }
    fun foregroundTimeout() { finished(); record("系统后台下载时限已到，保留进度；返回应用后继续") }
    fun foregroundInterrupted() {
        if (prefs.getBoolean("running", false)) record("后台下载服务被停止，已保留进度；请检查后台下载设置")
    }

    private fun record(message: String) {
        _lastStop.value = message
        prefs.edit { putString("last_stop", message); putBoolean("running", false) }
    }

    companion object {
        @Volatile private var instance: DownloadDiagnostics? = null
        fun get(context: Context): DownloadDiagnostics = instance ?: synchronized(this) {
            instance ?: DownloadDiagnostics(context.applicationContext).also { instance = it }
        }
    }
}
