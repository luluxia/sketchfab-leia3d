package com.redmagic.sketchfabsbs

interface Renderer {
    fun onSurfaceCreated()
    fun onSurfaceChanged(width: Int, height: Int)
    fun onDrawFrame()
}

