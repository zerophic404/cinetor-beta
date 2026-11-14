package com.mythbrix.cinetor.feature.player.gestures

import android.app.Activity
import android.provider.Settings
import android.view.WindowManager

/** Window brightness override for the player; [restore] hands control back to the system. */
class BrightnessController(private val activity: Activity?) {
    fun get(): Float {
        val a = activity ?: return 0.5f
        val w = a.window.attributes.screenBrightness
        if (w >= 0f) return w
        return runCatching {
            Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    fun set(value: Float) {
        val a = activity ?: return
        val lp = a.window.attributes
        lp.screenBrightness = value.coerceIn(0.01f, 1f)
        a.window.attributes = lp
    }

    fun restore() {
        val a = activity ?: return
        val lp = a.window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        a.window.attributes = lp
    }
}
