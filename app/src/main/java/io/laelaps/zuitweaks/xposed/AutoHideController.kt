package io.laelaps.zuitweaks.xposed

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference

/**
 * Collapse/expand state machine for the external-display taskbar.
 *
 * The move is TaskbarActivityContext.setTaskbarWindowSize(int) - the launcher's own
 * public API. Shrinking the window to the stashed height takes the pill *and* the nav
 * buttons off screen; restoring it brings both back, laid out by the launcher. That
 * sidesteps Launcher3's stash state machine, which on this build keeps isStashed()
 * false no matter which flag is set (see RECON.md).
 *
 * Cursor tracking hooks the drag layer's dispatchHoverEvent rather than using an
 * OnHoverListener: the listener only sees events the drag layer itself handles, so
 * moving the cursor onto a taskbar icon looked like leaving the bar and collapsed it
 * out from under the user. At the window root, HOVER_EXIT means the pointer left the
 * window, which is the question we actually want answered.
 */
class AutoHideController(context: Any, private val config: Flags.Config) {

    private enum class State { COLLAPSED, EXPANDED, PENDING_COLLAPSE }

    private val ctx = WeakReference(context)
    private val main = Handler(Looper.getMainLooper())

    private var collapsedPx = config.int("collapsedPx", 2)
    private var collapseDelayMs = config.long("collapseDelayMs", 1200L)

    /** Captured from the launcher rather than assumed; it differs per display and rotation. */
    private var expandedPx = -1

    private var state = State.COLLAPSED
    private var dragLayer = WeakReference<View>(null)
    private val collapseRunnable = Runnable { collapseIfIdle() }

    /**
     * Becomes the live controller and makes sure the process-wide hooks exist.
     *
     * The taskbar context is recreated on every display/config change, so attach() runs
     * again for each new one. The hooks below are installed on *framework* classes
     * (ViewGroup, TaskbarInsetsController), which are shared by the whole process and have
     * no unhook path here - installing them per controller stacked one more copy on every
     * monitor reconnect. They are installed once per (class, method) instead, and read
     * [current] to find the controller they should act on.
     */
    fun attach() {
        main.post {
            Logx.guard("autohide attach") {
                val c = ctx.get() ?: return@guard
                expandedPx = XposedHelpers.callMethod(c, "getWindowHeight") as Int
                if (expandedPx <= collapsedPx) {
                    Logx.e("autohide: implausible expanded height $expandedPx; not attaching")
                    return@guard
                }
                val layer = XposedHelpers.callMethod(c, "getDragLayer") as? View
                    ?: run { Logx.e("autohide: no drag layer"); return@guard }
                dragLayer = WeakReference(layer)
                current = this
                hookHoverDispatch(layer)
                hookTouchability(c)

                Logx.i("autohide attached: expanded=$expandedPx collapsed=$collapsedPx delay=${collapseDelayMs}ms")
                collapse()
            }
        }
    }

    /**
     * Watches every hover event that enters the taskbar window.
     *
     * Two earlier attempts were wrong in instructive ways, and both for the same reason:
     *  - `setOnHoverListener` only fires when no child handled the event, so putting the
     *    cursor on a taskbar icon looked like leaving the bar.
     *  - `View.dispatchHoverEvent` is no better. ViewGroup overrides it but does call
     *    super - only when no child consumed the hover and the action is not HOVER_EXIT.
     *    Same blind spot, plus it is small enough that ART may inline it in the boot
     *    image and skip the hook entirely.
     *
     * ViewGroup.dispatchHoverEvent is the entry point: reached for every hover event
     * delivered to the group, before any child dispatch. So resolve the class that
     * actually declares it for this view and hook that, filtered to our one instance.
     */
    private fun hookHoverDispatch(layer: View) {
        val hooked = ArrayList<String>()
        for (name in listOf("dispatchHoverEvent", "onHoverEvent", "onInterceptHoverEvent")) {
            val declaring = declaringClassOf(layer.javaClass, name) ?: continue
            if (!claim(declaring, name)) {
                hooked += "${declaring.simpleName}.$name (already installed)"
                continue
            }
            try {
                XposedHelpers.findAndHookMethod(
                    declaring, name, MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val self = current ?: return
                            if (param.thisObject !== self.dragLayer.get()) return
                            Logx.guard("hover $name") { self.onHover(param.args[0] as MotionEvent, name) }
                        }
                    },
                )
                hooked += "${declaring.simpleName}.$name"
            } catch (t: Throwable) {
                release(declaring, name)
                Logx.e("autohide: could not hook $name on ${declaring.name}", t)
            }
        }
        Logx.i("autohide: hover watchers = ${if (hooked.isEmpty()) "<none>" else hooked.joinToString()}")

        if (config.bool("tapToReveal", true)) hookTapToReveal(layer)
        if (config.bool("probeInput", false)) probeAllInput(layer)
    }

    /**
     * Expand on a touch landing in the collapsed strip. Hover only exists for pointer
     * sources that can hover; ZUI's virtual touchpad injects touchscreen-source events
     * instead, so without this the bar is unreachable from that input path entirely.
     */
    private fun hookTapToReveal(layer: View) {
        val declaring = declaringClassOf(layer.javaClass, "dispatchTouchEvent") ?: return
        if (!claim(declaring, "tap:dispatchTouchEvent")) return
        try {
            XposedHelpers.findAndHookMethod(
                declaring, "dispatchTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val self = current ?: return
                        if (param.thisObject !== self.dragLayer.get()) return
                        Logx.guard("tap to reveal") {
                            if (self.state != State.COLLAPSED) return@guard
                            if ((param.args[0] as MotionEvent).actionMasked != MotionEvent.ACTION_DOWN) return@guard
                            Logx.i("tap in collapsed strip -> expand")
                            self.onCursorEnter()
                        }
                    }
                },
            )
            Logx.i("autohide: tap-to-reveal on ${declaring.simpleName}.dispatchTouchEvent")
        } catch (t: Throwable) {
            release(declaring, "tap:dispatchTouchEvent")
            Logx.e("autohide: could not hook dispatchTouchEvent", t)
        }
    }

    /**
     * Diagnostic: logs every pointer event that reaches the taskbar window, with its
     * source. ZUI's virtual-touchpad cursor does not trigger the hover path the way a
     * real mouse does, and this shows what it sends instead - injected touch, a
     * different InputDevice source, or nothing at all.
     */
    private fun probeAllInput(layer: View) {
        for (name in listOf("dispatchTouchEvent", "dispatchGenericMotionEvent", "dispatchHoverEvent")) {
            val declaring = declaringClassOf(layer.javaClass, name) ?: continue
            if (!claim(declaring, "probe:$name")) continue
            try {
                XposedHelpers.findAndHookMethod(
                    declaring, name, MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val self = current ?: return
                            if (param.thisObject !== self.dragLayer.get()) return
                            Logx.guard("probe $name") {
                                val e = param.args[0] as MotionEvent
                                Logx.i(
                                    "input $name action=${e.actionMasked} " +
                                        "source=0x${e.source.toString(16)} " +
                                        "device=${e.deviceId} x=${e.x} y=${e.y} rawY=${e.rawY}"
                                )
                            }
                        }
                    },
                )
            } catch (t: Throwable) {
                release(declaring, "probe:$name")
                Logx.e("probe: could not hook $name", t)
            }
        }
        Logx.i("autohide: input probe ON (every pointer event on the taskbar window is logged)")
    }

    /** Walks up from [start] to find the class that declares [method]. */
    private fun declaringClassOf(start: Class<*>, method: String): Class<*>? {
        var c: Class<*>? = start
        while (c != null) {
            if (c.declaredMethods.any { it.name == method && it.parameterTypes.size == 1 }) return c
            c = c.superclass
        }
        return null
    }

    private var hoverLogged = 0

    private fun onHover(event: MotionEvent, via: String) {
        if (hoverLogged < 12) {
            hoverLogged++
            Logx.i("hover via $via action=${event.actionMasked} y=${event.y} state=$state")
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> onCursorEnter()
            MotionEvent.ACTION_HOVER_EXIT -> onCursorExit()
        }
    }

    /**
     * The taskbar window reports TOUCHABLE_INSETS_REGION, and the launcher computes that
     * region from where the icons are. Collapsed to a few pixels there are no icons, the
     * region comes out empty, and the window stops receiving pointer events entirely -
     * so the cursor could never reach the hot zone to bring the bar back. While collapsed,
     * claim the whole (tiny) frame instead.
     */
    private fun hookTouchability(context: Any) {
        val loader = context.javaClass.classLoader
        val insetsController = XposedHelpers.findClassIfExists(INSETS_CONTROLLER, loader)
            ?: run { Logx.e("autohide: $INSETS_CONTROLLER not found"); return }
        if (!claim(insetsController, "updateInsetsTouchability")) return
        try {
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            XposedHelpers.findAndHookMethod(
                insetsController,
                "updateInsetsTouchability",
                infoClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("updateInsetsTouchability") {
                            val self = current ?: return@guard
                            if (self.state != State.COLLAPSED) return@guard
                            val owner = XposedHelpers.callMethod(param.thisObject, "getContext")
                            if (owner !== self.ctx.get()) return@guard
                            XposedHelpers.callMethod(
                                param.args[0], "setTouchableInsets", TOUCHABLE_INSETS_FRAME
                            )
                        }
                    }
                },
            )
            Logx.i("autohide: claiming the full frame while collapsed")
        } catch (t: Throwable) {
            release(insetsController, "updateInsetsTouchability")
            Logx.e("autohide: could not hook updateInsetsTouchability", t)
        }
    }

    fun onCursorEnter() {
        main.removeCallbacks(collapseRunnable)
        if (state == State.EXPANDED) return
        expand()
    }

    fun onCursorExit() {
        if (state == State.COLLAPSED) return
        state = State.PENDING_COLLAPSE
        main.removeCallbacks(collapseRunnable)
        main.postDelayed(collapseRunnable, collapseDelayMs)
    }

    fun expand() = resize(expandedPx, State.EXPANDED)

    fun collapse() = resize(collapsedPx, State.COLLAPSED)

    /**
     * Opening the taskbar's all-apps panel makes the launcher grow its own window to
     * fullscreen. Collapsing on top of that leaves the panel drawn against a window that
     * is 20px tall - which is what emptied the icons out of the app list. Wait it out
     * instead of fighting the launcher for the window size.
     */
    private fun collapseIfIdle() {
        val c = ctx.get() ?: return
        val busy = try {
            XposedHelpers.callMethod(c, "isTaskbarWindowFullscreen") as? Boolean ?: false
        } catch (t: Throwable) {
            false
        }
        if (busy) {
            main.postDelayed(collapseRunnable, collapseDelayMs)
            return
        }
        collapse()
    }

    private fun resize(px: Int, next: State) {
        val c = ctx.get() ?: return
        main.post {
            Logx.guard("resize to $px") {
                XposedHelpers.callMethod(c, "setTaskbarWindowSize", px)
                state = next
            }
        }
    }

    private companion object {
        /** ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME */
        const val TOUCHABLE_INSETS_FRAME = 0

        const val INSETS_CONTROLLER = "com.android.launcher3.taskbar.TaskbarInsetsController"

        /**
         * The controller the process-wide hooks act on. A new taskbar context makes a new
         * controller; the hooks stay where they are and follow this reference instead.
         */
        @Volatile
        var current: AutoHideController? = null

        /**
         * "<class>#<role>" for every hook already installed in this process. The hooks land
         * on framework/launcher classes shared by every taskbar context and there is no
         * unhook path here, so a second controller must not install them again.
         */
        val installed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

        /** True if this call is the one that owns installing [role] on [declaring]. */
        fun claim(declaring: Class<*>, role: String): Boolean =
            installed.add("${declaring.name}#$role")

        /** Give the claim back when the hook could not actually be installed. */
        fun release(declaring: Class<*>, role: String) {
            installed.remove("${declaring.name}#$role")
        }
    }

    /**
     * Re-read the values that are only ever consulted at runtime, so the settings UI can
     * change them without a launcher restart. Anything that decides whether a hook gets
     * installed at all (tapToReveal, probeInput, stripInsets, ...) is fixed at attach
     * time and is NOT reloadable - the UI says so per setting.
     */
    fun reload(cfg: Flags.Config) {
        collapsedPx = cfg.int("collapsedPx", 2)
        collapseDelayMs = cfg.long("collapseDelayMs", 1200L)
        Logx.i("autohide reloaded: collapsed=$collapsedPx delay=${collapseDelayMs}ms")
        if (state == State.COLLAPSED) collapse()
    }

    fun describe(): String = "state=$state expanded=$expandedPx collapsed=$collapsedPx"
}
