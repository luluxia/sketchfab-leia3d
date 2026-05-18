package com.redmagic.sketchfabsbs

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

object VendorFrameTuning {
    private const val TAG = "ThreeVendorTuning"
    private const val LOCK_TYPE_UNITY_3D = 7
    private const val LOCK_DURATION_MS = 10 * 60 * 1000L
    private const val MINDSYNC_SERVICE = "mindsyncservice"
    private const val MINDSYNC_DESCRIPTOR = "com.zte.performance.mindsync.IMindSyncManager"
    private const val TRANSACTION_ACQUIRE_PERFORMANCE_LOCK = 27
    private const val TRANSACTION_RELEASE_PERFORMANCE_LOCK = 28
    private const val TRANSACTION_SET_FPS = 63
    private const val TRANSACTION_SET_APP_FPS = 64
    private const val REFRESH_RATE_SERVICE = "ZteScreenRefreshRate"
    private const val REFRESH_RATE_DESCRIPTOR = "com.zte.performance.refreshrate.IScreenRefreshRate"
    private const val TRANSACTION_SET_FRAME_RATE_BY_APP = 1
    private const val TRANSACTION_SET_FRAME_RATE_FOR_SCENE_CHANGE = 2
    private const val TRANSACTION_SET_REFRESH_RATE_BY_GAME_ASSIST = 6
    private const val SURFACE_FLINGER_SERVICE = "SurfaceFlinger"

    private var perfToken: Binder? = null

    fun acquirePerformanceLock(context: Context): String {
        return runCatching {
            val token = perfToken ?: Binder().also { perfToken = it }
            transactMindSync(TRANSACTION_ACQUIRE_PERFORMANCE_LOCK) { data ->
                data.writeStrongBinder(token)
                data.writeString(context.packageName)
                data.writeInt(LOCK_TYPE_UNITY_3D)
                data.writeLong(LOCK_DURATION_MS)
            }
            "MindSync binder lock type=$LOCK_TYPE_UNITY_3D ${LOCK_DURATION_MS}ms"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            perfToken = null
            Log.w(TAG, "MindSync binder lock failed", it)
        }.getOrElse {
            "MindSync binder lock failed: ${it.javaClass.simpleName}"
        }
    }

    fun releasePerformanceLock(): String {
        val token = perfToken ?: return "MindSync lock not held"
        return runCatching {
            transactMindSync(TRANSACTION_RELEASE_PERFORMANCE_LOCK) { data ->
                data.writeStrongBinder(token)
            }
            perfToken = null
            "MindSync binder lock released"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "MindSync binder release failed", it)
        }.getOrElse {
            "MindSync binder release failed: ${it.javaClass.simpleName}"
        }
    }

    fun setFrameRateByApp(frameRate: Float, compatibility: Int = 0, strategy: Int = 0): String {
        return runCatching {
            transactRefreshRate(TRANSACTION_SET_FRAME_RATE_BY_APP) { data ->
                data.writeFloat(frameRate)
                data.writeInt(compatibility)
                data.writeInt(strategy)
            }
            "ZteScreenRefreshRate setFrameRateByApp fps=$frameRate compat=$compatibility strategy=$strategy"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "ZteScreenRefreshRate setFrameRateByApp failed", it)
        }.getOrElse {
            "ZteScreenRefreshRate setFrameRateByApp failed: ${it.javaClass.simpleName}"
        }
    }

    fun setFrameRateForSceneChange(sceneId: Int): String {
        return runCatching {
            transactRefreshRate(TRANSACTION_SET_FRAME_RATE_FOR_SCENE_CHANGE) { data ->
                data.writeInt(sceneId)
            }
            "ZteScreenRefreshRate setFrameRateForScene scene=$sceneId"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "ZteScreenRefreshRate setFrameRateForScene failed", it)
        }.getOrElse {
            "ZteScreenRefreshRate setFrameRateForScene failed: ${it.javaClass.simpleName}"
        }
    }

    fun setRefreshRateByGameAssist(packageName: String, fps: Int, who: String = "GameAssist"): String {
        return runCatching {
            transactRefreshRate(TRANSACTION_SET_REFRESH_RATE_BY_GAME_ASSIST) { data ->
                data.writeString(who)
                data.writeString(packageName)
                data.writeInt(fps)
            }
            "ZteScreenRefreshRate GameAssist pkg=$packageName fps=$fps"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "ZteScreenRefreshRate GameAssist failed", it)
        }.getOrElse {
            "ZteScreenRefreshRate GameAssist failed: ${it.javaClass.simpleName}"
        }
    }

    fun setMindSyncFps(packageName: String, fps: Int, who: String = "tgpa", args: String = ""): String {
        return transactMindSyncSetFps(who, packageName, fps, args)
    }

    fun setMindSyncAppFps(packageName: String, fps: Int): String {
        return transactMindSyncSetAppFps(packageName, fps)
    }

    fun surfaceFpsControl(packageName: String, policy: Int, desiredFps: Int): String {
        return runCatching {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val method = surfaceControl.getDeclaredMethod(
                "fpsControl",
                String::class.java,
                Integer.TYPE,
                Integer.TYPE
            )
            method.isAccessible = true
            method.invoke(null, packageName, policy, desiredFps)
            "SurfaceControl.fpsControl policy=$policy fps=$desiredFps"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "SurfaceControl.fpsControl failed", it)
        }.getOrElse {
            "SurfaceControl.fpsControl failed: ${it.javaClass.simpleName}"
        }
    }

    fun requestSurfaceFpsUnlock(packageName: String, desiredFps: Int): String {
        val appResult = surfaceFlingerFpsControl(packageName, 8000, desiredFps)
        val commitResult = surfaceFlingerFpsControl("", 10000, 0)
        return "SurfaceFlinger fpsControl unlock: $appResult; $commitResult"
    }

    fun surfaceFlingerFpsControl(packageName: String, policy: Int, desiredFps: Int): String {
        return runCatching {
            val binder = getService(SURFACE_FLINGER_SERVICE) ?: error("$SURFACE_FLINGER_SERVICE unavailable")
            NativeFpsControl.fpsControlBinder(binder, packageName, policy, desiredFps).also {
                check(it.contains("called")) { it }
            }
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "SurfaceFlinger.fpsControl failed", it)
        }.getOrElse {
            "SurfaceFlinger.fpsControl failed: ${it.javaClass.simpleName}"
        }
    }

    private fun transactMindSyncSetFps(who: String, packageName: String, fps: Int, args: String): String {
        return runCatching {
            transactMindSync(TRANSACTION_SET_FPS) { data ->
                data.writeString(who)
                data.writeString(packageName)
                data.writeInt(fps)
                data.writeString(args)
            }
            "MindSync binder setFps who=$who pkg=$packageName fps=$fps"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "MindSync binder setFps failed", it)
        }.getOrElse {
            "MindSync binder setFps failed: ${it.javaClass.simpleName}"
        }
    }

    private fun transactMindSyncSetAppFps(packageName: String, fps: Int): String {
        return runCatching {
            transactMindSync(TRANSACTION_SET_APP_FPS) { data ->
                data.writeString(packageName)
                data.writeInt(fps)
            }
            "MindSync binder setAppFps pkg=$packageName fps=$fps"
        }.onSuccess {
            Log.i(TAG, it)
        }.onFailure {
            Log.w(TAG, "MindSync binder setAppFps failed", it)
        }.getOrElse {
            "MindSync binder setAppFps failed: ${it.javaClass.simpleName}"
        }
    }

    private fun transactMindSync(code: Int, writeArgs: (Parcel) -> Unit) {
        val binder = getService(MINDSYNC_SERVICE) ?: error("$MINDSYNC_SERVICE unavailable")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MINDSYNC_DESCRIPTOR)
            writeArgs(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactRefreshRate(code: Int, writeArgs: (Parcel) -> Unit) {
        val binder = getService(REFRESH_RATE_SERVICE) ?: error("$REFRESH_RATE_SERVICE unavailable")
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(REFRESH_RATE_DESCRIPTOR)
            writeArgs(data)
            if (!binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                error("$REFRESH_RATE_SERVICE transaction $code failed")
            }
        } finally {
            data.recycle()
        }
    }

    private fun getService(name: String): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val method = serviceManager.getDeclaredMethod("getService", String::class.java)
        method.isAccessible = true
        return method.invoke(null, name) as? IBinder
    }
}

