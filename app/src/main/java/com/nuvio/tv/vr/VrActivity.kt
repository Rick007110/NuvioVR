package com.nuvio.tv.vr

import android.os.Bundle
import android.view.KeyEvent
import androidx.core.net.toUri
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.CylinderShapeOptions
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
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
 * UI on a floating, curved, grabbable screen. Controller rays and hand-tracking pinches
 * reach the panel as pointer events (see [VrPointerBridge]); face buttons are mapped here.
 *
 * By default the screen floats in a virtual night-sky environment; passthrough (the real
 * room) is opt-in from Settings > VR, the player's passthrough button or the Y button.
 */
class VrActivity : AppSystemActivity() {

    private val scope = MainScope()
    private var mainPanel: Entity? = null
    private var sky: Entity? = null
    private var skyDim: Entity? = null
    private var floor: Entity? = null
    private var screenGlow: Entity? = null

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ActivityPanelRegistration(
            R.id.vr_main_panel,
            classIdCreator = { MainActivity::class.java },
            settingsCreator = {
                UIPanelSettings(
                    // A gentle curve, like a cinema screen wrapping around the viewer.
                    shape = CylinderShapeOptions(
                        radius = SCREEN_CURVE_RADIUS_M,
                        width = SCREEN_WIDTH_M,
                        height = SCREEN_WIDTH_M * 9f / 16f
                    ),
                    // The UI was designed for a 960x540dp TV canvas; 320 dpi renders it at 1920x1080.
                    display = DpDisplayOptions(960f, 540f, dpi = 320),
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
        scene.setLightingEnvironment(
            ambientColor = Vector3(0.6f),
            sunColor = Vector3(0.6f),
            sunDirection = -Vector3(1f, 3f, -2f),
            environmentIntensity = 0.3f
        )
        createEnvironment()
        val panel = Entity.createPanelEntity(
            R.id.vr_main_panel,
            Transform(BROWSE_POSE),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
        )
        mainPanel = panel
        screenGlow = Entity.create(
            listOf(
                Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
                Box(
                    Vector3(-GLOW_WIDTH_M / 2, -GLOW_HEIGHT_M / 2, 0f),
                    Vector3(GLOW_WIDTH_M / 2, GLOW_HEIGHT_M / 2, 0f)
                ),
                transparentMaterial(R.drawable.vr_screen_glow),
                // Just behind the screen (away from the viewer), and moves with it when grabbed.
                Transform(Pose(Vector3(0f, 0f, GLOW_DEPTH_OFFSET_M))),
                TransformParent(panel)
            )
        )

        scope.launch {
            combine(
                VrSession.passthroughEnabled,
                VrSession.isPlayingVideo,
                VrSession.dimDuringPlayback
            ) { passthrough, playing, dim -> Triple(passthrough, playing, dim) }
                .collect { (passthrough, playing, dim) -> applyEnvironment(passthrough, playing, dim) }
        }
        scope.launch {
            VrSession.isPlayingVideo.collect { playing ->
                mainPanel?.setComponent(Scale(Vector3(if (playing) CINEMA_SCALE else 1f)))
            }
        }
        scope.launch {
            VrSession.recenterRequests.collect { recenterScreen() }
        }
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        recenterScreen()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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

    private fun applyEnvironment(passthrough: Boolean, playing: Boolean, dimDuringPlayback: Boolean) {
        val virtual = !passthrough
        val dimmed = playing && dimDuringPlayback
        scene.enablePassthrough(passthrough)
        sky?.setComponent(Visible(virtual && !dimmed))
        skyDim?.setComponent(Visible(virtual && dimmed))
        floor?.setComponent(Visible(virtual && !dimmed))
        // The glow would bleed into the picture during playback.
        screenGlow?.setComponent(Visible(virtual && !playing))
    }

    private fun recenterScreen() {
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
        const val SCREEN_CURVE_RADIUS_M = 3.5f
        const val CINEMA_SCALE = 1.35f
        const val GLOW_WIDTH_M = 3.0f
        const val GLOW_HEIGHT_M = 1.9f
        const val GLOW_DEPTH_OFFSET_M = 0.15f
        const val FLOOR_HALF_SIZE_M = 6f

        // In LOCAL_FLOOR space the user stands at the origin looking down +Z.
        val BROWSE_POSE = Pose(Vector3(0f, 1.35f, 2.2f), Quaternion(0f, 0f, 0f))
    }
}
