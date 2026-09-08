package io.laelaps.zuitweaks.xposed

import android.util.Log

/**
 * Logs to both logcat and the LSPosed module log.
 *
 * logcat is what the build/verify loop greps (`adb logcat | grep ZuiTweaks`); the
 * XposedBridge copy survives when logcat is not attached. With the *Debug log* setting on,
 * [DebugLog] adds a third copy on /sdcard - see there for why a ring buffer is not enough.
 */
object Logx {

    fun d(msg: String) {
        Log.d(Flags.TAG, msg)
        DebugLog.write('D', msg)
    }

    fun i(msg: String) {
        Log.i(Flags.TAG, msg)
        XposedBridge.log("[${Flags.TAG}] $msg")
        DebugLog.write('I', msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(Flags.TAG, msg, t) else Log.e(Flags.TAG, msg)
        XposedBridge.log("[${Flags.TAG}] ERROR $msg")
        if (t != null) XposedBridge.log(t)
        if (t != null) DebugLog.write('E', msg, t) else DebugLog.write('E', msg)
    }

    /**
     * Every hook callback body goes through this. An uncaught throw inside a hook
     * takes the launcher down with it, so nothing in this module ever rethrows.
     */
    inline fun guard(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            e("hook failed: $what", t)
        }
    }
}
