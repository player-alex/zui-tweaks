package io.laelaps.zuitweaks.xposed

import android.content.Context
import java.lang.ref.WeakReference

/**
 * Finds the external-display taskbar and, optionally, switches it to Launcher3's
 * transient mode.
 *
 * Transient mode is off by default. Turning it on does shrink the reported insets
 * (navigationBars 80 -> 20, tappableElement -> 0) and makes the launcher rebuild all
 * four paramsForRotation entries for us, but it also replaces ZUI's full-width bar with
 * AOSP's floating pill - a visibly different taskbar - and Launcher3's stash state
 * machine still refuses to engage on this build (RECON.md 3/I). The hiding is done
 * through setTaskbarWindowSize anyway, so the default keeps ZUI's own appearance.
 *
 * Set `transient=1` in /sdcard/zuitweaks.conf to re-enable it.
 *
 * Every override is gated on the receiver being TaskbarActivityContextDp, the
 * external-display subclass, so the tablet's own taskbar is never touched.
 */
object TransientTaskbarHook {

    private const val TASKBAR_CTX = "com.android.launcher3.taskbar.TaskbarActivityContext"

    /**
     * Launcher3 asks the same question two ways. The stash paths go through the *static*
     * DisplayController.isTransientTaskbar(Context), not the instance method, so both
     * have to agree or the guards stay shut.
     */
    private const val DISPLAY_CONTROLLER = "com.android.launcher3.util.DisplayController"

    private var dpContextClass: Class<*>? = null
    private var config: Flags.Config? = null

    /**
     * The taskbar context the live AutoHideController belongs to. Weak: a strong field here
     * pinned every superseded TaskbarActivityContextDp (and its whole view tree) for the
     * lifetime of the process.
     */
    private var autoHideOwner = WeakReference<Any>(null)

    fun install(classLoader: ClassLoader, config: Flags.Config) {
        this.config = config
        dpContextClass = XposedHelpers.findClassIfExists(Flags.DP_CONTEXT_CLASS, classLoader)
        if (dpContextClass == null) {
            Logx.e("${Flags.DP_CONTEXT_CLASS} not found - cannot tell the two taskbars apart")
            return
        }

        val transient = config.bool("transient", false)
        Logx.i("transient mode: $transient")

        // Hooked either way: this is also how we get hold of the live external taskbar.
        override(classLoader, "isTransientTaskbar", true, apply = transient)
        if (transient) {
            override(classLoader, "isThreeButtonNav", false, apply = true)
            override(classLoader, "isPinnedTaskbar", false, apply = true)
            overrideStatic(classLoader, "isTransientTaskbar", true)
            overrideStatic(classLoader, "isPinnedTaskbar", false)
        }
    }

    /**
     * Forces a no-arg boolean predicate on the external-display taskbar. With [apply]
     * false it only observes, which is still how we capture the instance.
     */
    private fun override(classLoader: ClassLoader, method: String, value: Boolean, apply: Boolean) {
        try {
            XposedHelpers.findAndHookMethod(
                TASKBAR_CTX, classLoader, method,
                object : XC_MethodHook() {
                    private var logged = 0

                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("$TASKBAR_CTX.$method") {
                            val self = param.thisObject ?: return@guard
                            if (dpContextClass?.isInstance(self) != true) return@guard

                            Control.rememberDpContext(self)
                            attachAutoHide(self)

                            if (!apply) return@guard
                            val original = param.result
                            if (original == value) return@guard
                            param.result = value
                            if (logged < 3) {
                                logged++
                                Logx.i("$method() on ${self.javaClass.simpleName}: $original -> $value")
                            }
                        }
                    }
                },
            )
            Logx.i("hooked $method (${if (apply) "-> $value" else "observe only"})")
        } catch (t: Throwable) {
            Logx.e("could not hook $TASKBAR_CTX.$method", t)
        }
    }

    /** Same override, for DisplayController's static Context-taking variants. */
    private fun overrideStatic(classLoader: ClassLoader, method: String, value: Boolean) {
        try {
            XposedHelpers.findAndHookMethod(
                DISPLAY_CONTROLLER, classLoader, method, Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("$DISPLAY_CONTROLLER.$method") {
                            val ctx = param.args.getOrNull(0) ?: return@guard
                            if (dpContextClass?.isInstance(ctx) != true) return@guard
                            if (param.result == value) return@guard
                            param.result = value
                        }
                    }
                },
            )
            Logx.i("hooked DisplayController.$method -> $value (external taskbar only)")
        } catch (t: Throwable) {
            Logx.e("could not hook $DISPLAY_CONTROLLER.$method", t)
        }
    }

    /**
     * The taskbar context is recreated on display and config changes, so the controller
     * follows it rather than being installed once at load time.
     */
    private fun attachAutoHide(ctx: Any) {
        val cfg = config ?: return
        if (!cfg.bool("autohide", true)) return
        if (autoHideOwner.get() === ctx) return
        autoHideOwner = WeakReference(ctx)
        AutoHideController(ctx, cfg).also {
            Control.autoHide = it
            it.attach()
        }
    }
}
