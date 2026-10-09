package com.nuvio.tv.vr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.vr.VrSession.PlayerCommand
import kotlinx.coroutines.delay

/** Width x height of the control bar canvas, in dp; see [VrActivity] for its size in metres. */
const val VR_CONTROL_BAR_WIDTH_DP = 1000f
const val VR_CONTROL_BAR_HEIGHT_DP = 170f

private val BarBackground = Color(0xE6101014)
private val Accent = Color(0xFF8B5CF6)

/**
 * Compact player controls shown close to the viewer while a film plays on the cinema screen,
 * so pausing, seeking and going back never need a long reach to the big screen.
 *
 * With auto-hide on (the arrow button) the bar collapses to a thin handle so it doesn't distract
 * from the film, and opens again when the viewer points at it.
 */
@Composable
fun VrControlBar() {
    val state by VrSession.playerState.collectAsState()
    val autoHide by VrSession.controlBarAutoHide.collectAsState()
    var pointedAt by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(autoHide, pointedAt) {
        when {
            !autoHide -> expanded = true
            pointedAt -> {
                delay(OPEN_DELAY_MS) // only when really aiming at it, not when sweeping past
                expanded = true
            }
            else -> {
                delay(CLOSE_DELAY_MS)
                expanded = false
            }
        }
    }
    val player = state ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> pointedAt = true
                            PointerEventType.Exit -> pointedAt = false
                            else -> Unit
                        }
                    }
                }
            }
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 }
        ) {
            BarContent(player = player, autoHide = autoHide)
        }
        if (!expanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 220.dp, height = 10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }
    }
}

private const val OPEN_DELAY_MS = 250L
private const val CLOSE_DELAY_MS = 2500L

@Composable
private fun BarContent(player: VrSession.PlayerState, autoHide: Boolean) {
    val passthrough by VrSession.passthroughEnabled.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(BarBackground)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = player.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${formatTime(player.positionMs)} / ${formatTime(player.durationMs)}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 16.sp
            )
        }
        SeekBar(positionMs = player.positionMs, durationMs = player.durationMs)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BarButton(Icons.AutoMirrored.Filled.ArrowBack) { VrSession.sendPlayerCommand(PlayerCommand.Back) }
            Box(modifier = Modifier.weight(1f))
            BarButton(Icons.Default.Replay10) { VrSession.sendPlayerCommand(PlayerCommand.SeekBackward) }
            BarButton(
                icon = if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                primary = true
            ) { VrSession.sendPlayerCommand(PlayerCommand.PlayPause) }
            BarButton(Icons.Default.Forward10) { VrSession.sendPlayerCommand(PlayerCommand.SeekForward) }
            Box(modifier = Modifier.weight(1f))
            BarButton(Icons.Default.ClosedCaption) { VrSession.sendPlayerCommand(PlayerCommand.Subtitles) }
            BarButton(Icons.Default.Audiotrack) { VrSession.sendPlayerCommand(PlayerCommand.Audio) }
            BarButton(if (passthrough) Icons.Default.VisibilityOff else Icons.Default.Visibility) {
                VrSession.togglePassthrough()
            }
            BarButton(Icons.Default.Tune) { VrSession.sendPlayerCommand(PlayerCommand.ToggleScreenControls) }
            BarButton(if (autoHide) Icons.Default.PushPin else Icons.Default.KeyboardArrowDown) {
                VrSession.setControlBarAutoHide(!autoHide)
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val size = if (primary) 64.dp else 52.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    primary -> if (hovered) Accent.copy(alpha = 0.85f) else Accent
                    hovered -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        val target = (offset.x / size.width).coerceIn(0f, 1f) * durationMs
                        VrSession.sendPlayerCommand(PlayerCommand.SeekTo(target.toLong()))
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { f ->
                            if (durationMs > 0) VrSession.sendPlayerCommand(PlayerCommand.SeekTo((f * durationMs).toLong()))
                        }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )
        Box(
            modifier = Modifier
                .width(maxWidth * fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Accent)
        )
        Box(
            modifier = Modifier
                .padding(start = (maxWidth * fraction - 9.dp).coerceAtLeast(0.dp))
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
