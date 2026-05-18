package com.redmagic.sketchfabsbs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.Log
import android.util.AttributeSet
import android.view.Surface
import android.webkit.WebView

class SurfaceAwareWebView(context: Context, attrs: AttributeSet?) : WebView(context, attrs) {
    var surface: Surface? = null
    private var drawCount = 0
    private var drawWindowStartMs = 0L
    private var drawMaxMs = 0L
    private var drawSlowCount = 0

    @SuppressLint("CanvasSize")
    override fun draw(canvas: Canvas) {
        val surface = surface
        if (surface != null) {
            val startMs = SystemClock.elapsedRealtime()
            val surfaceCanvas = surface.lockHardwareCanvas()
            val scale = surfaceCanvas.width.toFloat() / canvas.width.toFloat()
            surfaceCanvas.scale(scale, scale)
            surfaceCanvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            super.draw(surfaceCanvas)
            surface.unlockCanvasAndPost(surfaceCanvas)
            recordSurfaceDraw(SystemClock.elapsedRealtime() - startMs)
        } else {
            super.draw(canvas)
        }
    }

    private fun recordSurfaceDraw(durationMs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        if (drawWindowStartMs == 0L) {
            drawWindowStartMs = nowMs
        }
        drawCount += 1
        drawMaxMs = maxOf(drawMaxMs, durationMs)
        if (durationMs > 12) {
            drawSlowCount += 1
        }

        val elapsedMs = nowMs - drawWindowStartMs
        if (elapsedMs >= 1000) {
            val fps = drawCount * 1000f / elapsedMs
            Log.i(
                "SketchfabLeia3D",
                "WebViewSurfaceDraw fps=${"%.1f".format(fps)} max=${drawMaxMs}ms slowOver12=${drawSlowCount}"
            )
            drawWindowStartMs = nowMs
            drawCount = 0
            drawMaxMs = 0
            drawSlowCount = 0
        }
    }
}

