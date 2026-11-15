package com.mythbrix.cinetor

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mythbrix.cinetor.core.player.PlaybackService
import com.mythbrix.cinetor.core.player.PlayerUiState
import com.mythbrix.cinetor.feature.player.PipController
import com.mythbrix.cinetor.feature.player.HostMode
import com.mythbrix.cinetor.feature.music.shouldPauseOnBackground
import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var pip: PipController
    private var backgroundPlay = false
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (Android 12+: AVD ring draw + play pop) → Compose AnimatedSplash.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as CinetorApplication).container
        val playback = container.playback
        pip = PipController(this, playback).also { it.register() }

        lifecycleScope.launch { container.settings.backgroundPlay.collect { backgroundPlay = it } }
        lifecycleScope.launch {
            container.settings.autoPip.collect {
                pip.autoPip = it
                pip.update()
            }
        }
        // Keep PiP params (auto-enter flag, play/pause icon, aspect) in sync with playback.
        lifecycleScope.launch {
            val ui = playback.player.flatMapLatest { p -> p?.state ?: flowOf(PlayerUiState()) }
                .map { Triple(it.playWhenReady, it.videoWidth, it.videoHeight) }
                .distinctUntilChanged()
            combine(playback.state.map { it.active }.distinctUntilChanged(), ui) { a, u -> a to u }
                .collect { pip.update() }
        }
        setContent { CinetorRoot(container) }
    }

    override fun onStart() {
        super.onStart()
        // Bind the MediaSessionService (notification, lock screen, BT keys) once per activity.
        if (controllerFuture == null) {
            controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
                .buildAsync()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        (application as CinetorApplication).container.playback.setPip(isInPictureInPictureMode)
    }

    override fun onStop() {
        super.onStop()
        val playback = (application as CinetorApplication).container.playback
        playback.onBackground()
        // PiP window closed / app backgrounded: video audio continues only with Background play on; music always.
        val container = (application as CinetorApplication).container
        if (shouldPauseOnBackground(
                videoActive = playback.state.value.item != null,
                musicActive = container.music.state.value.active,
                backgroundPlay = backgroundPlay,
                inPip = isInPictureInPictureMode,
            )
        ) {
            playback.setPip(false)
            playback.pause()
        }
    }

    /** Live TV remote keys (fullscreen live): CH+/CH−, number entry, D-pad up/down zaps when not used for focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playback = (application as CinetorApplication).container.playback
        val s = playback.state.value
        if (s.live && s.mode == HostMode.FULL && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { playback.zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { playback.zap(-1); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { playback.channelDigit(event.keyCode - KeyEvent.KEYCODE_0); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (super.dispatchKeyEvent(event)) return true
                    playback.zap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        pip.unregister()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}
