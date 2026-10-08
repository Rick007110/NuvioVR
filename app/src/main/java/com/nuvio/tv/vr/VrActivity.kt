package com.nuvio.tv.vr

import android.os.Bundle
import android.view.KeyEvent
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
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
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
 * UI in a floating, grabbable panel. Controller rays and hand-tracking pinches reach the
 * panel as pointer events (see [VrPointerBridge]); face buttons are mapped to keys here.
 */
class VrActivity : AppSystemActivity() {

    private val scope = MainScope()
    private var mainPanel: Entity? = null
    private var sceneReady = false

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ActivityPanelRegistration(
            R.id.vr_main_panel,
            classIdCreator = { MainActivity::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = PANEL_WIDTH_M, height = PANEL_WIDTH_M * 9f / 16f),
                    // The UI was designed for a 960x540dp TV canvas; 320 dpi renders it at 1920x1080.
                    display = DpDisplayOptions(960f, 540f, dpi = 320),
                    style = PanelStyleOptions(themeResourceId = R.style.Theme_MyApplication_Panel)
                )
            }
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        mainPanel = Entity.createPanelEntity(
            R.id.vr_main_panel,
            Transform(BROWSE_POSE),
            Grabbable(enabled = true, type = GrabbableType.PIVOT_Y)
        )
        sceneReady = true
        scope.launch {
            combine(VrSession.preferredEnvironment, VrSession.isPlayingVideo) { preferred, playing ->
                if (playing) VrSession.Environment.CINEMA else preferred
            }.collect(::applyEnvironment)
        }
        scope.launch {
            VrSession.isPlayingVideo.collect { playing ->
                mainPanel?.setComponent(Scale(Vector3(if (playing) CINEMA_SCALE else 1f)))
            }
        }
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        mainPanel?.setComponent(Transform(BROWSE_POSE))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyEnvironment(environment: VrSession.Environment) {
        if (!sceneReady) return
        // Cinema mode: no passthrough, so the panel floats in a dark void.
        scene.enablePassthrough(environment == VrSession.Environment.PASSTHROUGH)
    }

    /**
     * Quest Touch buttons don't reach panels as key events, so map the ones the TV UI needs:
     * B = Back, Y = toggle passthrough/cinema, Menu = the cards' context-menu key.
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
                if (left.isPressed(ButtonBits.ButtonY)) VrSession.togglePreferredEnvironment()
                if (left.isPressed(ButtonBits.ButtonMenu)) VrSession.sendKeyToPanel(KeyEvent.KEYCODE_MENU)
            }
        }
    }

    private companion object {
        const val PANEL_WIDTH_M = 2.0f
        const val CINEMA_SCALE = 1.35f

        // In LOCAL_FLOOR space the user stands at the origin looking down +Z.
        val BROWSE_POSE = Pose(Vector3(0f, 1.35f, 2.2f), Quaternion(0f, 0f, 0f))
    }
}
