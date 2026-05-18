package com.redmagic.sketchfabsbs

import android.os.IBinder
import android.util.Log

object NativeFpsControl {
    private const val TAG = "SketchfabFpsControl"

    private val loaded = runCatching {
        System.loadLibrary("fpscontrol_jni")
    }.onFailure {
        Log.w(TAG, "fpscontrol_jni load failed", it)
    }.isSuccess

    fun fpsControlBinder(binder: IBinder, name: String, code: Int, value: Int): String {
        check(loaded) { "fpscontrol_jni unavailable" }
        return fpsControlBinderNative(binder, name, code, value)
    }

    private external fun fpsControlBinderNative(
        binder: IBinder,
        name: String,
        code: Int,
        value: Int
    ): String
}

