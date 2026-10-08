package com.nuvio.tv.vr

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R

/**
 * Floating "back" button for hand tracking: without controllers there is no B button, so
 * every non-root screen gets a pinchable back button in the top-left corner. It sends the
 * same Back key as the B button, so each screen keeps its own back behaviour.
 *
 * It is pointer-only (not focusable), so D-pad / focus navigation is unaffected.
 */
@Composable
fun VrBackButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { VrSession.sendKeyToPanel(KeyEvent.KEYCODE_BACK) }
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val highlighted = hovered || pressed
    Box(
        modifier = modifier
            .padding(16.dp)
            .size(56.dp)
            .background(
                color = if (highlighted) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.55f),
                shape = CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = if (highlighted) 0.8f else 0.3f), CircleShape)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}
