package io.laelaps.zuitweaks.xposed

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.lang.reflect.Array as JArray

/**
 * Phase D: observation only. Nothing here mutates anything.
 *
 * Round 1 established that the ZUI launcher is AOSP Launcher3 with the taskbar
 * stack intact (TaskbarActivityContext / TaskbarStashController / TaskbarInsetsController),
 * and that com.zui.launcher.taskbar.TaskbarActivityContextDp is the external-display
 * variant. Round 2 answers what the "work with the grain" hook needs to know:
 *
 *  1. Is the Taskbar_dp instance really a TaskbarActivityContextDp? (the discriminator
 *     that keeps us off the internal-display taskbar)
 *  2. What do isTransientTaskbar / isPinnedTaskbar / isTaskbarStashed return for each?
 *  3. Does the launcher rewrite the window through updateViewLayout / setTaskbarWindowSize?
 */
object ProbeHook {

    private const val TASKBAR_CTX = "com.android.launcher3.taskbar.TaskbarActivityContext"
    private const val TASKBAR_UTILS = "com.zui.launcher.utils.TaskbarUtilities"

    private var addViewCount = 0
    private var updateCount = 0

    /** Keeps a hot method from flooding logcat while still showing state changes. */
    private val seen = HashMap<String, Int>()

    private fun once(key: String, limit: Int = 3): Boolean {
        val n = (seen[key] ?: 0) + 1
        seen[key] = n
        return n <= limit
    }

    fun install(classLoader: ClassLoader) {
        Logx.i("probe v2 installing; killSwitch[${Flags.killSwitchReport()}]")

        hookWindowManager(classLoader)
        hookTaskbarDecisions(classLoader)
        hookTaskbarUtilities(classLoader)
    }

    // ---------------------------------------------------------------- WindowManager

    private fun hookWindowManager(classLoader: ClassLoader) {
        hookImpl(classLoader, "addView") { view, lp -> onAddView(view, lp) }
        hookImpl(classLoader, "updateViewLayout") { view, lp -> onUpdateViewLayout(view, lp) }
    }

    private fun hookImpl(
        classLoader: ClassLoader,
        method: String,
        onCall: (View, WindowManager.LayoutParams) -> Unit,
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", classLoader, method,
                View::class.java, ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("WindowManagerImpl.$method") {
                            val view = param.args[0] as? View ?: return@guard
                            val lp = param.args[1] as? WindowManager.LayoutParams ?: return@guard
                            onCall(view, lp)
                        }
                    }
                },
            )
            Logx.i("hooked WindowManagerImpl.$method")
        } catch (t: Throwable) {
            Logx.e("could not hook WindowManagerImpl.$method", t)
        }
    }

    private fun onAddView(view: View, lp: WindowManager.LayoutParams) {
        addViewCount++
        if (!isTaskbarish(lp)) return
        Logx.i("=== addView #$addViewCount ===")
        dump(view, lp)
    }

    private fun onUpdateViewLayout(view: View, lp: WindowManager.LayoutParams) {
        if (!isTaskbarish(lp)) return
        updateCount++
        // If this fires often for Taskbar_dp, an addView-only patch would not hold.
        Logx.i(
            "updateViewLayout #$updateCount title='${lp.title}' display=${displayIdOf(view)} " +
                "h=${lp.height} y=${lp.y} vis=${visibilityName(view.visibility)} " +
                "navInset=${navBarInsetBottom(lp)}"
        )
    }

    // ------------------------------------------------- Launcher3 taskbar decisions

    /**
     * These are the methods a "work with the grain" hook would override. Logging their
     * receiver class and result tells us whether TaskbarActivityContextDp is a reliable
     * discriminator, and what the transient/pinned state currently is on each display.
     */
    private fun hookTaskbarDecisions(classLoader: ClassLoader) {
        // isTransientTaskbar / isThreeButtonNav / isPinnedTaskbar are owned by
        // TransientTaskbarHook - hooking them here too would race over param.result.
        val noArgBooleans = listOf(
            "isTaskbarStashed", "isGestureNav", "isInStashedLauncherState",
        )
        for (name in noArgBooleans) hookReporting(classLoader, TASKBAR_CTX, name)

        for (name in listOf("getDefaultTaskbarWindowSize", "getWindowHeight")) {
            hookReporting(classLoader, TASKBAR_CTX, name)
        }

        // The factory that builds the Taskbar_dp LayoutParams, title and all.
        try {
            XposedHelpers.findAndHookMethod(
                TASKBAR_CTX, classLoader, "createDefaultWindowLayoutParams",
                Int::class.javaPrimitiveType, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("createDefaultWindowLayoutParams") {
                            val lp = param.result as? WindowManager.LayoutParams ?: return@guard
                            Logx.i(
                                "createDefaultWindowLayoutParams(type=${param.args[0]}, " +
                                    "title='${param.args[1]}') on ${receiver(param)} " +
                                    "-> h=${lp.height} navInset=${navBarInsetBottom(lp)} " +
                                    "rotationParams=${rotationParamCount(lp)}"
                            )
                        }
                    }
                },
            )
            Logx.i("hooked $TASKBAR_CTX.createDefaultWindowLayoutParams")
        } catch (t: Throwable) {
            Logx.e("could not hook createDefaultWindowLayoutParams", t)
        }

        hookAllReporting(classLoader, TASKBAR_CTX, "setTaskbarWindowSize")
        hookAllReporting(classLoader, TASKBAR_CTX, "setAutohideSuspendFlag")
        hookAllReporting(classLoader, TASKBAR_CTX, "unstashTaskbarIfStashed")
        hookAllReporting(classLoader, TASKBAR_CTX, "onSwipeToUnstashTaskbar")
    }

    /** Static helpers ZUI added; they take a Context, so displayId separates the two bars. */
    private fun hookTaskbarUtilities(classLoader: ClassLoader) {
        for (name in listOf("isTaskbarStashed", "isTaskbarEnable", "isNavbarEnable", "isInDesktopMode")) {
            try {
                XposedHelpers.findAndHookMethod(
                    TASKBAR_UTILS, classLoader, name, Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Logx.guard("TaskbarUtilities.$name") {
                                val ctx = param.args[0] as? Context
                                val display = try {
                                    ctx?.display?.displayId ?: -1
                                } catch (t: Throwable) {
                                    -1
                                }
                                if (once("util:$name:$display:${param.result}")) {
                                    Logx.i("TaskbarUtilities.$name(display=$display) = ${param.result}")
                                }
                            }
                        }
                    },
                )
            } catch (t: Throwable) {
                Logx.e("could not hook $TASKBAR_UTILS.$name", t)
            }
        }
    }

    private fun hookReporting(classLoader: ClassLoader, className: String, method: String) {
        try {
            XposedHelpers.findAndHookMethod(
                className, classLoader, method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("$className.$method") {
                            val key = "$method:${receiver(param)}:${param.result}"
                            if (once(key)) Logx.i("$method() on ${receiver(param)} = ${param.result}")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            Logx.e("could not hook $className.$method", t)
        }
    }

    /** For overloaded or unknown-signature methods: hook every overload by name. */
    private fun hookAllReporting(classLoader: ClassLoader, className: String, method: String) {
        try {
            val cls = XposedHelpers.findClass(className, classLoader)
            val n = XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("$className.$method") {
                        val args = param.args.joinToString(", ") { it?.toString()?.take(40).orEmpty() }
                        if (once("call:$method:${receiver(param)}:$args", limit = 6)) {
                            Logx.i("$method($args) on ${receiver(param)}")
                        }
                    }
                }
            }).size
            if (n == 0) Logx.i("no overload of $className.$method found")
        } catch (t: Throwable) {
            Logx.e("could not hook $className.$method", t)
        }
    }

    private fun receiver(param: XC_MethodHook.MethodHookParam): String =
        param.thisObject?.javaClass?.simpleName ?: "static"

    // ------------------------------------------------------------------- dumping

    private fun isTaskbarish(lp: WindowManager.LayoutParams): Boolean {
        val title = lp.title?.toString().orEmpty()
        return lp.type == Flags.TYPE_NAVIGATION_BAR_PANEL || title.contains("Taskbar", ignoreCase = true)
    }

    private fun dump(view: View, lp: WindowManager.LayoutParams) {
        Logx.i("  view=${view.javaClass.name} visibility=${visibilityName(view.visibility)}")
        Logx.i("  ctx=${view.context.javaClass.name}")
        Logx.i("  title='${lp.title}' type=${lp.type} display=${displayIdOf(view)}")
        Logx.i(
            "  w=${lp.width} h=${lp.height} x=${lp.x} y=${lp.y} gravity=0x${lp.gravity.toString(16)} " +
                "flags=0x${lp.flags.toString(16)} privateFlags=0x${intField(lp, "privateFlags").toString(16)}"
        )
        Logx.i("  isTarget=${lp.title?.toString() == Flags.TARGET_TITLE && displayIdOf(view) != 0}")

        dumpProvidedInsets("  providedInsets", lp)

        val rotationParams = objField(lp, "paramsForRotation")
        if (rotationParams == null) {
            Logx.i("  paramsForRotation: ABSENT")
            return
        }
        val n = JArray.getLength(rotationParams)
        Logx.i("  paramsForRotation: $n entries")
        for (i in 0 until n) {
            val rot = JArray.get(rotationParams, i) as? WindowManager.LayoutParams ?: continue
            Logx.i("    [$i] h=${rot.height} y=${rot.y} navInset=${navBarInsetBottom(rot)}")
        }
    }

    private fun dumpProvidedInsets(label: String, lp: WindowManager.LayoutParams) {
        val insets = objField(lp, "providedInsets")
        if (insets == null) {
            Logx.i("$label: FIELD MISSING; declared fields follow")
            lp.javaClass.declaredFields.forEach { Logx.i("    ${it.name}: ${it.type.name}") }
            return
        }
        val n = JArray.getLength(insets)
        Logx.i("$label: $n entries (${insets.javaClass.componentType?.name})")
        for (i in 0 until n) Logx.i("    [$i] ${JArray.get(insets, i)}")
    }

    private fun rotationParamCount(lp: WindowManager.LayoutParams): Int {
        val arr = objField(lp, "paramsForRotation") ?: return -1
        return JArray.getLength(arr)
    }

    /**
     * Pulls the navigationBars insetsSize out of a provider's toString rather than
     * reflecting into InsetsFrameProvider - the class is @hide and its field layout
     * has moved between releases, but its toString has been stable.
     */
    private fun navBarInsetBottom(lp: WindowManager.LayoutParams): String {
        val insets = objField(lp, "providedInsets") ?: return "n/a"
        for (i in 0 until JArray.getLength(insets)) {
            val s = JArray.get(insets, i)?.toString().orEmpty()
            if (!s.contains("type=navigationBars")) continue
            val at = s.indexOf("insetsSize=")
            if (at < 0) return "?"
            val end = s.indexOf('}', at)
            return if (end < 0) "?" else s.substring(at, end + 1)
        }
        return "none"
    }

    private fun displayIdOf(view: View): Int = try {
        view.context.display?.displayId ?: -1
    } catch (t: Throwable) {
        -1
    }

    private fun visibilityName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "?$v"
    }

    private fun objField(target: Any, name: String): Any? = try {
        XposedHelpers.getObjectField(target, name)
    } catch (t: Throwable) {
        null
    }

    private fun intField(target: Any, name: String): Int = try {
        XposedHelpers.getIntField(target, name)
    } catch (t: Throwable) {
        0
    }
}
