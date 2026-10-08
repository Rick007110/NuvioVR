package com.nuvio.tv.vr

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Shared state between the immersive [VrActivity] (scene, controllers) and the
 * [com.nuvio.tv.MainActivity] panel that hosts the app UI. Both run in the same process.
 */
object VrSession {

    /** How the world around the panel looks. */
    enum class Environment { PASSTHROUGH, CINEMA }

    private val _preferredEnvironment = MutableStateFlow(Environment.PASSTHROUGH)

    /** What the user picked for browsing (toggled with the Y button). */
    val preferredEnvironment: StateFlow<Environment> = _preferredEnvironment.asStateFlow()

    private val _isPlayingVideo = MutableStateFlow(false)

    /** True while the internal player is on screen; the scene switches to cinema mode. */
    val isPlayingVideo: StateFlow<Boolean> = _isPlayingVideo.asStateFlow()

    private var panelActivity = WeakReference<Activity>(null)

    fun togglePreferredEnvironment() {
        _preferredEnvironment.value = when (_preferredEnvironment.value) {
            Environment.PASSTHROUGH -> Environment.CINEMA
            Environment.CINEMA -> Environment.PASSTHROUGH
        }
    }

    fun setPlayingVideo(playing: Boolean) {
        _isPlayingVideo.value = playing
    }

    fun attachPanelActivity(activity: Activity) {
        panelActivity = WeakReference(activity)
    }

    fun detachPanelActivity(activity: Activity) {
        if (panelActivity.get() === activity) panelActivity.clear()
    }

    /**
     * Quest Touch controller buttons are not forwarded to panels as key events, so the
     * immersive activity reads them and injects the matching key into the app panel.
     */
    fun sendKeyToPanel(keyCode: Int) {
        val activity = panelActivity.get() ?: return
        activity.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
