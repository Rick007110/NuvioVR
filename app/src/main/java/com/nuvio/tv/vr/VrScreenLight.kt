package com.nuvio.tv.vr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Samples the average colour of the playing video a few times per second and publishes it
 * as [VrSession.screenColor], so the cinema hall can be lit by the screen.
 *
 * Works for both players (ExoPlayer and mpv render into a SurfaceView): it looks up the
 * largest visible SurfaceView in the activity and copies it into a tiny 16x9 bitmap.
 */
object VrScreenLight {

    private const val SAMPLE_INTERVAL_MS = 120L

    fun start(activity: Activity, scope: CoroutineScope): Job = scope.launch {
        val handler = Handler(Looper.getMainLooper())
        VrSession.isPlayingVideo.collectLatest { playing ->
            if (!playing) {
                VrSession.setScreenColor(null)
                return@collectLatest
            }
            val bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            try {
                while (isActive) {
                    val surfaceView = findVideoSurface(activity.window?.decorView)
                    if (surfaceView != null && surfaceView.holder.surface.isValid) {
                        copy(surfaceView, bitmap, handler)?.let(VrSession::setScreenColor)
                    }
                    delay(SAMPLE_INTERVAL_MS)
                }
            } finally {
                bitmap.recycle()
                VrSession.setScreenColor(null)
            }
        }
    }

    private suspend fun copy(view: SurfaceView, bitmap: Bitmap, handler: Handler): Int? =
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(view, bitmap, { result ->
                    if (continuation.isActive) {
                        continuation.resume(if (result == PixelCopy.SUCCESS) averageColor(bitmap) else null)
                    }
                }, handler)
            } catch (_: IllegalArgumentException) {
                // Surface went away between the check and the copy.
                continuation.resume(null)
            }
        }

    private fun averageColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var r = 0L
        var g = 0L
        var b = 0L
        for (pixel in pixels) {
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
        }
        val n = pixels.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun findVideoSurface(root: View?): SurfaceView? {
        var best: SurfaceView? = null
        var bestArea = 0
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is SurfaceView) {
                val area = view.width * view.height
                if (area > bestArea) {
                    best = view
                    bestArea = area
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        root?.let(::visit)
        return best
    }
}
