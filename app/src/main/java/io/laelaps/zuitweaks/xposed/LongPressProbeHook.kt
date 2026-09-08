package io.laelaps.zuitweaks.xposed

import android.view.View
import android.view.ViewParent

/**
 * Drawer-grouping probe: OBSERVATION ONLY. Nothing here mutates anything.
 *
 * Goal: learn the exact call chain when an app icon in the app drawer (all-apps) is
 * long-pressed, so the later interception (long-press -> movable/select mode, ZUI menu
 * deferred to release) can hook the right, stable seam instead of guessing.
 *
 * Confirmed seams (dex of ZuiLauncher 18.2.0.0400; RECON "drawer grouping" recon):
 *   com.android.launcher3.popup.PopupContainerWithArrow.showForIcon(...)  <- the menu-show entry
 *   com.android.launcher3.touch.ItemLongClickListener.{beginDrag,canStartDrag}
 *   com.android.launcher3.dragndrop.DragController.startDrag(...)
 *   com.zui.launcher.uiextend.ZuiItemLongClickListener.{a,b}  <- ZUI's own long-click (R8-obf)
 *
 * What the logs answer:
 *   - Does a drawer long-press go through showForIcon (menu) AND/OR start a drag?
 *   - Which surface does the pressed icon live in (all-apps / workspace / folder / taskbar)?
 *   - Which app (BubbleTextView text + tag/ItemInfo) is the gesture target?
 *   - Is ZuiItemLongClickListener.a/b the true entry we must intercept?
 *
 * Enable per RECON's config channels, e.g. add `longpressprobe=1` to /sdcard/zuitweaks.conf,
 * then restart the launcher (`am force-stop com.zui.launcher`). Default is OFF.
 */
object LongPressProbeHook {

    private const val POPUP = "com.android.launcher3.popup.PopupContainerWithArrow"
    private const val ITEM_LONGCLICK = "com.android.launcher3.touch.ItemLongClickListener"
    private const val DRAG_CONTROLLER = "com.android.launcher3.dragndrop.DragController"
    private const val LAUNCHER_DRAG_CONTROLLER = "com.android.launcher3.dragndrop.LauncherDragController"
    private const val ZUI_LONGCLICK = "com.zui.launcher.uiextend.ZuiItemLongClickListener"
    private const val STATE_MANAGER = "com.android.launcher3.statemanager.StateManager"
    private const val ALLAPPS_TRANSITION = "com.android.launcher3.allapps.AllAppsTransitionController"
    private const val FOLDER = "com.android.launcher3.folder.Folder"

    /** Running call counts per key, so repeated presses stay visible but a hot method can't flood. */
    private val counts = HashMap<String, Int>()

    private fun bump(key: String, cap: Int): Int? {
        val n = (counts[key] ?: 0) + 1
        counts[key] = n
        return if (n <= cap) n else null
    }

    fun install(cl: ClassLoader) {
        Logx.i("longpress probe installing (observation only)")
        // Discrete, user-gesture-driven -> log every call (generous cap).
        hookAll(cl, POPUP, "showForIcon", cap = 40)
        hookAll(cl, ITEM_LONGCLICK, "beginDrag", cap = 40)
        // startDrag is declared on the abstract base and/or the concrete subclass; try both,
        // each optional (a class-not-here or unresolvable-signature is fine, not an error).
        hookAll(cl, DRAG_CONTROLLER, "startDrag", cap = 40, optional = true)
        hookAll(cl, LAUNCHER_DRAG_CONTROLLER, "startDrag", cap = 40, optional = true)
        // ZUI's custom handler is what we ultimately intercept; watch it closely.
        hookAll(cl, ZUI_LONGCLICK, "a", cap = 40)
        hookAll(cl, ZUI_LONGCLICK, "b", cap = 40)
        // Potentially chatty (touch/scroll) -> tighter cap.
        hookAll(cl, ITEM_LONGCLICK, "canStartDrag", cap = 12)
        // State transitions: which call collapses the drawer (ALL_APPS -> NORMAL) when a
        // drawer drag starts. The caller stack is the point we would gate to keep it open.
        hookState(cl, STATE_MANAGER, "goToState")
        hookState(cl, ALLAPPS_TRANSITION, "setState")
        hookState(cl, ALLAPPS_TRANSITION, "setStateWithAnimation")
        // Folder close/drag path: which of these tears the folder down on an internal long-press.
        for (m in listOf("handleClose", "onDropCompleted", "completeDragExit", "onDragStart", "onDragEnd", "onDrop", "acceptDrop", "onDragOver")) {
            hookTrace(cl, FOLDER, m)
        }
    }

    /** Log a method call with receiver + launcher caller chain (for tracing tear-down paths). */
    private fun hookTrace(cl: ClassLoader, className: String, method: String) {
        try {
            val cls = XposedHelpers.findClassIfExists(className, cl) ?: return
            val n = XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("$className.$method") {
                        val c = bump("$className#$method", 20) ?: return@guard
                        Logx.i("FT-> [$c] ${short(className)}.$method | ${caller()}")
                    }
                }
            }).size
            Logx.i("folder trace: hooked $className.$method ($n)")
        } catch (t: Throwable) {
            Logx.e("folder trace: could not hook $className.$method", t)
        }
    }

    /** Logs a state transition with its target state and the launcher-side caller chain. */
    private fun hookState(cl: ClassLoader, className: String, method: String) {
        try {
            val cls = XposedHelpers.findClassIfExists(className, cl)
                ?: run { Logx.i("state probe: class absent $className"); return }
            val n = XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("$className.$method") {
                        val c = bump("$className#$method", 30) ?: return@guard
                        val target = param.args?.getOrNull(0)
                        val state = target?.let {
                            "${it.javaClass.simpleName}(${runCatching { it.toString() }.getOrNull()?.take(40) ?: "-"})"
                        } ?: "?"
                        Logx.i("ST-> [$c] ${short(className)}.$method($state) | ${caller()}")
                    }
                }
            }).size
            Logx.i("state probe: hooked $className.$method ($n overload(s))")
            if (n == 0) Logx.i("state probe: NO overload of $className.$method found")
        } catch (t: Throwable) {
            Logx.e("state probe: could not hook $className.$method", t)
        }
    }

    /** The launcher/quickstep/zui frames that led here, module and framework glue stripped. */
    private fun caller(): String {
        val out = ArrayList<String>()
        for (f in Throwable().stackTrace) {
            val cn = f.className
            if (cn.startsWith("io.laelaps") || cn.contains("xposed", ignoreCase = true) ||
                cn.contains("libxposed") || cn.contains("LspHooker")
            ) continue
            if (cn.contains("launcher") || cn.contains("quickstep") || cn.contains("zui")) {
                out.add("${cn.substringAfterLast('.')}.${f.methodName}")
                if (out.size >= 7) break
            }
        }
        return if (out.isEmpty()) "<no launcher frames>" else out.joinToString(" <- ")
    }

    private fun hookAll(cl: ClassLoader, className: String, method: String, cap: Int, optional: Boolean = false) {
        try {
            val cls = XposedHelpers.findClassIfExists(className, cl)
            if (cls == null) {
                Logx.i("longpress probe: class absent, skipped $className")
                return
            }
            val handles = XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("$className.$method") {
                        val n = bump("$className#$method", cap) ?: return@guard
                        val recv = param.thisObject?.javaClass?.simpleName ?: "static"
                        val args = param.args?.joinToString(", ") { describe(it) } ?: ""
                        Logx.i("LP-> [$n] ${short(className)}.$method($args) on $recv")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("$className.$method:after") {
                        // Only the decision-returning calls are worth an "after" line.
                        if (method != "showForIcon" && method != "canStartDrag") return@guard
                        val n = bump("$className#$method#result", cap) ?: return@guard
                        Logx.i("LP<- [$n] ${short(className)}.$method => ${describe(param.result)}")
                    }
                }
            }).size
            Logx.i("longpress probe: hooked $className.$method ($handles overload(s))")
            if (handles == 0) Logx.i("longpress probe: NO overload of $className.$method found")
        } catch (t: Throwable) {
            // An optional target (e.g. startDrag on an abstract base whose signature won't
            // resolve here) is not a failure worth an ERROR line.
            if (optional) Logx.i("longpress probe: optional $className.$method not hooked (${t.javaClass.simpleName})")
            else Logx.e("longpress probe: could not hook $className.$method", t)
        }
    }

    // ------------------------------------------------------------------- description

    /** Best-effort identity of an argument. Never throws. */
    private fun describe(o: Any?): String {
        if (o == null) return "null"
        val c = o.javaClass.simpleName.ifEmpty { o.javaClass.name }
        if (o is View) {
            val text = runCatching {
                (o.javaClass.getMethod("getText").invoke(o) as? CharSequence)?.toString()
            }.getOrNull()
            val tag = runCatching { o.tag }.getOrNull()
            val tagDesc = tag?.let { "${it.javaClass.simpleName}[${it.toString().take(70)}]" } ?: "noTag"
            return "$c{text=${text ?: "-"}, surface=${surfaceOf(o)}, tag=$tagDesc}"
        }
        // Compact for common non-views; full toString would drown the log.
        val s = runCatching { o.toString() }.getOrNull().orEmpty()
        return if (s.length <= 48) "$c($s)" else c
    }

    /**
     * Walk up the view tree and name the first ancestor that identifies the surface. This is
     * the definitive drawer-vs-home discriminator for a long-pressed icon.
     */
    private fun surfaceOf(v: View): String {
        var p: ViewParent? = v.parent
        var hops = 0
        while (p != null && hops < 16) {
            val name = p.javaClass.simpleName
            when {
                name.contains("AllApps", ignoreCase = true) -> return "AllApps($name)"
                name.contains("Drawer", ignoreCase = true) -> return "Drawer($name)"
                name.contains("Folder", ignoreCase = true) -> return "Folder($name)"
                name.contains("Taskbar", ignoreCase = true) -> return "Taskbar($name)"
                name.contains("Workspace", ignoreCase = true) ||
                    name.contains("CellLayout", ignoreCase = true) ||
                    name.contains("Hotseat", ignoreCase = true) -> return "Workspace($name)"
            }
            p = p.parent
            hops++
        }
        return "?"
    }

    private fun short(className: String): String = className.substringAfterLast('.')
}
