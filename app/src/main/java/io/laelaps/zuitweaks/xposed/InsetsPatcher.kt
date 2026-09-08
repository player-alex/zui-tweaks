package io.laelaps.zuitweaks.xposed

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.lang.reflect.Array as JArray

/**
 * Zeroes the insets the external taskbar reports, so apps get the whole screen.
 *
 * Shrinking the window with setTaskbarWindowSize hides the bar but does not give the
 * space back: the launcher keeps publishing an 80px navigationBars inset regardless of
 * window height (measured - see RECON.md). Transient mode would drop it to 20px, but at
 * the cost of replacing ZUI's bar with AOSP's floating pill, so instead the providers
 * are emptied out here and the bar becomes a pure overlay.
 *
 * Applied at both addView and updateViewLayout: the launcher rebuilds providedInsets
 * whenever the taskbar window is resized, which is exactly what auto-hide does.
 *
 * Only the window titled Taskbar_dp on a non-default display is touched; the tablet's
 * own Taskbar shares the package and window type, so the title check is what protects it.
 */
object InsetsPatcher {

    private var dumpedFields = false

    fun install(classLoader: ClassLoader) {
        hook(classLoader, "addView")
        hook(classLoader, "updateViewLayout")
    }

    private fun hook(classLoader: ClassLoader, method: String) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", classLoader, method,
                View::class.java, ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("InsetsPatcher.$method") {
                            val view = param.args[0] as? View ?: return@guard
                            val lp = param.args[1] as? WindowManager.LayoutParams ?: return@guard
                            if (!isExternalTaskbar(view, lp)) return@guard
                            patch(lp)
                        }
                    }
                },
            )
            Logx.i("insets: hooked WindowManagerImpl.$method")
        } catch (t: Throwable) {
            Logx.e("insets: could not hook WindowManagerImpl.$method", t)
        }
    }

    private fun isExternalTaskbar(view: View, lp: WindowManager.LayoutParams): Boolean {
        if (lp.type != Flags.TYPE_NAVIGATION_BAR_PANEL) return false
        if (lp.title?.toString() != Flags.TARGET_TITLE) return false
        val display = try {
            view.context.display?.displayId ?: 0
        } catch (t: Throwable) {
            0
        }
        return display != 0
    }

    /** The main params plus every per-rotation copy, which WMS swaps in on rotation. */
    private fun patch(lp: WindowManager.LayoutParams) {
        zeroProviders(lp)
        val rotations = XposedHelpers.getObjectField(lp, "paramsForRotation") ?: return
        for (i in 0 until JArray.getLength(rotations)) {
            (JArray.get(rotations, i) as? WindowManager.LayoutParams)?.let { zeroProviders(it) }
        }
    }

    private fun zeroProviders(lp: WindowManager.LayoutParams) {
        val providers = try {
            XposedHelpers.getObjectField(lp, "providedInsets")
        } catch (t: Throwable) {
            null
        } ?: return

        for (i in 0 until JArray.getLength(providers)) {
            val provider = JArray.get(providers, i) ?: continue
            if (!dumpedFields) {
                dumpedFields = true
                Logx.i("insets: InsetsFrameProvider fields:")
                provider.javaClass.declaredFields.forEach { Logx.i("    ${it.name}: ${it.type.name}") }
            }
            zeroOne(provider)
        }
    }

    /**
     * Field names on InsetsFrameProvider are @hide and have moved between releases, so
     * match on type instead: the Insets-typed field is the inset size, and the array of
     * per-window-type overrides each carry one of their own.
     */
    private fun zeroOne(provider: Any) {
        val none = android.graphics.Insets.NONE
        for (field in provider.javaClass.declaredFields) {
            try {
                when {
                    field.type == android.graphics.Insets::class.java -> {
                        field.isAccessible = true
                        field.set(provider, none)
                    }

                    field.type.isArray && field.type.componentType?.name?.contains("InsetsSizeOverride") == true -> {
                        field.isAccessible = true
                        val overrides = field.get(provider) ?: continue
                        for (j in 0 until JArray.getLength(overrides)) {
                            val override = JArray.get(overrides, j) ?: continue
                            override.javaClass.declaredFields
                                .filter { it.type == android.graphics.Insets::class.java }
                                .forEach { it.isAccessible = true; it.set(override, none) }
                        }
                    }
                }
            } catch (t: Throwable) {
                Logx.e("insets: could not zero ${field.name}", t)
            }
        }
    }
}
