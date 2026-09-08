package io.laelaps.zuitweaks.xposed

import java.lang.reflect.Field

/**
 * Opens the two guards that keep Launcher3's stash machinery shut on this device.
 *
 * TaskbarStashController gates every stash path on:
 *   supportsVisualStashing()  - false while the taskbar reports three-button nav
 *   isInApp()                 - false, because ZUI never sets FLAG_IN_APP for the
 *                               external-display taskbar (there is no launcher home on it)
 *
 * Both survived R8 with their names, so they can be forced directly instead of poking
 * at the obfuscated updateStateForFlag/applyState pair.
 *
 * Each controller instance is matched to its taskbar through its TaskbarActivityContext
 * field, so only the external-display controller is affected.
 */
object StashHook {

    private const val STASH_CTRL = "com.android.launcher3.taskbar.TaskbarStashController"
    private const val TASKBAR_CTX = "com.android.launcher3.taskbar.TaskbarActivityContext"

    private var dpContextClass: Class<*>? = null
    private var taskbarContextClass: Class<*>? = null

    /** The controller's back-reference to its taskbar; found once by type, then cached. */
    private var ownerField: Field? = null

    fun install(classLoader: ClassLoader, config: Flags.Config) {
        dpContextClass = XposedHelpers.findClassIfExists(Flags.DP_CONTEXT_CLASS, classLoader)
        taskbarContextClass = XposedHelpers.findClassIfExists(TASKBAR_CTX, classLoader)
        if (dpContextClass == null || taskbarContextClass == null) {
            Logx.e("stash hook: taskbar classes not found; skipping")
            return
        }

        if (config.bool("inApp", false)) force(classLoader, "isInApp", true)
        if (config.bool("visualStash", false)) force(classLoader, "supportsVisualStashing", true)
        if (config.bool("manualStash", false)) force(classLoader, "supportsManualStashing", true)
        if (config.bool("forceHide", false)) force(classLoader, "isForceHideTaskbar", true)

        if (config.bool("zuiDebug", false)) enableZuiFlagLogging(classLoader)
    }

    private fun force(classLoader: ClassLoader, method: String, value: Boolean) {
        try {
            XposedHelpers.findAndHookMethod(
                STASH_CTRL, classLoader, method,
                object : XC_MethodHook() {
                    private var logged = 0

                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("$STASH_CTRL.$method") {
                            val self = param.thisObject ?: return@guard
                            if (!isExternal(self)) return@guard
                            val original = param.result
                            if (original == value) return@guard
                            param.result = value
                            if (logged < 3) {
                                logged++
                                Logx.i("stash: $method $original -> $value")
                            }
                        }
                    }
                },
            )
            Logx.i("hooked $method -> $value (external taskbar only)")
        } catch (t: Throwable) {
            Logx.e("could not hook $STASH_CTRL.$method", t)
        }
    }

    private fun isExternal(self: Any): Boolean {
        val field = ownerField ?: self.javaClass.declaredFields
            .firstOrNull { taskbarContextClass?.isAssignableFrom(it.type) == true }
            ?.also {
                it.isAccessible = true
                ownerField = it
                Logx.i("stash: owner field is '${it.name}' (${it.type.simpleName})")
            }
        ?: return false
        return try {
            dpContextClass?.isInstance(field.get(self)) == true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * ZUI shipped their own FLAG_IN_APP tracing behind a static boolean. Turning it on
     * costs nothing and prints their view of the flag transitions under tag FLAG_IN_APPPP.
     */
    private fun enableZuiFlagLogging(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(STASH_CTRL, classLoader)
            XposedHelpers.setStaticBooleanField(cls, "FLAG_IN_APP_DEBUG", true)
            Logx.i("enabled ZUI FLAG_IN_APP_DEBUG (logcat tag FLAG_IN_APPPP)")
        } catch (t: Throwable) {
            Logx.e("could not enable FLAG_IN_APP_DEBUG", t)
        }
    }
}
