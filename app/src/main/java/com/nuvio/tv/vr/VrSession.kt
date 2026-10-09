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
    private const val KEY_CINEMA_HALL = "cinema_hall"

    private var prefs: SharedPreferences? = null

    private val _passthroughEnabled = MutableStateFlow(false)

    /** Show the real room instead of the virtual environment. Off by default. */
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    private val _dimDuringPlayback = MutableStateFlow(true)

    /** Darken the virtual environment while a video plays. */
    val dimDuringPlayback: StateFlow<Boolean> = _dimDuringPlayback.asStateFlow()

    private val _cinemaHall = MutableStateFlow(true)

    /** Use the cinema hall as the virtual environment (otherwise the night sky). */
    val cinemaHall: StateFlow<Boolean> = _cinemaHall.asStateFlow()

    private val _screenColor = MutableStateFlow<Int?>(null)

    /**
     * Average colour of the video currently playing (ARGB), sampled a few times per second,
     * so the cinema hall can be lit by the screen. Null when nothing is playing.
     */
    val screenColor: StateFlow<Int?> = _screenColor.asStateFlow()

    private val _isPlayingVideo = MutableStateFlow(false)

    /** True while the internal player is on screen. */
    val isPlayingVideo: StateFlow<Boolean> = _isPlayingVideo.asStateFlow()

    private val _recenterRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Asks the scene to move the screen back in front of the user. */
    val recenterRequests: SharedFlow<Unit> = _recenterRequests.asSharedFlow()

    /** What the cinema control bar shows about the player; null when nothing is playing. */
    data class PlayerState(
        val title: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    /** Commands from the cinema control bar to the player. */
    sealed interface PlayerCommand {
        data object PlayPause : PlayerCommand
        data object SeekBackward : PlayerCommand
        data object SeekForward : PlayerCommand
        data class SeekTo(val positionMs: Long) : PlayerCommand
        data object Subtitles : PlayerCommand
        data object Audio : PlayerCommand
        data object ToggleScreenControls : PlayerCommand
        data object Back : PlayerCommand
    }

    private val _playerState = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = _playerState.asStateFlow()

    private val _playerCommands = MutableSharedFlow<PlayerCommand>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val playerCommands: SharedFlow<PlayerCommand> = _playerCommands.asSharedFlow()

    fun setPlayerState(state: PlayerState?) {
        _playerState.value = state
    }

    fun sendPlayerCommand(command: PlayerCommand) {
        _playerCommands.tryEmit(command)
    }

    private var panelActivity = WeakReference<Activity>(null)

    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences
        _passthroughEnabled.value = preferences.getBoolean(KEY_PASSTHROUGH, false)
        _dimDuringPlayback.value = preferences.getBoolean(KEY_DIM_DURING_PLAYBACK, true)
        _cinemaHall.value = preferences.getBoolean(KEY_CINEMA_HALL, true)
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

    fun setCinemaHall(enabled: Boolean) {
        _cinemaHall.value = enabled
        prefs?.edit()?.putBoolean(KEY_CINEMA_HALL, enabled)?.apply()
    }

    fun setScreenColor(color: Int?) {
        _screenColor.value = color
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
