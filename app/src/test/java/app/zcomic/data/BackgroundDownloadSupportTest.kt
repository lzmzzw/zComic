package app.zcomic.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundDownloadSupportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun manufacturerAndBrandSelectXiaomiFamilyButNotOtherDevices() {
        assertTrue(BackgroundDownloadSupport.isXiaomi("Xiaomi", "Redmi"))
        assertTrue(BackgroundDownloadSupport.isXiaomi("unknown", "POCO"))
        assertFalse(BackgroundDownloadSupport.isXiaomi("Google", "google"))
    }

    @Test fun inaccessibleHyperOsBatteryScreenFallsBackToThisAppsDetails() {
        ShadowBuild.setManufacturer("Xiaomi")
        val attempts = mutableListOf<Intent>()
        assertTrue(BackgroundDownloadSupport.open(context, BackgroundDownloadSupport.batteryIntents(context)) {
            attempts += it
            if (it.component != null) throw SecurityException("not exported")
        })
        assertEquals(2, attempts.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.last().action)
        assertEquals("package:${context.packageName}", attempts.last().data.toString())
    }

    @Test fun removedAutostartActivityFallsBackAndMissingEverySettingsActivityReportsFailure() {
        val attempts = mutableListOf<Intent>()
        assertTrue(BackgroundDownloadSupport.open(context, BackgroundDownloadSupport.autostartIntents(context)) {
            attempts += it
            if (it.component != null) throw ActivityNotFoundException()
        })
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.last().action)
        assertFalse(BackgroundDownloadSupport.open(context, BackgroundDownloadSupport.exemptionIntents(context)) {
            throw ActivityNotFoundException()
        })
    }
}
