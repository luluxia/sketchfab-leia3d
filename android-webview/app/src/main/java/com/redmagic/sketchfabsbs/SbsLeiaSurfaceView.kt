package com.redmagic.sketchfabsbs

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.opengl.Matrix
import android.view.Surface
import android.view.SurfaceHolder
import android.util.Log
import com.leia.sdk.views.InputViewsAsset
import com.leia.sdk.views.InterlacedSurfaceView
import com.leia.sdk.views.ScaleType
import com.leia.sdk.views.TileLayout

class SbsLeiaSurfaceView(context: Context) : InterlacedSurfaceView(context) {
    private val textureRenderer = LeiaTextureRenderer()
    private val asset = InputViewsAsset(object : RendererImpl(textureRenderer) {})

    val inputSurfaceTexture = SurfaceTexture(false)

    init {
        setViewAsset(asset)
        setFrameRate(TARGET_REFRESH_RATE)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                applyAndroidSurfaceFrameRate(holder.surface, TARGET_REFRESH_RATE)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                applyAndroidSurfaceFrameRate(holder.surface, TARGET_REFRESH_RATE)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        setDebugGuiCloseListener {
            Log.i("SketchfabLeia3D", "Leia SDK debug GUI closed")
        }

        val identity = FloatArray(16)
        Matrix.setIdentityM(identity, 0)
        textureRenderer.addTexture(inputSurfaceTexture, identity)

        getConfig().use { config ->
            config.setNumTiles(2, 1)
            config.setTileLayout(TileLayout.LeftToRight_Down_RowMajor)
            config.setScaleType(ScaleType.FILL)
            config.setSourceMediaIsVideo(true)
            config.setIsHorizontalViewLayout(true)
            config.setUseAtlasForViews(true)
        }
    }

    fun resizeInput(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val atlasWidth = width - (width % 2)
        inputSurfaceTexture.setDefaultBufferSize(atlasWidth, height)
        getConfig().use { config ->
            config.setNumTiles(2, 1)
            config.setTileLayout(TileLayout.LeftToRight_Down_RowMajor)
            config.setScaleType(ScaleType.FILL)
            config.setSourceMediaIsVideo(true)
            config.setIsHorizontalViewLayout(true)
            config.setUseAtlasForViews(true)
            config.setSourceSize(atlasWidth, height)
        }
    }

    fun toggleSdkDebugGui() {
        toggleGuiVisibility()
        Log.i("SketchfabLeia3D", "Leia SDK debug GUI visible=${getConfig().use { it.getIsGuiVisible() }}")
    }

    fun setSdkFrameRate(fps: Float) {
        setFrameRate(fps)
        applyAndroidSurfaceFrameRate(holder.surface, fps)
        Log.i("SketchfabLeia3D", "InterlacedSurfaceView.setFrameRate($fps)")
    }

    private fun applyAndroidSurfaceFrameRate(surface: Surface?, fps: Float) {
        if (surface == null || !surface.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            Log.i("SketchfabLeia3D", "Android Surface.setFrameRate($fps) applied")
        } catch (error: Throwable) {
            Log.w("SketchfabLeia3D", "Android Surface.setFrameRate($fps) failed", error)
        }
    }

    private companion object {
        const val TARGET_REFRESH_RATE = 144f
    }
}

