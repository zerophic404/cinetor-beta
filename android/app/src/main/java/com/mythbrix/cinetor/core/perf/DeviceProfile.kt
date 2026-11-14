package com.mythbrix.cinetor.core.perf

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Hardware facts used by [detectTier]. Plain data so tier rules stay pure + JVM-testable. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val sdkInt: Int,
    val cpuCores: Int,
    val isLowRamDevice: Boolean,
    val isTv: Boolean,
) {
    companion object {
        fun fromContext(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return DeviceProfile(
                totalRamBytes = mem.totalMem,
                sdkInt = Build.VERSION.SDK_INT,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                isLowRamDevice = am.isLowRamDevice,
                isTv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
            )
        }
    }
}
