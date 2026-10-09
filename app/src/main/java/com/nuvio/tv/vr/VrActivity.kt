package com.nuvio.tv.vr

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.net.toUri
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The app's launcher on Quest. Owns the OpenXR scene and hosts the regular [MainActivity]
 * UI on a floating, grabbable screen. Controller rays and hand-tracking pinches reach the
 * panel as pointer events (see [VrPointerBridge]); face buttons are mapped here.
 *
 * Environments: a classic cinema hall (default) or a night sky; passthrough (the real room)
 * is opt-in from Settings > VR, the player's passthrough button or the Y button. In the
 * cinema, playback moves the picture onto the big screen on the stage, dims the house lights
 * and tints the hall with the colours of the film ([VrScreenLight]).
 */
class VrActivity : AppSystemActivity() {

    private enum class Environment { PASSTHROUGH, NIGHT_SKY, CINEMA }

    private data class SceneState(
        val environment: Environment,
        val playing: Boolean,
        val dimDuringPlayback: Boolean
    )

    private val scope = MainScope()
    private var mainPanel: Entity? = null
    private var sky: Entity? = null
    private var skyDim: Entity? = null
    private var floor: Entity? = null
    private var hall: Entity? = null
    private var hallLights: Entity? = null
    private var controlBar: Entity? = null
    private var controlBarShown = false

    private var sceneState: SceneState? = null
    private var onBigScreen = false

    // Smoothed lighting, updated every frame towards the target for the current state.
    private val ambient = FloatArray(3) { BROWSE_AMBIENT }
    private val sun = FloatArray(3) { BROWSE_SUN }
    private var environmentIntensity = BROWSE_ENVIRONMENT_INTENSITY
    private var frame = 0

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ActivityPanelRegistration(
            R.id.vr_main_panel,
            classIdCreator = { MainActivity::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = SCREEN_WIDTH_M, height = SCREEN_WIDTH_M * 9f / 16f),
                    // The UI was designed for a 960x540dp TV canvas; 320 dpi renders it at 1920x1080.
                    display = DpDisplayOptions(960f, 540f, dpi = 320),
                    style = PanelStyleOptions(themeResourceId = R.style.Theme_MyApplication_Panel)
                )
            }
        ),
        // Player controls close to the viewer while the film plays on the far cinema screen.
        ComposeViewPanelRegistration(
            R.id.vr_control_bar,
            composeViewCreator = { _, context -> ComposeView(context).apply { setContent { VrControlBar() } } },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = CONTROL_BAR_WIDTH_M, height = CONTROL_BAR_HEIGHT_M),
                    display = DpDisplayOptions(VR_CONTROL_BAR_WIDTH_DP, VR_CONTROL_BAR_HEIGHT_DP, dpi = 320),
                    style = PanelStyleOptions(themeResourceId = R.style.Theme_MyApplication_Panel)
                )
            }
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VrSession.init(this)
        // The right stick scrolls the hovered panel; it must not also move the user around.
        systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
        systemManager.registerSystem(ControllerKeySystem())
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        applyLighting()
        createEnvironment()
        mainPanel = Entity.createPanelEntity(
            R.id.vr_main_panel,
            Transform(BROWSE_POSE),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
        )
        controlBar = Entity.createPanelEntity(
            R.id.vr_control_bar,
            Transform(CONTROL_BAR_POSE),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y),
            Visible(false)
        )
        // Controller rays and hand pinches reach only 5 m by default; the cinema screen is 11 m away.
        systemManager.tryFindSystem<IsdkSystem>()?.setScenePointerDistance(SCENE_POINTER_DISTANCE_M)

        scope.launch {
            combine(
                VrSession.passthroughEnabled,
                VrSession.cinemaHall,
                VrSession.isPlayingVideo,
                VrSession.dimDuringPlayback
            ) { passthrough, cinemaHall, playing, dim ->
                val environment = when {
                    passthrough -> Environment.PASSTHROUGH
                    cinemaHall -> Environment.CINEMA
                    else -> Environment.NIGHT_SKY
                }
                SceneState(environment, playing, dim)
            }.collect(::applyState)
        }
        scope.launch {
            VrSession.recenterRequests.collect { recenterScreen() }
        }
    }

    override fun onSceneTick() {
        super.onSceneTick()
        val state = sceneState ?: return
        // Target lighting: house lights while browsing; in the dark cinema, the screen's colour.
        val target = FloatArray(3)
        val targetSun = FloatArray(3)
        val targetEnvironment: Float
        val houseLightsDown = state.environment == Environment.CINEMA && state.playing && state.dimDuringPlayback
        if (houseLightsDown) {
            val color = VrSession.screenColor.value ?: Color.BLACK
            val rgb = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
            for (i in 0..2) {
                target[i] = DIM_AMBIENT + rgb[i] * SCREEN_LIGHT_AMBIENT
                targetSun[i] = rgb[i] * SCREEN_LIGHT_SUN
            }
            targetEnvironment = DIM_ENVIRONMENT_INTENSITY
        } else {
            target.fill(BROWSE_AMBIENT)
            targetSun.fill(BROWSE_SUN)
            targetEnvironment = BROWSE_ENVIRONMENT_INTENSITY
        }
        for (i in 0..2) {
            ambient[i] += (target[i] - ambient[i]) * LIGHT_SMOOTHING
            sun[i] += (targetSun[i] - sun[i]) * LIGHT_SMOOTHING
        }
        environmentIntensity += (targetEnvironment - environmentIntensity) * LIGHT_SMOOTHING
        if (++frame % 2 == 0) applyLighting()
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        recenterScreen()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyLighting() {
        val onScreenSide = sceneState?.let { it.environment == Environment.CINEMA && it.playing } == true
        scene.setLightingEnvironment(
            ambientColor = Vector3(ambient[0], ambient[1], ambient[2]),
            sunColor = Vector3(sun[0], sun[1], sun[2]),
            // In the cinema the "sun" is the screen, shining from the stage towards the seats.
            sunDirection = if (onScreenSide) SCREEN_LIGHT_DIRECTION else BROWSE_SUN_DIRECTION,
            environmentIntensity = environmentIntensity
        )
    }

    private fun createEnvironment() {
        sky = skybox(R.drawable.vr_sky)
        skyDim = skybox(R.drawable.vr_sky_dim)
        floor = Entity.create(
            listOf(
                Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
                Box(Vector3(-FLOOR_HALF_SIZE_M, 0f, -FLOOR_HALF_SIZE_M), Vector3(FLOOR_HALF_SIZE_M, 0f, FLOOR_HALF_SIZE_M)),
                transparentMaterial(R.drawable.vr_floor),
                Transform(Pose(Vector3(0f, 0.002f, 0f)))
            )
        )
        hall = Entity.create(
            listOf(
                Mesh("apk:///cinema/hall.glb".toUri(), hittable = MeshCollision.NoCollision),
                Transform(Pose(Vector3(0f)))
            )
        )
        hallLights = Entity.create(
            listOf(
                Mesh("apk:///cinema/lights.glb".toUri(), hittable = MeshCollision.NoCollision),
                Transform(Pose(Vector3(0f)))
            )
        )
    }

    private fun skybox(textureRes: Int): Entity = Entity.create(
        listOf(
            Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
            Material().apply {
                baseTextureAndroidResourceId = textureRes
                unlit = true
            },
            Transform(Pose(Vector3(0f)))
        )
    )

    private fun transparentMaterial(textureRes: Int) = Material().apply {
        baseTextureAndroidResourceId = textureRes
        alphaMode = 1
        unlit = true
    }

    private fun applyState(state: SceneState) {
        sceneState = state
        val environment = state.environment
        val dimmed = state.playing && state.dimDuringPlayback
        scene.enablePassthrough(environment == Environment.PASSTHROUGH)

        val nightSky = environment == Environment.NIGHT_SKY
        sky?.setComponent(Visible(nightSky && !dimmed))
        skyDim?.setComponent(Visible(nightSky && dimmed))
        floor?.setComponent(Visible(nightSky && !dimmed))

        val cinema = environment == Environment.CINEMA
        hall?.setComponent(Visible(cinema))
        hallLights?.setComponent(Visible(cinema && !dimmed))
        val showControlBar = cinema && state.playing
        // Each time it appears, put it back within reach (it may have been grabbed elsewhere).
        if (showControlBar && !controlBarShown) controlBar?.setComponent(Transform(CONTROL_BAR_POSE))
        controlBar?.setComponent(Visible(showControlBar))
        controlBarShown = showControlBar
        VrSession.setControlBarActive(showControlBar)

        placeScreen(state)
    }

    /** In the cinema, playback moves the picture onto the big screen on the stage. */
    private fun placeScreen(state: SceneState) {
        val panel = mainPanel ?: return
        val bigScreen = state.environment == Environment.CINEMA && state.playing
        when {
            bigScreen -> {
                panel.setComponent(Transform(BIG_SCREEN_POSE))
                panel.setComponent(Scale(Vector3(BIG_SCREEN_SCALE)))
            }
            onBigScreen -> {
                // Back from the big screen: return the menu screen in front of the viewer.
                panel.setComponent(Transform(BROWSE_POSE))
                panel.setComponent(Scale(Vector3(if (state.playing) PLAYBACK_SCALE else 1f)))
            }
            else -> panel.setComponent(Scale(Vector3(if (state.playing) PLAYBACK_SCALE else 1f)))
        }
        onBigScreen = bigScreen
    }

    private fun recenterScreen() {
        if (onBigScreen) return
        mainPanel?.setComponent(Transform(BROWSE_POSE))
    }

    /**
     * Quest Touch buttons don't reach panels as key events, so map the ones the TV UI needs:
     * B = Back, Y = toggle passthrough, Menu = the cards' context-menu key.
     * A, X and the triggers stay "click" for the pointer ray.
     */
    private class ControllerKeySystem : SystemBase() {
        override fun execute() {
            val body = Query.where { has(AvatarBody.id) }.eval()
                .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                ?.getComponent<AvatarBody>() ?: return
            body.rightHand.tryGetComponent<Controller>()?.takeIf { it.isActive }?.let { right ->
                if (right.isPressed(ButtonBits.ButtonB)) VrSession.sendKeyToPanel(KeyEvent.KEYCODE_BACK)
            }
            body.leftHand.tryGetComponent<Controller>()?.takeIf { it.isActive }?.let { left ->
                if (left.isPressed(ButtonBits.ButtonY)) VrSession.togglePassthrough()
                if (left.isPressed(ButtonBits.ButtonMenu)) VrSession.sendKeyToPanel(KeyEvent.KEYCODE_MENU)
            }
        }
    }

    private companion object {
        const val SCREEN_WIDTH_M = 2.0f
        const val PLAYBACK_SCALE = 1.35f
        const val FLOOR_HALF_SIZE_M = 6f

        // In LOCAL_FLOOR space the user stands at the origin looking down +Z.
        val BROWSE_POSE = Pose(Vector3(0f, 1.35f, 2.2f), Quaternion(0f, 0f, 0f))

        // Cinema screen on the stage (see tools/cinema/generate_cinema.py): 12 x 6.75 m.
        val BIG_SCREEN_POSE = Pose(Vector3(0f, 3.2f, 11.0f), Quaternion(0f, 0f, 0f))
        const val BIG_SCREEN_SCALE = 12.0f / SCREEN_WIDTH_M
        const val SCENE_POINTER_DISTANCE_M = 20f

        // Control bar: well below eye level, within arm's reach, tilted up towards the viewer.
        const val CONTROL_BAR_WIDTH_M = 1.0f
        const val CONTROL_BAR_HEIGHT_M = CONTROL_BAR_WIDTH_M * VR_CONTROL_BAR_HEIGHT_DP / VR_CONTROL_BAR_WIDTH_DP
        val CONTROL_BAR_POSE = Pose(Vector3(0f, 0.78f, 0.7f), Quaternion(CONTROL_BAR_TILT_DEG, 0f, 0f))
        const val CONTROL_BAR_TILT_DEG = 35f

        // House lights up (browsing) vs down (playing in the cinema).
        const val BROWSE_AMBIENT = 0.55f
        const val BROWSE_SUN = 0.45f
        const val BROWSE_ENVIRONMENT_INTENSITY = 0.35f
        val BROWSE_SUN_DIRECTION = Vector3(-0.3f, -1f, 0.4f)
        // House lights down: nearly pitch black, the film only faintly lights the hall.
        const val DIM_AMBIENT = 0.004f
        const val DIM_ENVIRONMENT_INTENSITY = 0f
        const val SCREEN_LIGHT_AMBIENT = 0.04f
        const val SCREEN_LIGHT_SUN = 0.12f
        val SCREEN_LIGHT_DIRECTION = Vector3(0f, -0.25f, -1f)
        const val LIGHT_SMOOTHING = 0.08f
    }
}
