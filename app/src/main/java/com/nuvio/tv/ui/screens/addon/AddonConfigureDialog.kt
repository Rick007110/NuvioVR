package com.nuvio.tv.ui.screens.addon

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.vr.Dialog

/**
 * In-app browser for an addon's own configuration page (e.g. Torrentio, Comet). Stremio
 * addons are configured on their website, which then offers an "Install" link
 * (`stremio://…/manifest.json`) that carries the chosen settings. That link is caught here
 * and handed to [onManifestUrl] instead of leaving the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AddonConfigureDialog(
    title: String,
    startUrl: String,
    onManifestUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf(title) }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    val noLinkMessage = stringResource(R.string.addon_configure_no_link)

    val goBack = {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onDismiss()
    }

    Dialog(
        onDismissRequest = goBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NuvioTheme.colors.BackgroundElevated)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ToolbarButton(
                    icon = if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_back),
                    onClick = goBack
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = clipboardMessage ?: stringResource(R.string.addon_configure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ToolbarButton(
                    icon = Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.addon_configure_paste),
                    label = stringResource(R.string.addon_configure_paste),
                    onClick = {
                        val copied = readClipboardText(context)
                        val manifestUrl = copied?.let(::extractManifestUrl)
                        if (manifestUrl != null) {
                            onManifestUrl(manifestUrl)
                        } else {
                            clipboardMessage = noLinkMessage
                        }
                    }
                )
                ToolbarButton(
                    icon = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = onDismiss
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val url = request.url.toString()
                                    val manifestUrl = extractManifestUrl(url)
                                    if (manifestUrl != null) {
                                        onManifestUrl(manifestUrl)
                                        return true
                                    }
                                    // Other app links (e.g. intent://) can't open inside the panel.
                                    val scheme = request.url.scheme?.lowercase()
                                    return scheme != "http" && scheme != "https"
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack()
                                    view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                }
                            }
                            loadUrl(startUrl)
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() }
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null
) {
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(if (label == null) CircleShape else RoundedCornerShape(24.dp))
            .background(NuvioTheme.colors.BackgroundCard)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(horizontal = if (label == null) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.size(24.dp)
        )
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary)
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
}

/**
 * Returns an installable addon URL when [raw] points at an addon manifest: a `stremio://`
 * link or an http(s) URL ending in `/manifest.json`.
 */
internal fun extractManifestUrl(raw: String): String? {
    val url = raw.trim()
    val lower = url.lowercase()
    val isStremio = lower.startsWith("stremio://")
    val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
    if (!isStremio && !isHttp) return null
    val path = url.substringBefore('#').substringBefore('?')
    return if (isStremio || path.endsWith("/manifest.json")) url else null
}

/** The addon's configuration page: `<base>/configure`, keeping the current settings path. */
internal fun addonConfigureUrl(baseUrl: String): String {
    val base = baseUrl.trim().removeSuffix("/manifest.json").trimEnd('/')
    return "$base/configure"
}
