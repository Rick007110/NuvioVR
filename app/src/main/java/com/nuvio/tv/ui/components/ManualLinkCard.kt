package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

private val ManualLinkCardBackground = Color.White.copy(alpha = 0.05f)
private val ManualLinkCardBorder = Color.White.copy(alpha = 0.09f)
private val ManualLinkCardShape = RoundedCornerShape(20.dp)

/**
 * VR replacement for QR codes: a QR code shown inside the headset can't be scanned, so we show
 * the link (and an optional code) large enough to read and type on a phone or computer.
 */
@Composable
fun ManualLinkCard(
    url: String?,
    modifier: Modifier = Modifier,
    code: String? = null,
    isLoading: Boolean = false,
    stripScheme: Boolean = false,
    footer: String? = null,
    unavailableText: String? = null,
    minWidth: Dp = 360.dp,
    maxWidth: Dp = 560.dp
) {
    val displayUrl = url?.takeIf { it.isNotBlank() }?.let { if (stripScheme) displayLinkWithoutScheme(it) else it }
    val displayCode = code?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .widthIn(min = minWidth, max = maxWidth)
            .heightIn(min = 120.dp)
            .background(ManualLinkCardBackground, ManualLinkCardShape)
            .border(1.dp, ManualLinkCardBorder, ManualLinkCardShape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                displayUrl != null -> {
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
                        color = NuvioTheme.colors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    if (displayCode != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val isShortCode = displayCode.length <= 9
                        Text(
                            text = displayCode,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = if (isShortCode) 44.sp else 26.sp,
                                letterSpacing = if (isShortCode) 8.sp else 2.sp
                            ),
                            color = NuvioTheme.colors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (!footer.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = footer,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                isLoading -> {
                    val shimmerBrush = rememberShimmerBrush(backdropAware = true)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SkeletonBar(width = 238.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 6.dp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SkeletonBar(width = 140.dp, height = 24.dp, brush = shimmerBrush, cornerRadius = 8.dp)
                    }
                }
                !unavailableText.isNullOrBlank() -> {
                    Text(
                        text = unavailableText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

fun displayLinkWithoutScheme(value: String): String = value
    .removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')
