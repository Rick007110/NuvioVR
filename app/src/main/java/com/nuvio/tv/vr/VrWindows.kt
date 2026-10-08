package com.nuvio.tv.vr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.Popup as ComposePopup

/**
 * Drop-in replacement for [androidx.compose.ui.window.Dialog]. Dialogs live in their own
 * window, so they need their own [VrPointerBridge] for VR pointers to reach TV components.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    ComposeDialog(onDismissRequest = onDismissRequest, properties = properties) {
        VrPointerBridge { content() }
    }
}

/** Drop-in replacement for [androidx.compose.ui.window.Popup]; see [Dialog]. */
@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit
) {
    ComposePopup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        VrPointerBridge { content() }
    }
}
