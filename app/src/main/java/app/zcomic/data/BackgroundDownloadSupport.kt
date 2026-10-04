package app.zcomic.data

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal data class BackgroundAccessState(val batteryExempt: Boolean, val backgroundRestricted: Boolean,
    val batterySaver: Boolean, val notifications: Boolean, val dataSaver: Boolean)

/** Public Android status cannot confirm HyperOS's separate autostart/no-restrictions switches. */
internal object BackgroundDownloadSupport {
    fun isXiaomi(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Boolean =
        listOf(manufacturer, brand).any { it.lowercase() in setOf("xiaomi", "redmi", "poco") }

    fun state(context: Context): BackgroundAccessState {
        val power = context.getSystemService(PowerManager::class.java)
        return BackgroundAccessState(power.isIgnoringBatteryOptimizations(context.packageName),
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted, power.isPowerSaveMode,
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
    }

    fun appSettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    fun batteryIntents(context: Context): List<Intent> = buildList {
        if (isXiaomi()) add(Intent().setComponent(ComponentName("com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName).putExtra("package_label", "zComic"))
        add(appSettings(context))
    }

    fun autostartIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")), appSettings(context))

    fun exemptionIntents(context: Context) = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appSettings(context))

    fun notificationIntents(context: Context) = listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), appSettings(context))

    /** OEM activities are best-effort, may be removed or denied on a new firmware. */
    fun open(context: Context, intents: List<Intent>, launch: (Intent) -> Unit = { context.startActivity(it) }): Boolean {
        for (intent in intents) {
            try { launch(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        return false
    }
}
