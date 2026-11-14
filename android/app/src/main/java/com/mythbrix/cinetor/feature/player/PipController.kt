package com.mythbrix.cinetor.feature.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.mythbrix.cinetor.R

/** PiP window buttons. */
enum class PipAction(val code: Int, val title: String) {
    REWIND(1, "Back 10 seconds"),
    TOGGLE(2, "Play/Pause"),
    FORWARD(3, "Forward 10 seconds"),
    ;

    companion object {
        fun fromCode(code: Int): PipAction? = entries.firstOrNull { it.code == code }
    }
}

/** Pure mapping PiP button → player command. */
fun PlayerStateHolder.applyPip(action: PipAction) {
    when (action) {
        PipAction.REWIND -> seekBy(-10_000)
        PipAction.TOGGLE -> togglePlay()
        PipAction.FORWARD -> seekBy(10_000)
    }
}

/** PiP aspect ratio from the video size, clamped to Android's 1:2.39…2.39:1 (16:9 when unknown). */
fun pipAspect(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 16 to 9
    val r = width.toFloat() / height
    return when {
        r > 2.39f -> 239 to 100
        r < 1f / 2.39f -> 100 to 239
        else -> width to height
    }
}

/**
 * System Picture-in-Picture: auto-enter on Home while a video plays (API 31+ setAutoEnterEnabled,
 * API 26–30 via onUserLeaveHint), ±10 s / play-pause actions, aspect from the video.
 */
class PipController(private val activity: ComponentActivity, private val holder: PlayerStateHolder) {
    var autoPip: Boolean = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PipAction.fromCode(intent.getIntExtra(EXTRA_CODE, -1))?.let { holder.applyPip(it) }
        }
    }
    private var registered = false

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }

    private fun shouldEnter(): Boolean {
        val s = holder.state.value
        return autoPip && s.active && holder.playback.playWhenReady
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun action(a: PipAction, iconRes: Int): RemoteAction {
        val intent = Intent(ACTION).setPackage(activity.packageName).putExtra(EXTRA_CODE, a.code)
        val pi = PendingIntent.getBroadcast(
            activity, a.code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), a.title, a.title, pi)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun params(): PictureInPictureParams {
        val ui = holder.playback
        val (w, h) = pipAspect(ui.videoWidth, ui.videoHeight)
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            .setActions(
                listOf(
                    action(PipAction.REWIND, R.drawable.ic_pip_replay),
                    action(PipAction.TOGGLE, if (ui.playWhenReady) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
                    action(PipAction.FORWARD, R.drawable.ic_pip_forward),
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(shouldEnter())
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** Refresh params (play state / aspect / auto-enter flag changed). */
    fun update() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supported) return
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    /** API 26–30: Home pressed while playing → enter PiP manually. */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!supported || !shouldEnter()) return
        runCatching { activity.enterPictureInPictureMode(params()) }
    }

    companion object {
        const val ACTION = "com.mythbrix.cinetor.PIP_CONTROL"
        const val EXTRA_CODE = "code"
    }
}
