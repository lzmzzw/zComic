package app.zcomic

import android.content.Context
import android.os.PowerManager

/** Bounded CPU lease for the foreground executor; does not keep the screen lit. */
internal class DownloadWakeLease(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zcomic:downloads").apply { setReferenceCounted(false) }
    fun renew() { lock.acquire(TIMEOUT_MS) }
    fun close() { if (lock.isHeld) lock.release() }
    companion object {
        const val TIMEOUT_MS = 10 * 60 * 1_000L
        const val RENEW_MS = 5 * 60 * 1_000L
    }
}
