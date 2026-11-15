package com.mythbrix.cinetor.core.legal

import com.mythbrix.cinetor.BuildConfig

/**
 * Local developer debug log (docs/requirements-hardening.md §2). OFF by default, toggled in
 * Settings → Developer, kept in memory only (never written to shared storage, never auto-sent).
 * Release builds drop every call unless the user explicitly enabled the toggle, so request URLs,
 * headers and stream URLs are not in logcat for a shipped APK.
 */
object DebugLog {
    private const val MAX = 300

    @Volatile
    var enabled: Boolean = false
        private set

    private val lines = ArrayDeque<String>()

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) clear()
    }

    /** [detail] is produced lazily: nothing is even formatted while the log is off. */
    fun log(tag: String, detail: () -> String) {
        if (!enabled) return
        val line = "${System.currentTimeMillis()} $tag ${detail()}"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Cinetor/$tag", line)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
