package com.redmagic.sketchfabsbs

import android.content.Context
import android.graphics.Color
import android.view.Surface
import android.widget.FrameLayout

class SbsWebViewHolder(context: Context) : FrameLayout(context) {
    val interlacedView = SbsLeiaSurfaceView(context)
    val browserWebView = SurfaceAwareWebView(context, null)
    val viewerWebView = SurfaceAwareWebView(context, null)
    val webView: SurfaceAwareWebView
        get() = browserWebView

    private val inputSurface = Surface(interlacedView.inputSurfaceTexture)
    private var interlacedMode = true

    init {
        setBackgroundColor(Color.BLACK)

        browserWebView.setBackgroundColor(Color.BLACK)
        viewerWebView.setBackgroundColor(Color.BLACK)

        addView(
            browserWebView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            viewerWebView,
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
        browserWebView.onResume()
        viewerWebView.onResume()
    }

    fun onPause() {
        browserWebView.onPause()
        viewerWebView.onPause()
        interlacedView.onPause()
    }

    fun setInterlacedMode(enabled: Boolean) {
        if (interlacedMode == enabled) return
        interlacedMode = enabled
        if (enabled) {
            showViewerInterlaced()
        } else {
            showBrowser()
        }
    }

    fun showBrowser() {
        interlacedMode = false
        viewerWebView.surface = null
        interlacedView.visibility = GONE
        viewerWebView.visibility = GONE
        browserWebView.visibility = VISIBLE
        browserWebView.bringToFront()
        browserWebView.invalidate()
    }

    fun showViewer2D() {
        interlacedMode = false
        viewerWebView.surface = null
        interlacedView.visibility = GONE
        browserWebView.visibility = GONE
        viewerWebView.visibility = VISIBLE
        viewerWebView.bringToFront()
        viewerWebView.invalidate()
    }

    fun showViewerInterlaced() {
        interlacedMode = true
        routeWebViewToCnsdk()
    }

    fun toggleSdkDebugGui() {
        interlacedView.toggleSdkDebugGui()
    }

    fun setSdkFrameRate(fps: Float) {
        interlacedView.setSdkFrameRate(fps)
    }

    private fun routeWebViewToCnsdk() {
        browserWebView.visibility = GONE
        viewerWebView.visibility = VISIBLE
        viewerWebView.surface = inputSurface
        interlacedView.visibility = VISIBLE
        interlacedView.bringToFront()
        viewerWebView.invalidate()
    }

    private fun resize() {
        interlacedView.resizeInput(width, height)
    }

    fun destroy() {
        browserWebView.surface = null
        viewerWebView.surface = null
        browserWebView.destroy()
        viewerWebView.destroy()
        inputSurface.release()
        interlacedView.inputSurfaceTexture.release()
    }
}

