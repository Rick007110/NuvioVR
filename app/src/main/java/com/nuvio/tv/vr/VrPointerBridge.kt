package com.nuvio.tv.vr

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp

/**
 * Makes the D-pad-only TV UI usable with VR pointers.
 *
 * Most of the UI is built from androidx.tv.material3 components, which only react to
 * D-pad/Enter keys and ignore touch. On Quest, controller rays and hand-tracking pinches
 * arrive as pointer events (hover + touch), so this bridge sits at the root of a window:
 *
 * - hover: hit-tests the semantics tree and draws a highlight around the clickable node
 *   under the pointer (TV focus visuals are key-driven and never react to hover);
 * - tap: consumes the release and invokes the node's semantic click action, which works
 *   for tv-material and regular foundation/material3 components alike;
 * - press and hold: invokes the long-click action, or falls back to the MENU key that the
 *   TV cards use to open their context menu;
 * - drags pass through untouched, so lists keep scrolling normally.
 *
 * Taps that hit nothing clickable pass through, so screens can still add their own
 * pointer handling (e.g. tapping the video to show player controls).
 */
@Composable
fun VrPointerBridge(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val view = LocalView.current
    val state = remember(view) { VrPointerBridgeState(view) }
    val highlightColor = Color.White
    Box(
        modifier = modifier
            .onGloballyPositioned { state.coordinates = it }
            .pointerInput(state) { with(state) { handlePointerInput() } }
            .drawWithContent {
                drawContent()
                val rect = state.highlightRect ?: return@drawWithContent
                val coords = state.coordinates ?: return@drawWithContent
                if (!coords.isAttached) return@drawWithContent
                val topLeft = coords.windowToLocal(rect.topLeft)
                val pad = 3.dp.toPx()
                val radius = CornerRadius(14.dp.toPx())
                val origin = Offset(topLeft.x - pad, topLeft.y - pad)
                val size = Size(rect.width + pad * 2, rect.height + pad * 2)
                drawRoundRect(
                    color = highlightColor.copy(alpha = if (state.pressed) 0.22f else 0.08f),
                    topLeft = origin,
                    size = size,
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = highlightColor.copy(alpha = 0.9f),
                    topLeft = origin,
                    size = size,
                    cornerRadius = radius,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
    ) {
        content()
    }
}

private class VrPointerBridgeState(private val view: View) {
    var coordinates: LayoutCoordinates? = null
    var highlightRect by mutableStateOf<Rect?>(null)
    var pressed by mutableStateOf(false)

    suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handlePointerInput() {
        val touchSlop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        if (!change.pressed) highlightRect = findTarget(change.position)?.boundsInWindow
                    }

                    PointerEventType.Exit -> highlightRect = null

                    PointerEventType.Press -> {
                        val pointerId = change.id
                        val start = change.position
                        val pressedAt = SystemClock.uptimeMillis()
                        val target = findTarget(start)
                        highlightRect = target?.boundsInWindow
                        pressed = target != null
                        var moved = false
                        var longPressed = false
                        while (true) {
                            val remaining = longPressTimeout - (SystemClock.uptimeMillis() - pressedAt)
                            val next = if (!moved && !longPressed && target != null && remaining > 0) {
                                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                            } else {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (next == null) {
                                // Held in place long enough: treat as a long press.
                                longPressed = true
                                target?.let(::performLongClick)
                                continue
                            }
                            val current = next.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!moved && (current.position - start).getDistance() > touchSlop) {
                                moved = true
                                pressed = false
                                highlightRect = null
                            }
                            if (!current.pressed) {
                                if (target != null && !moved) {
                                    // Keep children from also handling this release.
                                    current.consume()
                                    if (!longPressed) performClick(target)
                                }
                                break
                            }
                        }
                        pressed = false
                        // The click usually changes the screen; the next hover move re-targets.
                        highlightRect = null
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun localToWindow(position: Offset): Offset? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        return coords.localToWindow(position)
    }

    private fun findTarget(localPosition: Offset): SemanticsNode? {
        val windowPosition = localToWindow(localPosition) ?: return null
        val root = (view as? ViewRootForTest)?.semanticsOwner?.rootSemanticsNode ?: return null
        return findClickable(root, windowPosition)
    }

    /** Depth-first, top-most (last drawn) child first: returns the deepest clickable hit. */
    private fun findClickable(node: SemanticsNode, position: Offset): SemanticsNode? {
        val bounds = node.boundsInWindow
        if (bounds.isEmpty || !bounds.contains(position)) return null
        val children = node.children
        for (index in children.indices.reversed()) {
            findClickable(children[index], position)?.let { return it }
        }
        val config = node.config
        if (config.getOrNull(SemanticsProperties.Disabled) != null) return null
        return if (config.getOrNull(SemanticsActions.OnClick) != null) node else null
    }

    private fun requestFocus(node: SemanticsNode) {
        if (node.config.getOrNull(SemanticsProperties.Focused) == true) return
        node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
    }

    private fun performClick(node: SemanticsNode) {
        requestFocus(node)
        node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun performLongClick(node: SemanticsNode) {
        val longClick = node.config.getOrNull(SemanticsActions.OnLongClick)?.action
        if (longClick != null) {
            longClick.invoke()
            return
        }
        // TV cards open their context menu from the MENU key on the focused item.
        requestFocus(node)
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0))
    }
}
