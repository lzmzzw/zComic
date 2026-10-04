package app.zcomic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.zcomic.data.BackgroundDownloadSupport

@Composable
internal fun BackgroundDownloadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val xiaomi = remember { BackgroundDownloadSupport.isXiaomi() }
    var state by remember { mutableStateOf(BackgroundDownloadSupport.state(context)) }
    var message by remember { mutableStateOf("") }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = BackgroundDownloadSupport.state(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(intents: List<android.content.Intent>) {
        message = if (BackgroundDownloadSupport.open(context, intents)) ""
            else "未能打开设置，请在系统设置中搜索 zComic"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("后台下载设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (xiaomi) {
                Text("已启用小米 / 红米兼容下载。请把 zComic 的省电策略设为“无限制”，并允许后台自启动。", fontSize = 13.sp)
                TextButton(onClick = { open(BackgroundDownloadSupport.batteryIntents(context)) }) { Text("省电策略 · 选择无限制") }
                TextButton(onClick = { open(BackgroundDownloadSupport.autostartIntents(context)) }) { Text("后台自启动 · 允许 zComic") }
                Text("这两项设置需在系统页面确认，应用无法读取它们。若入口变动，在应用信息中找“省电策略”，在系统设置中搜索“后台自启动”。", fontSize = 12.sp)
                Text("仅有排队或下载中任务时恢复后台下载；全部完成、取消或暂停后停止，不常驻。已暂停或失败的任务需点击继续。", fontSize = 12.sp)
                Text("后台锁定：最近任务中长按 zComic 卡片，选择锁图标；或在手机管家 → 设置 → 加速 → 锁定应用中选择 zComic。锁定可避免一键清理，不等于解除省电限制。", fontSize = 12.sp)
            } else Text("下载使用系统后台任务。若切换应用后停止，请允许 zComic 在后台运行。", fontSize = 13.sp)
            Text("Android 电池优化：${if (state.batteryExempt) "已豁免" else "未豁免"}", fontSize = 12.sp)
            if (!state.batteryExempt) TextButton(onClick = { open(BackgroundDownloadSupport.exemptionIntents(context)) }) { Text("允许持续后台下载") }
            if (xiaomi) Text("Android 的电池优化豁免与 HyperOS 的“无限制”是不同设置，请分别检查。", fontSize = 12.sp)
            if (state.backgroundRestricted) Text("系统报告：后台运行受限", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (state.batterySaver) Text("省电模式已开启：下载期间建议关闭省电 / 超级省电。", fontSize = 12.sp)
            if (state.dataSaver) Text("流量节省已限制后台联网：在应用信息中允许后台数据或无限制流量。", fontSize = 12.sp)
            Text("下载通知：${if (state.notifications) "已允许" else "未允许"}", fontSize = 12.sp)
            TextButton(onClick = { open(BackgroundDownloadSupport.notificationIntents(context)) }) { Text("下载通知设置") }
            TextButton(onClick = { open(listOf(BackgroundDownloadSupport.appSettings(context))) }) { Text("应用信息 · 后台联网与电量") }
            Text("设置完成后返回应用继续下载，再切换应用或锁屏验证。系统中强行停止应用会中断下载，重新打开后恢复。", fontSize = 12.sp)
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
