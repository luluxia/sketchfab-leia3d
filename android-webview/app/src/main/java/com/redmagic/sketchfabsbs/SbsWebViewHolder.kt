package com.redmagic.sketchfabsbs

import android.content.Context
import android.graphics.Color
import android.view.Surface
import android.widget.FrameLayout

class SbsWebViewHolder(context: Context) : FrameLayout(context) {
    val interlacedView = SbsLeiaSurfaceView(context)
    val webView = SurfaceAwareWebView(context, null)

    private val inputSurface = Surface(interlacedView.inputSurfaceTexture)
    private var interlacedMode = false

    init {
        setBackgroundColor(Color.BLACK)

        webView.setBackgroundColor(Color.BLACK)

        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            interlacedView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            resize()
        }

        setInterlacedMode(false)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resize()
    }

    fun onResume() {
        interlacedView.onResume()
        webView.onResume()
    }

    fun onPause() {
        webView.onPause()
        interlacedView.onPause()
    }

    fun setInterlacedMode(enabled: Boolean) {
        if (interlacedMode == enabled) return
        interlacedMode = enabled
        if (enabled) {
            routeWebViewToCnsdk()
        } else {
            webView.surface = null
            interlacedView.visibility = GONE
            webView.bringToFront()
            webView.invalidate()
        }
    }

    fun toggleSdkDebugGui() {
        interlacedView.toggleSdkDebugGui()
    }

    fun setSdkFrameRate(fps: Float) {
        interlacedView.setSdkFrameRate(fps)
    }

    private fun routeWebViewToCnsdk() {
        webView.surface = inputSurface
        interlacedView.visibility = VISIBLE
        interlacedView.bringToFront()
        webView.invalidate()
    }

    private fun resize() {
        interlacedView.resizeInput(width, height)
    }

    fun destroy() {
        webView.surface = null
        webView.destroy()
        inputSurface.release()
        interlacedView.inputSurfaceTexture.release()
    }
}

