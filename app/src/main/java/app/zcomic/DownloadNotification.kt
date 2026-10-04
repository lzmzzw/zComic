package app.zcomic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.zcomic.data.DownloadRecord
import app.zcomic.data.DownloadScheduler

internal object DownloadNotification {
    const val ID = 2
    const val FOREGROUND_ID = 3
    private const val CHANNEL = "comic-downloads"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "漫画下载", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context, tasks: List<DownloadRecord>): Notification {
        val task = tasks.firstOrNull { it.status == "running" } ?: tasks.firstOrNull()
        val total = tasks.sumOf { it.total }
        val received = tasks.sumOf { it.received }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = Intent(context, DownloadActionReceiver::class.java).apply {
            action = "app.zcomic.PAUSE_DOWNLOAD"
            data = android.net.Uri.parse("zcomic://download/queue")
            putExtra(DownloadScheduler.TASK_ID, "queue")
        }
        val control = PendingIntent.getBroadcast(context, 0, pause,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (total > 0) ((received.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val text = when {
            task == null -> "准备下载"
            task.error.isNotBlank() -> task.error
            task.total > 0 -> "剩余 ${tasks.size} 卷 · ${received / 1048576} / ${total / 1048576} MB"
            else -> "等待下载"
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.let { "${it.comicTitle} · ${it.volumeTitle}" } ?: "zComic 下载")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, tasks.isEmpty() || tasks.any { it.total <= 0 })
            .addAction(Notification.Action.Builder(null, "暂停全部", control).build()).build()
    }
}
