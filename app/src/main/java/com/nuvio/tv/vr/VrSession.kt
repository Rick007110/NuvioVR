package com.nuvio.tv.vr

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Shared state between the immersive [VrActivity] (scene, controllers) and the
 * [com.nuvio.tv.MainActivity] panel that hosts the app UI. Both run in the same process.
 */
object VrSession {

    private const val PREFS_NAME = "vr_settings"
    private const val KEY_PASSTHROUGH = "passthrough_enabled"
    private const val KEY_DIM_DURING_PLAYBACK = "dim_during_playback"

    private var prefs: SharedPreferences? = null

    private val _passthroughEnabled = MutableStateFlow(false)

    /** Show the real room instead of the virtual environment. Off by default. */
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    private val _dimDuringPlayback = MutableStateFlow(true)

    /** Darken the virtual environment while a video plays. */
    val dimDuringPlayback: StateFlow<Boolean> = _dimDuringPlayback.asStateFlow()

    private val _isPlayingVideo = MutableStateFlow(false)

    /** True while the internal player is on screen. */
    val isPlayingVideo: StateFlow<Boolean> = _isPlayingVideo.asStateFlow()

    private val _recenterRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Asks the scene to move the screen back in front of the user. */
    val recenterRequests: SharedFlow<Unit> = _recenterRequests.asSharedFlow()

    private var panelActivity = WeakReference<Activity>(null)

    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences
        _passthroughEnabled.value = preferences.getBoolean(KEY_PASSTHROUGH, false)
        _dimDuringPlayback.value = preferences.getBoolean(KEY_DIM_DURING_PLAYBACK, true)
    }

    fun setPassthroughEnabled(enabled: Boolean) {
        _passthroughEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_PASSTHROUGH, enabled)?.apply()
    }

    fun togglePassthrough() = setPassthroughEnabled(!_passthroughEnabled.value)

    fun setDimDuringPlayback(enabled: Boolean) {
        _dimDuringPlayback.value = enabled
        prefs?.edit()?.putBoolean(KEY_DIM_DURING_PLAYBACK, enabled)?.apply()
    }

    fun requestRecenter() {
        _recenterRequests.tryEmit(Unit)
    }

    fun setPlayingVideo(playing: Boolean) {
        _isPlayingVideo.value = playing
    }

    fun attachPanelActivity(activity: Activity) {
        init(activity)
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
