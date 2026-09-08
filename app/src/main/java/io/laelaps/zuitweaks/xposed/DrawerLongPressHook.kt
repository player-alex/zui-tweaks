package io.laelaps.zuitweaks.xposed

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.lang.reflect.Member

/**
 * Drawer grouping — the in-drawer "movable mode" with boundary handoff.
 *
 * Probe findings (RECON drawer-grouping recon):
 *   - A drawer long-press starts LauncherDragController.startDrag with an all-apps source
 *     (LauncherAllAppsContainerView / AppInfo).
 *   - startDrag synchronously runs onDragStart -> StateManager.goToState(SpringLoaded), which
 *     collapses the drawer and reveals the home screen. On drop, DropTargetHandler ->
 *     goToState(Normal) sends it home.
 *
 * The user's model, in two halves:
 *   (kept open)  While the drag pointer is INSIDE the drawer, suppress SpringLoaded/Normal so
 *               the drawer stays open and a release returns the icon to the drawer.
 *   (handoff)    Once the pointer LEAVES the drawer bounds, perform the SpringLoaded transition
 *               we had suppressed (re-invoking the original goToState we stashed) and stop
 *               suppressing, so native home placement takes over for the rest of the drag.
 *
 * Scope: everything is gated on `drawerDragActive`, set only for a drag whose source is the
 * all-apps container. A home-screen (workspace) drag is untouched.
 *
 * OFF by default. `drawermovable=1` in /sdcard/zuitweaks.conf, then restart the launcher.
 * Killswitch /sdcard/zuitweaks.off still disables everything.
 */
object DrawerLongPressHook {

    private const val POPUP = "com.android.launcher3.popup.PopupContainerWithArrow"
    private const val LAUNCHER_DRAG_CONTROLLER = "com.android.launcher3.dragndrop.LauncherDragController"
    private const val SECONDARY_DRAG_CONTROLLER = "com.android.launcher3.secondarydisplay.SecondaryDragController"
    private const val DRAG_CONTROLLER = "com.android.launcher3.dragndrop.DragController"
    private const val STATE_MANAGER = "com.android.launcher3.statemanager.StateManager"
    private const val FOLDER = "com.android.launcher3.folder.Folder"
    private const val FOLDER_PAGED_VIEW = "com.android.launcher3.folder.FolderPagedView"
    private const val ABSTRACT_FLOATING = "com.android.launcher3.AbstractFloatingView"

    /**
     * True while an icon is being dragged INSIDE our reused folder. A folder-item drag closes the
     * folder immediately (Folder.handleClose via AbstractFloatingView.close from startDrag) and
     * then drops to home; we suppress that close + the home transition so the item reorders inside
     * the open folder instead of kicking the user out.
     */
    @Volatile private var folderItemDragActive = false

    // Folder-item drag: distinguish a real reorder (moved) from a tap-hold (no move -> menu).
    @Volatile private var folderDragMoved = false
    @Volatile private var folderDragBaselineSet = false
    /** Set once a folder-item drag leaves the folder panel: from then on the folder may close. */
    @Volatile private var folderDragLeftBounds = false
    @Volatile private var folderDragStartX = 0
    @Volatile private var folderDragStartY = 0
    @Volatile private var folderDragIcon: View? = null
    @Volatile private var folderShowMenuOnEnd = false
    /** One-shot: lets the next showForIcon through our own suppression (to show the menu). */
    @Volatile private var allowMenuOnce = false
    // Drag-reorder deltas (user-tunable in the settings UI; defaults match Schema).
    @Volatile private var folderDragSlopPx = 30
    @Volatile private var drawerDragSlopPx = 40
    @Volatile private var groupZoneHalf = 0.18f   // half-width of the group centre zone, as a fraction

    /** True while a drag that originated in the app drawer is in flight. */
    @Volatile private var drawerDragActive = false

    /** Set once the pointer has left the drawer: from here on we stop keeping the drawer open. */
    @Volatile private var handedOff = false

    /** Bumped on every drawer startDrag; lets a deferred clear ignore a superseded drag. */
    @Volatile private var dragEpoch = 0

    /** The all-apps container, captured at drag start, used as the drawer boundary rect. */
    @Volatile private var drawerView: View? = null

    // The suppressed SpringLoaded transition, stashed so we can replay it on handoff.
    @Volatile private var stashStateManager: Any? = null
    @Volatile private var stashGoToState: Member? = null
    @Volatile private var stashSpringArgs: Array<Any?>? = null

    /**
     * A SpringLoaded LauncherState object, captured whenever goToState(SpringLoaded) passes through
     * our hook (any drawer/app drag). Native does NOT start a SpringLoaded transition for a
     * folder-ITEM drag, so to let an app be dragged out of the folder onto the home screen we drive
     * that transition ourselves — this is the state object to pass.
     */
    @Volatile private var springLoadedState: Any? = null

    private var moveLogTick = 0

    // Edge auto-scroll while dragging inside the grid, so off-screen icons can be reached.
    @Volatile private var lastPointerX = 0
    @Volatile private var lastPointerY = 0
    @Volatile private var autoScrolling = false

    // The dragged icon and its label, captured at drag start, for drop-target grouping.
    @Volatile private var draggedIcon: View? = null
    // The dragged app's AppInfo TAG captured at drag start. Must NOT be read live from draggedIcon.tag
    // at drop time: draggedIcon is a RecyclerView item view that is recycled/rebound during the reflow,
    // so its tag drifts to another item (a folder sentinel), corrupting the group source.
    @Volatile private var draggedTag: Any? = null
    @Volatile private var sourceLabel = "-"

    // Live drawer reorder (config drawerorder=1): reorder began, the app currently in a group "centre
    // zone" (drop-to-group instead of reorder), and the dragged app's slot key.
    @Volatile private var reorderBegan = false
    @Volatile private var groupCandidate: View? = null
    @Volatile private var draggedSlotKey: String? = null

    // EXTERNAL display (SecondaryDisplayLauncher) drag state. That surface drives its drag through
    // SecondaryDragController (its OWN startDrag — our LauncherDragController.startDrag hook never sees
    // it), while it INHERITS DragController.callOnDragEnd/handleMoveEvent (our base hooks DO fire). So
    // the shared drawer state above is never established/reset for an external drag, and a stale
    // drawerDragActive/draggedIcon from a prior internal drag could make callOnDragEnd group the wrong
    // apps. We track the external drag on its OWN fields here (isolated from the Launcher-only
    // handoff/keep-open paths) and reset the shared state when it starts.
    @Volatile private var secondaryDragActive = false
    @Volatile private var secondaryDraggedIcon: View? = null
    @Volatile private var secondaryGrid: View? = null
    @Volatile private var secondaryLastX = 0
    @Volatile private var secondaryLastY = 0
    @Volatile private var secondaryStartX = 0
    @Volatile private var secondaryStartY = 0
    @Volatile private var secondaryBaselineSet = false
    @Volatile private var secondaryMoved = false

    // Drawer drag movement gate: a long-press + release WITHOUT moving past this slop is a tap-hold →
    // show the app menu (not a reorder). Baseline is the first move event (startDrag args are the
    // drag-view registration point, not the touch).
    @Volatile private var drawerDragMoved = false
    @Volatile private var drawerBaselineSet = false
    @Volatile private var drawerStartX = 0
    @Volatile private var drawerStartY = 0
    private const val EDGE_BAND_PX = 200   // grid-edge band that triggers auto-scroll
    private const val MAX_SCROLL_PX = 36   // per ~16ms tick, scaled by depth into the band

    // When on, a drop onto an icon opens our custom drawer-folder overlay.
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var folderUi = false

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!drawerDragActive || handedOff) { autoScrolling = false; return }
            val v = drawerView ?: run { autoScrolling = false; return }
            val r = screenRect(v) ?: run { autoScrolling = false; return }
            val y = lastPointerY
            // Negative dy scrolls the list up (toward the top); positive scrolls down.
            val dy = when {
                y <= r[1] + EDGE_BAND_PX -> -scaledSpeed(r[1] + EDGE_BAND_PX - y)
                y >= r[3] - EDGE_BAND_PX -> scaledSpeed(y - (r[3] - EDGE_BAND_PX))
                else -> 0
            }
            if (dy == 0) { autoScrolling = false; return }
            runCatching { v.scrollBy(0, dy) }
            mainHandler.postDelayed(this, 16)
        }
    }

    /** Depth into the edge band -> scroll speed, so it accelerates toward the very edge. */
    private fun scaledSpeed(depthPx: Int): Int =
        (depthPx * MAX_SCROLL_PX / EDGE_BAND_PX).coerceIn(6, MAX_SCROLL_PX)

    fun install(
        cl: ClassLoader,
        folderUiEnabled: Boolean,
        drawerDragSlop: Int = 40,
        folderDragSlop: Int = 30,
        groupZonePct: Int = 36,
    ) {
        classLoader = cl
        folderUi = folderUiEnabled
        drawerDragSlopPx = drawerDragSlop.coerceIn(1, 400)
        folderDragSlopPx = folderDragSlop.coerceIn(1, 400)
        groupZoneHalf = (groupZonePct.coerceIn(0, 100)) / 200f   // pct is the full centre width
        hookMenuSuppression(cl)
        hookDragTracking(cl)
        hookMoveTracking(cl)
        hookKeepOpen(cl)
        hookFolderClose(cl)
        if (folderUi) hookTaskbarDrawerLongPress(cl)
        if (folderUi) DrawerFolderOverlay.installGuards(cl)
        Logx.i("drawer hook: folderUi=$folderUi")
    }

    /**
     * Long-press handling for the EXTERNAL taskbar all-apps drawer. That drawer is not the internal
     * Launcher — it is a taskbar overlay whose long-press goes through
     * TaskbarDragController.startDragOnLongClick, which in ZUI's DP mode does NOT start a drag but
     * calls TaskbarPopupController.showForIconDp(view) to open the app popup (App info / Uninstall …).
     *
     * Two problems it fixes, both rooted in that DP path skipping resetIconScale()/clearPressedBackground()
     * whenever showForIconDp returns null (which leaves the icon visibly shrunk with no menu):
     *   - Our synthetic folder AppInfo is a sentinel component, not a real app, so showForIconDp finds
     *     no shortcuts and returns null → folder icon stuck shrunk, no menu. Folders have no app menu,
     *     so we just reset the scale and consume the long-press.
     *   - For a real app we EXPLICITLY drive showForIconDp (the same DP popup the platform uses — App
     *     info / Uninstall), so the menu reliably appears and the icon scale is reset. If that call
     *     doesn't produce a popup we leave the event for the native path rather than swallow it.
     */
    private fun hookTaskbarDrawerLongPress(cl: ClassLoader) {
        // Long-press on an app INSIDE our folder overlay on the external display: reuse ZUI's own DP
        // popup (App info / "앱 제거"/Uninstall) — exactly like we reuse the native Folder. Verified on
        // device: TaskbarPopupController.showForIconDp(view) renders into the view's own context drag
        // layer (our overlay window for a folder item), so the popup appears anchored over the folder,
        // the folder stays open, and its buttons work natively. We just need to (a) hold a
        // TaskbarPopupController instance and (b) fire showForIconDp at a REAL long-press.
        //
        // The two input paths differ for a folder item:
        //   MOUSE / touchpad -> onLongClickWithMouseOrTouchPad -> TaskbarDragController.startDragOnLongClick
        //     (fires at the long-press timeout). We intercept it, show the popup, and consume.
        //   TOUCH -> BubbleTextView.onTouchEvent -> O() -> setStayPressed(true) (press-DOWN feedback);
        //     it never reaches startDragOnLongClick/showForIcon. We turn that press feedback into a real
        //     long-press with a timer (cancelled on setStayPressed(false) = release / move-out).
        runCatching {
            // Cache a TaskbarPopupController instance whenever one shows a DP popup (top-level items do).
            val tpc = XposedHelpers.findClass("com.android.launcher3.taskbar.TaskbarPopupController", cl)
            XposedBridge.hookAllMethods(tpc, "showForIconDp", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("cache popup controller") { cachedPopupController = param.thisObject }
                }
            })
        }
        runCatching {
            val btv = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", cl)
            XposedBridge.hookAllMethods(btv, "setStayPressed", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("folder-item touch long-press") {
                        val v = param.thisObject as? View ?: return@guard
                        if (param.args?.getOrNull(0) == true) {
                            if (isFolderItemOnExternal(v)) scheduleFolderItemLongPress(v)
                        } else {
                            cancelFolderItemLongPress()
                        }
                    }
                }
            })
            Logx.i("drawer hook: in-folder touch long-press installed")
        }.onFailure { Logx.e("drawer hook: in-folder touch long-press install failed", it) }
        runCatching {
            val tdc = XposedHelpers.findClass("com.android.launcher3.taskbar.TaskbarDragController", cl)
            XposedBridge.hookAllMethods(tdc, "startDragOnLongClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("taskbar long-press:before") {
                        cachePopupControllerFrom(param.thisObject)
                        val view = param.args?.getOrNull(0) as? View ?: return@guard
                        // Mouse long-press on a folder item: show the native DP popup ourselves and
                        // consume, so native's normal-drag branch never runs.
                        if (isFolderItemOnExternal(view)) {
                            cancelFolderItemLongPress()   // the touch timer (if any raced) is superseded
                            showFolderItemPopup(view)
                            param.setResult(true)
                            return@guard
                        }
                        // Our synthetic folder icon (not a real app): reset + consume.
                        if (view.tag?.javaClass?.simpleName == "AppInfo" && compOf(view)?.packageName == DrawerFolderRender.SENTINEL_PKG) {
                            forceResetPress(view)
                            param.setResult(true)
                            Logx.i("taskbar folder long-press: reset + consumed for '${textOf(view)}'")
                        }
                    }
                }

                // Top-level real apps go through ZUI natively (showForIconDp). For a system app the
                // popup is empty and native's null-path skips resetIconScale(), leaving the icon shrunk;
                // reset it (DP mode only, so an internal taskbar drag is untouched).
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("taskbar long-press:after") {
                        val view = drawerAppView(param) ?: return@guard
                        if (compOf(view)?.packageName == DrawerFolderRender.SENTINEL_PKG) return@guard
                        if (!isTaskbarDpMode(param.thisObject)) return@guard
                        forceResetPress(view)
                        mainHandler.postDelayed({ Logx.guard("lp reset delayed") { forceResetPress(view) } }, 250)
                    }
                }
            })
            Logx.i("drawer hook: taskbar drawer long-press handler installed")
        }.onFailure { Logx.e("drawer hook: taskbar drawer long-press install failed", it) }
    }

    /** The long-pressed view if it is an all-apps drawer icon (AppInfo tag), else null. */
    private fun drawerAppView(param: XC_MethodHook.MethodHookParam): View? {
        val view = param.args?.getOrNull(0) as? View ?: return null
        val tag = runCatching { view.tag }.getOrNull() ?: return null
        return if (tag.javaClass.simpleName == "AppInfo") view else null
    }

    private fun compOf(view: View): ComponentName? =
        runCatching { XposedHelpers.getObjectField(view.tag, "componentName") as? ComponentName }.getOrNull()

    /**
     * Force a BubbleTextView out of the pressed/shrunk state. Verified on device: the press scale lives
     * in the FastBitmapDrawable's scale field (animated to 0.8 on press), and the native DP null-path
     * leaves it stuck. Clear stay-pressed + pressed, reset the icon scale (cancels the animator, sets
     * scale=1.0), pin the drawable's scale field to 1.0 directly as a backstop, drop any view-level
     * scale, then refresh the drawable state so nothing re-drives it.
     */
    private fun forceResetPress(view: View) {
        runCatching { XposedHelpers.callMethod(view, "setStayPressed", false) }
        runCatching { XposedHelpers.callMethod(view, "setPressed", false) }
        runCatching { XposedHelpers.callMethod(view, "clearPressedBackground") }
        runCatching { XposedHelpers.callMethod(view, "resetIconScale") }
        runCatching {
            val fbd = XposedHelpers.callMethod(view, "getIcon") ?: return@runCatching
            runCatching { XposedHelpers.callMethod(fbd, "resetScale") }
            forEachInstanceFloat(fbd) { f ->
                if (!f.name.contains("lpha", true)) runCatching { if (f.getFloat(fbd) != 1.0f) f.setFloat(fbd, 1.0f) }
            }
        }
        runCatching { if (view.scaleX != 1.0f) view.scaleX = 1.0f }
        runCatching { if (view.scaleY != 1.0f) view.scaleY = 1.0f }
        runCatching { XposedHelpers.callMethod(view, "refreshDrawableState") }
        runCatching { view.invalidate() }
    }

    /** Reset the FastBitmapDrawable scale (cancel the animator, set to 1.0) on every drawer icon in
     *  [grid] — used at reorder END so a hovered neighbour's un-reset drag-over "accept" scale can't
     *  stay shrunk once the live pin releases. Folder icons are pinned to 1.0 anyway, so no conflict.
     *  Returns how many icons were found shrunk (< 0.99) before the reset. */
    private fun resetGridIconScales(grid: View?): Int {
        val vg = grid as? ViewGroup ?: return 0
        var checked = 0
        var shrunk = 0
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i) ?: continue
            if (runCatching { c.tag }.getOrNull()?.javaClass?.simpleName != "AppInfo") continue
            checked++
            if (iconScaleOf(c)?.let { it < 0.99f } == true) shrunk++
            forceResetPress(c)
        }
        Logx.i("reorder-end scale reset: checked $checked drawer icons, reset $shrunk shrunk to 1.0")
        return shrunk
    }

    /** The FastBitmapDrawable scale (its smallest non-alpha float field) of an icon view, or null. */
    private fun iconScaleOf(view: View): Float? {
        val fbd = runCatching { XposedHelpers.callMethod(view, "getIcon") }.getOrNull() ?: return null
        var min: Float? = null
        forEachInstanceFloat(fbd) { f ->
            if (!f.name.contains("lpha", true) && !f.name.contains("isabled", true)) {
                val v = runCatching { f.getFloat(fbd) }.getOrNull()
                if (v != null && (min == null || v < min!!)) min = v
            }
        }
        return min
    }

    /** Diagnostic: log any drawer icon in [grid] whose FastBitmapDrawable scale field is < 0.99 (i.e.
     *  left shrunk). Proves the stuck-neighbour bug pre-reset and its absence post-reset. */
    private fun logGridIconScales(tag: String, grid: View?) {
        val vg = grid as? ViewGroup ?: return
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i) ?: continue
            if (runCatching { c.tag }.getOrNull()?.javaClass?.simpleName != "AppInfo") continue
            val fbd = runCatching { XposedHelpers.callMethod(c, "getIcon") }.getOrNull() ?: continue
            forEachInstanceFloat(fbd) { f ->
                if (!f.name.contains("lpha", true) && !f.name.contains("isabled", true)) {
                    val v = runCatching { f.getFloat(fbd) }.getOrDefault(1f)
                    if (v < 0.99f) Logx.i("$tag: icon '${textOf(c)}' scale ${f.name}=${"%.3f".format(v)} (SHRUNK)")
                }
            }
        }
    }

    /** A folder item (WorkspaceItemInfo in a Folder) hosted by the external overlay (no real Launcher). */
    private fun isFolderItemOnExternal(v: View): Boolean = isFolderItemIcon(v) && !contextIsLauncher(v)

    private fun contextIsLauncher(v: View): Boolean = runCatching {
        val cl = classLoader ?: return false
        val actxCls = XposedHelpers.findClass("com.android.launcher3.views.ActivityContext", cl)
        val ctx = XposedHelpers.callStaticMethod(actxCls, "lookupContext", v.context) ?: return false
        XposedHelpers.findClass("com.android.launcher3.Launcher", cl).isInstance(ctx)
    }.getOrDefault(false)

    // A TaskbarPopupController instance (they are shared across the taskbar), and the pending touch
    // long-press timer + a debounce so the touch and mouse triggers can't double-show a popup.
    @Volatile private var cachedPopupController: Any? = null
    @Volatile private var pendingLongPressView: View? = null
    @Volatile private var lastPopupView = 0
    @Volatile private var lastPopupAt = 0L

    private val longPressRunnable = Runnable {
        Logx.guard("folder-item long-press fire") {
            val v = pendingLongPressView
            pendingLongPressView = null
            if (v != null) showFolderItemPopup(v)
        }
    }

    /** Arm the touch long-press timer for a folder item (fires the popup if the press is still held). */
    private fun scheduleFolderItemLongPress(v: View) {
        if (pendingLongPressView === v) return
        mainHandler.removeCallbacks(longPressRunnable)
        pendingLongPressView = v
        mainHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
    }

    /** Cancel the pending touch long-press (the press was released or turned into a move). */
    private fun cancelFolderItemLongPress() {
        if (pendingLongPressView == null) return
        mainHandler.removeCallbacks(longPressRunnable)
        pendingLongPressView = null
    }

    private fun cachePopupControllerFrom(taskbarDragController: Any) {
        if (cachedPopupController != null) return
        runCatching {
            var controllers: Any? = null
            var cls: Class<*>? = taskbarDragController.javaClass
            while (cls != null && controllers == null) {
                for (f in cls.declaredFields) if (f.type.simpleName == "TaskbarControllers") {
                    f.isAccessible = true; controllers = f.get(taskbarDragController); if (controllers != null) break
                }
                cls = cls.superclass
            }
            cachedPopupController = controllers?.let { XposedHelpers.getObjectField(it, "taskbarPopupController") }
        }
    }

    /**
     * Show ZUI's native DP popup (App info / "앱 제거") for a folder item and reset the press feedback.
     * showForIconDp renders into the item's own context drag layer (our overlay), anchored to the icon,
     * without closing the folder — verified on device. Debounced so the touch + mouse triggers of one
     * long-press don't stack; native also returns null (a no-op) if a popup is already open.
     */
    private fun showFolderItemPopup(view: View) {
        val id = System.identityHashCode(view)
        if (id == lastPopupView && SystemClock.uptimeMillis() - lastPopupAt < 800) return
        lastPopupView = id
        lastPopupAt = SystemClock.uptimeMillis()
        val pc = cachedPopupController
        val shown = pc != null && runCatching { XposedHelpers.callMethod(pc, "showForIconDp", view) != null }.getOrDefault(false)
        Logx.i("in-folder popup: '${textOf(view)}' shown=$shown")
        // Native resets the icon after showForIconDp (and it's needed on the null path so a system app
        // in a folder isn't left shrunk); mirror that.
        forceResetPress(view)
    }

    private inline fun forEachInstanceFloat(obj: Any, body: (java.lang.reflect.Field) -> Unit) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type == java.lang.Float.TYPE && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true; body(f)
                }
            }
            cls = cls.superclass
        }
    }

    /** True when the given TaskbarDragController's context is in ZUI DP (external desktop) mode. */
    private fun isTaskbarDpMode(taskbarDragController: Any): Boolean = runCatching {
        val activity = XposedHelpers.getObjectField(taskbarDragController, "mActivity") ?: return false
        XposedHelpers.callMethod(activity, "isInDpMode") as? Boolean ?: false
    }.getOrDefault(false)

    // -------------------------------------------------- 1) suppress the long-press menu

    private fun hookMenuSuppression(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(POPUP, cl)
            val n = XposedBridge.hookAllMethods(cls, "showForIcon", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("showForIcon:suppress") {
                        val icon = param.args?.getOrNull(0) as? View ?: return@guard
                        // We deliberately re-show the menu on a folder no-move release; let it pass.
                        if (allowMenuOnce) { allowMenuOnce = false; return@guard }
                        if (!isDrawerIcon(icon) && !isFolderItemIcon(icon)) return@guard
                        param.setResult(null)
                        Logx.i("long-press: menu suppressed for '${textOf(icon)}'")
                    }
                }
            }).size
            Logx.i("drawer hook: menu suppression on showForIcon ($n overload(s))")
        } catch (t: Throwable) {
            Logx.e("drawer hook: menu suppression install failed", t)
        }
    }

    // ------------------------------------------ 2) track when a drawer drag is in flight

    private fun hookDragTracking(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(LAUNCHER_DRAG_CONTROLLER, cl)
            XposedBridge.hookAllMethods(cls, "startDrag", object : XC_MethodHook() {
                // MUST be before: startDrag synchronously calls onDragStart -> goToState.
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("startDrag:track") {
                        when {
                            isDrawerDrag(param.args) -> {
                                drawerDragActive = true
                                handedOff = false
                                dragEpoch++
                                stashStateManager = null
                                stashGoToState = null
                                stashSpringArgs = null
                                drawerView = captureDrawerView(param.args)
                                draggedIcon = captureDraggedIcon(param.args)
                                // Capture the dragged app's AppInfo tag NOW: its grid view is a
                                // RecyclerView item that gets recycled/rebound during the reflow, so by
                                // drop time `draggedIcon.tag` points at a DIFFERENT item (seen: a folder
                                // sentinel) — which made maybeOpenFolder treat the drop as folder-onto-
                                // folder and silently add nothing. The AppInfo object itself is stable.
                                draggedTag = runCatching { draggedIcon?.tag }.getOrNull()
                                sourceLabel = draggedIcon?.let { textOf(it) } ?: "-"
                                moveLogTick = 0
                                // Start a live reorder if drawer-ordering is on (beginReorder self-gates).
                                reorderBegan = false
                                groupCandidate = null
                                draggedSlotKey = null
                                drawerDragMoved = false
                                drawerBaselineSet = false
                                val tag = runCatching { draggedIcon?.tag }.getOrNull()
                                if (tag != null && DrawerFolderRender.beginReorder(tag)) {
                                    reorderBegan = true
                                    draggedSlotKey = slotKeyOf(tag)
                                    ensureItemAnimator(drawerView)
                                }
                                Logx.i("drawer drag: ACTIVE '$sourceLabel' reorder=$reorderBegan; grid=${boundsOf(drawerView)}")
                            }
                            isFolderDrag(param.args) -> {
                                folderItemDragActive = true
                                dragEpoch++
                                folderDragMoved = false
                                folderDragLeftBounds = false
                                // Baseline is set from the FIRST real move event, not from these
                                // args (which are the drag-view registration point, not the touch).
                                folderDragBaselineSet = false
                                folderShowMenuOnEnd = false
                                folderDragIcon = param.args?.getOrNull(2) as? View
                                Logx.i("folder-item drag: ACTIVE")
                            }
                        }
                    }
                }
            })
            Logx.i("drawer hook: drag tracking on $LAUNCHER_DRAG_CONTROLLER.startDrag")
        } catch (t: Throwable) {
            Logx.e("drawer hook: startDrag track install failed", t)
        }
        // EXTERNAL drawer (SecondaryDisplayLauncher): its SecondaryDragController has its OWN startDrag,
        // so the hook above never fires there. Track the external drag on our isolated `secondary*`
        // fields and, critically, RESET the shared drawer state so a stale drawerDragActive/draggedIcon
        // from a prior internal drag can't make the inherited callOnDragEnd group the wrong target.
        try {
            val cls = XposedHelpers.findClass(SECONDARY_DRAG_CONTROLLER, cl)
            XposedBridge.hookAllMethods(cls, "startDrag", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("secondary startDrag:track") {
                        resetDrawerDragState()   // clear any stale shared state; external drag is isolated
                        secondaryDraggedIcon = captureDraggedIcon(param.args)
                        secondaryGrid = captureDrawerView(param.args)
                        secondaryLastX = 0; secondaryLastY = 0
                        secondaryStartX = 0; secondaryStartY = 0
                        secondaryBaselineSet = false
                        secondaryMoved = false
                        secondaryDragActive = secondaryDraggedIcon != null
                        // Pin every drawer icon's press-scale to 1.0 for the duration of this external
                        // drag, so a hovered/displaced app can't press-scale flicker on the external.
                        DrawerFolderRender.setExternalDragActive(secondaryDragActive)
                        val argTypes = param.args?.joinToString { it?.javaClass?.simpleName ?: "null" } ?: "-"
                        Logx.i("secondary drag: ACTIVE src='${secondaryDraggedIcon?.let { textOf(it) } ?: "-"}' " +
                            "grid=${boundsOf(secondaryGrid)} active=$secondaryDragActive args=[$argTypes]")
                    }
                }
            })
            Logx.i("drawer hook: external drag tracking on $SECONDARY_DRAG_CONTROLLER.startDrag")
        } catch (t: Throwable) {
            Logx.e("drawer hook: secondary startDrag track install failed", t)
        }
        try {
            val cls = XposedHelpers.findClass(DRAG_CONTROLLER, cl)
            XposedBridge.hookAllMethods(cls, "callOnDragEnd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("callOnDragEnd:track") {
                        // EXTERNAL drag ends here too (SecondaryDragController inherits this method).
                        // Handle it on its own isolated path and return — never fall through to the
                        // Launcher-only drawer drag-end logic (handoff/keep-open/reorder).
                        val isSecondaryCtl = param.thisObject?.javaClass?.name
                            ?.endsWith("secondarydisplay.SecondaryDragController") == true
                        if (secondaryDragActive || isSecondaryCtl) {
                            handleSecondaryDrop()
                            secondaryDragActive = false
                            secondaryDraggedIcon = null
                            secondaryGrid = null
                            secondaryMoved = false
                            secondaryBaselineSet = false
                            DrawerFolderRender.setExternalDragActive(false)   // release the press-scale pin
                            return@guard
                        }
                        if (folderItemDragActive) {
                            // Persist an in-folder reorder (moved, not dragged out) so the order survives
                            // closing the overlay. Read after the native reorder settles.
                            if (folderDragMoved && !folderDragLeftBounds) {
                                val gid = DrawerFolderOverlay.openGroupId()
                                mainHandler.postDelayed({
                                    val order = DrawerFolderOverlay.currentOrderComponents()
                                    if (gid >= 0 && order != null && order.size >= 2) {
                                        FolderStore.setMembers(gid, order)
                                        DrawerFolderRender.refresh()
                                        Logx.i("folder reorder persisted: group $gid -> ${order.joinToString()}")
                                    }
                                }, 250)
                            }
                            if (folderShowMenuOnEnd) {
                                val icon = folderDragIcon
                                folderShowMenuOnEnd = false
                                if (icon != null) mainHandler.post { showFolderItemMenu(icon) }
                            }
                            val leftBounds = folderDragLeftBounds
                            val epoch = dragEpoch
                            mainHandler.postDelayed({
                                if (epoch == dragEpoch) {
                                    folderItemDragActive = false
                                    // App was pulled out of the folder: repaint now, so it appears
                                    // in the drawer only after the held drag has ended.
                                    if (leftBounds) DrawerFolderRender.refresh()
                                    Logx.i("folder-item drag: END")
                                }
                            }, 400)
                        }
                        if (!drawerDragActive) return@guard
                        // Drop inside the drawer (not handed off to home).
                        if (!handedOff) {
                            when {
                                // Tap-hold (no move past slop): show the app menu, not a reorder.
                                !drawerDragMoved -> {
                                    if (reorderBegan) DrawerFolderRender.cancelReorder()
                                    val icon = draggedIcon
                                    val slot = icon?.let { slotKeyOf(it.tag) }
                                    if (icon != null && slot?.startsWith("${DrawerFolderRender.SENTINEL_PKG}/") != true) {
                                        mainHandler.post { showDrawerItemMenu(icon) }
                                    }
                                }
                                // Live reorder in flight: a centre-zone hover groups; otherwise commit
                                // the reflowed order.
                                reorderBegan -> {
                                    // Decide at RELEASE:
                                    //  - released over a FOLDER (app drag, any part of it) -> JOIN that
                                    //    folder. Hit-tested at its CURRENT position, so it works for a
                                    //    non-adjacent folder and regardless of how the reflow moved it.
                                    //  - else the live centre candidate (app+app group, or an app still
                                    //    hovering an icon centre at release).
                                    //  - else commit the reorder — releasing BEYOND a folder (over an
                                    //    app slot on its far side) is how you move an app PAST it.
                                    val draggedIsApp = draggedSlotKey?.startsWith("${DrawerFolderRender.SENTINEL_PKG}/") != true
                                    val releaseFolder = if (draggedIsApp)
                                        drawerView?.let { folderUnderPoint(it, lastPointerX, lastPointerY, draggedIcon) } else null
                                    val gc = releaseFolder ?: groupCandidate
                                    if (gc != null) {
                                        DrawerFolderRender.cancelReorder()
                                        Logx.i("drawer drop: group '$sourceLabel' onto '${textOf(gc)}' " +
                                            "(${if (releaseFolder != null) "release-folder" else "hover"})")
                                        maybeOpenFolder(gc)
                                    } else {
                                        DrawerFolderRender.commitReorder()
                                        Logx.i("drawer drop: reorder committed (released beyond any folder)")
                                    }
                                }
                                // Reorder off: keep the original drop-onto-icon grouping.
                                else -> {
                                    val v = drawerView
                                    val target = if (v != null) hitTestAppIcon(v, lastPointerX, lastPointerY, draggedIcon) else null
                                    if (target != null) {
                                        Logx.i("GROUP candidate: drop '$sourceLabel' onto '${textOf(target)}'")
                                        maybeOpenFolder(target)
                                    }
                                }
                            }
                            // A live reorder applies the launcher's drag-over "accept" scale (the
                            // FastBitmapDrawable scale field) to the HOVERED NEIGHBOUR. Our flicker pin
                            // forces 1.0 only WHILE the drag is live, so once it releases at drop the
                            // neighbour is left with its un-reset shrunk scale — stuck small until the
                            // next drag. Reset every grid icon's scale AFTER the drop settles (the pin is
                            // already off by now: commit/cancelReorder cleared reorderActive above).
                            if (drawerDragMoved) {
                                val g = drawerView
                                mainHandler.postDelayed({
                                    Logx.guard("reorder-end scale reset") {
                                        logGridIconScales("post-drop", g)   // any SHRUNK neighbour (pre-reset)
                                        resetGridIconScales(g)              // logs "checked N, reset M"
                                        logGridIconScales("post-reset", g)  // should be empty
                                    }
                                }, 250)
                            }
                        } else if (reorderBegan) {
                            // Dragged out to home: abandon the reorder, restore the persisted order.
                            DrawerFolderRender.cancelReorder()
                        }
                        reorderBegan = false
                        groupCandidate = null
                        // Defer the clear so the post-drop NORMAL (which fires around drag end)
                        // is still suppressed and the launcher returns to the drawer — unless we
                        // already handed off, in which case native placement should proceed.
                        val epoch = dragEpoch
                        mainHandler.postDelayed({
                            if (epoch == dragEpoch) {
                                drawerDragActive = false
                                drawerView = null
                                Logx.i("drawer drag: END (flag cleared)")
                            }
                        }, 400)
                    }
                }
            })
            Logx.i("drawer hook: drag-end tracking on $DRAG_CONTROLLER.callOnDragEnd")
        } catch (t: Throwable) {
            Logx.e("drawer hook: callOnDragEnd track install failed", t)
        }
    }

    // --------------------------------- 3) watch the pointer; hand off when it leaves drawer

    private fun hookMoveTracking(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(DRAG_CONTROLLER, cl)
            val n = XposedBridge.hookAllMethods(cls, "handleMoveEvent", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("handleMoveEvent:boundary") {
                        val x = (param.args?.getOrNull(0) as? Int) ?: return@guard
                        val y = (param.args?.getOrNull(1) as? Int) ?: return@guard
                        // EXTERNAL drag (SecondaryDragController inherits handleMoveEvent): track the
                        // live pointer + a moved gate on our isolated fields, so the drop hit-test uses
                        // the real release point. Independent of the Launcher `drawerDragActive` path.
                        if (secondaryDragActive) {
                            secondaryLastX = x
                            secondaryLastY = y
                            if (!secondaryBaselineSet) {
                                secondaryStartX = x; secondaryStartY = y; secondaryBaselineSet = true
                            } else if (!secondaryMoved) {
                                val sdx = x - secondaryStartX; val sdy = y - secondaryStartY
                                if (sdx * sdx + sdy * sdy > drawerDragSlopPx * drawerDragSlopPx) secondaryMoved = true
                            }
                        }
                        // Folder-item drag: baseline from the first move event's real touch point,
                        // then mark "moved" once the pointer leaves the slop (a tap-hold stays put).
                        if (folderItemDragActive) {
                            lastPointerX = x
                            lastPointerY = y
                            if (!folderDragBaselineSet) {
                                folderDragStartX = x
                                folderDragStartY = y
                                folderDragBaselineSet = true
                            } else if (!folderDragMoved) {
                                val dx = x - folderDragStartX
                                val dy = y - folderDragStartY
                                if (dx * dx + dy * dy > folderDragSlopPx * folderDragSlopPx) {
                                    folderDragMoved = true
                                    Logx.i("folder-item drag: moved (reorder)")
                                }
                            }
                            // Dragged out of the folder panel -> let the folder close and pull the
                            // app out of the group (it returns to the drawer).
                            if (folderDragMoved && !folderDragLeftBounds && !DrawerFolderOverlay.folderContains(x, y)) {
                                folderDragLeftBounds = true
                                onFolderDragLeft()
                            }
                        }
                        if (!drawerDragActive || handedOff) return@guard
                        lastPointerX = x
                        lastPointerY = y
                        if (!drawerBaselineSet) {
                            drawerStartX = x; drawerStartY = y; drawerBaselineSet = true
                        } else if (!drawerDragMoved) {
                            val ddx = x - drawerStartX; val ddy = y - drawerStartY
                            if (ddx * ddx + ddy * ddy > drawerDragSlopPx * drawerDragSlopPx) drawerDragMoved = true
                        }
                        val v = drawerView ?: return@guard
                        val r = screenRect(v) ?: return@guard
                        val inside = x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
                        if ((moveLogTick++ % 15) == 0) {
                            Logx.i("drawer drag: move ($x,$y) grid=[${r[0]},${r[1]}-${r[2]},${r[3]}] inside=$inside")
                        }
                        if (!inside) { handOff(x, y, r); return@guard }
                        // Inside the grid: live reorder (reflow) based on the app under the pointer —
                        // only once the drag has actually moved past the slop (a tap-hold stays put).
                        if (reorderBegan && drawerDragMoved) handleReorderMove(v, x, y)
                        // Inside the grid: kick off edge auto-scroll when near top/bottom.
                        val inBand = y <= r[1] + EDGE_BAND_PX || y >= r[3] - EDGE_BAND_PX
                        if (inBand && !autoScrolling) {
                            autoScrolling = true
                            mainHandler.post(autoScrollRunnable)
                        }
                    }
                }
            }).size
            Logx.i("drawer hook: move tracking on $DRAG_CONTROLLER.handleMoveEvent ($n overload(s))")
        } catch (t: Throwable) {
            Logx.e("drawer hook: handleMoveEvent track install failed", t)
        }
    }

    /** Pointer left the drawer: replay the suppressed SpringLoaded, then let native take over. */
    private fun handOff(x: Int, y: Int, r: IntArray) {
        handedOff = true
        val sm = stashStateManager
        val m = stashGoToState
        val args = stashSpringArgs
        if (sm != null && m != null) {
            try {
                XposedBridge.invokeOriginalMethod(m, sm, args)
                Logx.i("drawer drag: HANDOFF at ($x,$y) outside [${r[0]},${r[1]}-${r[2]},${r[3]}] -> home placement")
            } catch (t: Throwable) {
                Logx.e("drawer drag: handoff goToState replay failed", t)
            }
        } else {
            // No stashed transition — this is a drag we handed over from the folder overlay (native
            // never started a SpringLoaded for it). Drive the workspace reveal now, as the pointer
            // leaves the drawer grid, so the app drops on the home screen.
            Logx.i("drawer drag: HANDOFF at ($x,$y) (folder-origin; revealing workspace)")
            revealWorkspaceForDragOut()
        }
    }

    // --------------------------------------- 4) keep the drawer open during a drawer drag

    private fun hookKeepOpen(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(STATE_MANAGER, cl)
            val n = XposedBridge.hookAllMethods(cls, "goToState", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("goToState:keepOpen") {
                        val drawerActive = drawerDragActive && !handedOff
                        // A folder-item drag keeps the folder open only until the item LEAVES the
                        // folder; after that we must let transitions through so the app can be dropped
                        // on the home screen (bug 2).
                        val folderActive = folderItemDragActive && !folderDragLeftBounds
                        val target = param.args?.getOrNull(0) ?: return@guard
                        val name = runCatching { target.toString() }.getOrNull().orEmpty()
                        val isSpring = name.contains("SpringLoaded", ignoreCase = true)
                        val isNormal = name.contains("Normal", ignoreCase = true)
                        // Remember a real SpringLoaded state object for the explicit folder-drag-out
                        // transition, whether or not we suppress this particular call.
                        if (isSpring) springLoadedState = target
                        if (!drawerActive && !folderActive) return@guard
                        if (!isSpring && !isNormal) return@guard
                        // Stash the SpringLoaded call once (drawer handoff only).
                        if (drawerActive && isSpring && stashGoToState == null) {
                            stashStateManager = param.thisObject
                            stashGoToState = param.method
                            stashSpringArgs = param.args?.clone()
                        }
                        param.setResult(null)
                        Logx.i("suppressed goToState($name) -> ${if (drawerActive) "drawer" else "folder"} stays open")
                    }
                }
            }).size
            Logx.i("drawer hook: keep-open on $STATE_MANAGER.goToState ($n overload(s))")
        } catch (t: Throwable) {
            Logx.e("drawer hook: keep-open install failed", t)
        }
    }

    // ------------------------------------ 5) keep our folder open during an internal item drag

    private fun hookFolderClose(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(ABSTRACT_FLOATING, cl)
            val n = XposedBridge.hookAllMethods(cls, "close", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("AbstractFloatingView.close:suppress") {
                        // Keep the folder open only while the drag stays inside it; once it leaves,
                        // allow the close so the app can be pulled back out to the drawer.
                        if (!folderItemDragActive || folderDragLeftBounds) return@guard
                        // Suppress the WHOLE close (not just handleClose): AbstractFloatingView.close
                        // also tears down the folder's modal touch interception before handleClose,
                        // which left a visible-but-non-modal "zombie" folder whose outside taps
                        // leaked to the drawer icons behind it. Only our folder, only during a
                        // folder-item drag.
                        if (param.thisObject?.javaClass?.simpleName != "Folder") return@guard
                        param.setResult(null)
                        Logx.i("folder-item drag: suppressed AbstractFloatingView.close -> folder stays modal")
                    }
                }

                // When a folder actually closes (e.g. an in-folder tap launched its app), drop any
                // pending touch long-press timer. Otherwise it could fire ~400ms later and call
                // showForIconDp on a torn-down folder — a no-op at best, a leftover popup at worst.
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("folder close: cancel pending long-press") {
                        if (param.thisObject?.javaClass?.simpleName == "Folder") cancelFolderItemLongPress()
                    }
                }
            }).size
            Logx.i("drawer hook: folder-close suppression on $ABSTRACT_FLOATING.close ($n overload(s))")
        } catch (t: Throwable) {
            Logx.e("drawer hook: folder-close install failed", t)
        }
        // Reject the drop on a no-move release, so a tap-hold does NOT merge/reorder (which was
        // colliding our two items and losing one). Instead the item returns to its cell and we
        // show the menu. A real reorder (moved) is accepted normally.
        try {
            val cls = XposedHelpers.findClass(FOLDER, cl)
            XposedBridge.hookAllMethods(cls, "acceptDrop", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("Folder.acceptDrop:noMove") {
                        if (!folderItemDragActive || folderDragMoved) return@guard
                        param.setResult(false)
                        folderShowMenuOnEnd = true
                        Logx.i("folder-item no-move release: drop rejected -> return to cell, menu on end")
                    }
                }
            })
            Logx.i("drawer hook: folder no-move drop rejection on $FOLDER.acceptDrop")
        } catch (t: Throwable) {
            Logx.e("drawer hook: folder acceptDrop install failed", t)
        }
        // NOTE: we used to suppress Folder.onDragOver until the user moved (a workaround for the
        // all-ranks-0 bug, where a tap-hold reflow left items swapped). That also blocked the live
        // reflow that OPENS A GAP between items, so an item could only be dropped at the end, never
        // inserted between two others. Now that items carry distinct ranks (0,1,2,… set in
        // DrawerFolderOverlay.open), the native reflow reorders correctly and the no-move case is
        // handled by acceptDrop returning false — so onDragOver is no longer suppressed.
        // Make in-folder MIDDLE insertion work. Per dex RE, Folder.startDrag seeds mEmptyCellRank
        // (obf field `p`) — the `empty` arg of realTimeReorder — from the dragged item's ItemInfo.rank.
        // Our guard on updateItemLocationsInDatabaseBatch stops ranks from re-syncing to cells, so that
        // rank is stale and realTimeReorder shifts the wrong range: the gap between two items never
        // opens and the item just appends. Re-seed mEmptyCellRank from the dragged view's LIVE cell
        // after startDrag runs (field `j` = mContent FolderPagedView). Harmless for real folders
        // (their rank isn't stale, so the value is unchanged).
        try {
            val cls = XposedHelpers.findClass(FOLDER, cl)
            XposedBridge.hookAllMethods(cls, "startDrag", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("Folder.startDrag:emptyCellRank") {
                        val folder = param.thisObject ?: return@guard
                        val v = param.args?.getOrNull(0) as? View ?: return@guard
                        val lp = v.layoutParams ?: return@guard
                        val cellX = XposedHelpers.callMethod(lp, "getCellX") as? Int ?: return@guard
                        val cellY = XposedHelpers.callMethod(lp, "getCellY") as? Int ?: return@guard
                        val content = XposedHelpers.getObjectField(folder, "j") ?: return@guard
                        val cols = XposedHelpers.callMethod(content, "getCellCountX") as? Int ?: return@guard
                        val page = (XposedHelpers.callMethod(content, "getCurrentPage") as? Int) ?: 0
                        val per = (XposedHelpers.callMethod(content, "itemsPerPage") as? Int) ?: (cols * 100)
                        val liveRank = page * per + cellY * cols + cellX
                        val old = runCatching { XposedHelpers.getIntField(folder, "p") }.getOrNull()
                        XposedHelpers.setObjectField(folder, "p", liveRank)   // Field.set unboxes to int
                        if (old != liveRank) Logx.i("folder reorder: reseeded mEmptyCellRank $old->$liveRank (cell $cellX,$cellY)")
                    }
                }
            })
            Logx.i("drawer hook: Folder.startDrag mEmptyCellRank fix installed")
        } catch (t: Throwable) {
            Logx.e("drawer hook: Folder.startDrag fix install failed", t)
        }
        // Kill the phantom empty cell. Our reused folder allocates a full grid row (grid=4x2,
        // alloc=4) for only 3 items, so findNearestArea can return the empty cell's rank (3); the
        // reorder target then oscillates between the real middle and that phantom and never settles,
        // so a middle insertion fails. Clamp findNearestArea's result to (itemCount-1) during our
        // folder-item drag so only real ranks are reachable.
        try {
            val cls = XposedHelpers.findClass(FOLDER_PAGED_VIEW, cl)
            XposedBridge.hookAllMethods(cls, "findNearestArea", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("findNearestArea:clamp") {
                        if (!folderItemDragActive) return@guard
                        val n = DrawerFolderOverlay.itemCount()
                        if (n <= 0) return@guard
                        val r = param.result as? Int ?: return@guard
                        if (r > n - 1) param.result = n - 1
                    }
                }
            })
            Logx.i("drawer hook: FolderPagedView.findNearestArea clamp installed")
        } catch (t: Throwable) {
            Logx.e("drawer hook: findNearestArea clamp install failed", t)
        }
    }

    /** Re-show the app popup menu for a folder item after a no-move release. */
    private fun showFolderItemMenu(icon: View) {
        val cl = classLoader ?: return
        runCatching {
            val popupCls = XposedHelpers.findClass(POPUP, cl)
            allowMenuOnce = true
            XposedHelpers.callStaticMethod(popupCls, "showForIcon", icon)
            Logx.i("folder-item: menu shown on no-move release")
        }.onFailure { allowMenuOnce = false; Logx.e("folder-item menu show failed", it) }
    }

    /** Show the app popup (app info / uninstall) for a drawer icon on a no-move long-press release. */
    private fun showDrawerItemMenu(icon: View) {
        val cl = classLoader ?: return
        runCatching {
            val popupCls = XposedHelpers.findClass(POPUP, cl)
            allowMenuOnce = true
            XposedHelpers.callStaticMethod(popupCls, "showForIcon", icon)
            Logx.i("drawer: menu shown on no-move release")
        }.onFailure { allowMenuOnce = false; Logx.e("drawer menu show failed", it) }
    }

    // ----------------------------------------------------------------- discriminators

    /** A drag whose source is our folder (startDrag carries a `Folder` argument). */
    private fun isFolderDrag(args: Array<Any?>?): Boolean {
        if (args == null) return false
        for (a in args) if (a != null && a.javaClass.simpleName == "Folder") return true
        return false
    }

    /** An icon that lives inside a Folder (WorkspaceItemInfo tag + a Folder ancestor). */
    private fun isFolderItemIcon(v: View): Boolean {
        val tag = runCatching { v.tag }.getOrNull() ?: return false
        if (tag.javaClass.simpleName != "WorkspaceItemInfo") return false
        return ancestorMatching(v) { it == "Folder" } != null
    }

    private fun isDrawerIcon(v: View): Boolean {
        val tag = runCatching { v.tag }.getOrNull() ?: return false
        if (tag.javaClass.simpleName != "AppInfo") return false
        return surfaceIsAllApps(v)
    }

    private fun isDrawerDrag(args: Array<Any?>?): Boolean {
        if (args == null) return false
        for (a in args) {
            if (a == null) continue
            val n = a.javaClass.simpleName
            if (n.contains("AllApps", ignoreCase = true)) return true
            if (n == "AppInfo") return true
            if (a is View && surfaceIsAllApps(a)) return true
        }
        return false
    }

    /**
     * The boundary rectangle for "still in the app list". The all-apps CONTAINER is full-screen
     * (it paints the blurred wallpaper behind the panel), so it is useless as a boundary. The
     * actual app grid is the AllAppsRecyclerView, reached by walking up from the dragged icon.
     * Leaving the grid (e.g. dragging up into the search bar) is the "left the drawer" signal.
     */
    private fun captureDrawerView(args: Array<Any?>?): View? {
        if (args == null) return null
        // The dragged icon is a View arg carrying an AppInfo tag; its ancestor is the grid.
        for (a in args) {
            if (a is View && runCatching { a.tag }.getOrNull()?.javaClass?.simpleName == "AppInfo") {
                ancestorMatching(a) { it.contains("RecyclerView", ignoreCase = true) }?.let { return it }
            }
        }
        // Fallbacks: any RecyclerView ancestor of any View arg, else an AllApps container arg.
        for (a in args) {
            if (a is View) ancestorMatching(a) { it.contains("RecyclerView", ignoreCase = true) }?.let { return it }
        }
        for (a in args) {
            if (a is View && a.javaClass.simpleName.contains("AllApps", ignoreCase = true)) return a
        }
        return null
    }

    /** Clear the shared (internal-Launcher) drawer drag state so stale values can't leak into a
     *  later drag we did not start-track (notably an external SecondaryDisplayLauncher drag). */
    private fun resetDrawerDragState() {
        drawerDragActive = false
        handedOff = false
        draggedIcon = null
        draggedTag = null
        drawerView = null
        groupCandidate = null
        reorderBegan = false
        draggedSlotKey = null
        drawerDragMoved = false
        drawerBaselineSet = false
    }

    /**
     * Resolve the drop target for an EXTERNAL (SecondaryDisplayLauncher) drag from its own tracked
     * state and group into it — using the FRESH release point and the FRESH dragged icon, so it can
     * never mis-target from stale internal-drag state (the reported "dropped on 설정 not Misc" bug).
     */
    /**
     * Resolve an EXTERNAL (SecondaryDisplayLauncher) drop by the RELEASE point — the same model as the
     * internal `reorderBegan` branch, which the old external path lacked (it hit-tested only the grid's
     * DIRECT children for a literal "AppInfo" tag, so it missed the folder whenever the external grid
     * nests its icons or a reflowed app overlapped the target — hence non-adjacent folders "never
     * joined"). Now: DEEP-search the on-screen grid for icon views, prefer a FOLDER under the release
     * point (join it, any part), else an app hovered in its centre (app+app group); otherwise leave the
     * native reorder to stand. Heavily logged so ONE real drag pinpoints coords/grid/candidates.
     */
    private fun handleSecondaryDrop() {
        val icon = secondaryDraggedIcon
        var grid = secondaryGrid
        if (!secondaryMoved) {
            Logx.i("secondary drop: tap-hold (no move) -> ignored (icon=${icon != null} grid=${grid != null})")
            return
        }
        val rx = secondaryLastX; val ry = secondaryLastY
        // If the captured grid is missing/childless, re-find the live on-screen all-apps grid.
        if (grid == null || ((grid as? ViewGroup)?.childCount ?: 0) == 0) {
            val refound = icon?.let { findAllAppsGrid(launcherRootFrom(it) ?: it.rootView) }
            Logx.i("secondary drop: grid re-find (captured=${grid != null}) -> ${refound?.javaClass?.simpleName}")
            if (refound != null) grid = refound
        }
        if (icon == null || grid == null) {
            Logx.i("secondary drop: skipped (icon=${icon != null} grid=${grid != null}) release=($rx,$ry)")
            return
        }
        Logx.i("secondary drop: src='${textOf(icon)}' release=($rx,$ry) grid=${grid.javaClass.simpleName} " +
            "gridBounds=${boundsOf(grid)} childCount=${(grid as? ViewGroup)?.childCount ?: -1}")
        val cands = ArrayList<View>()
        collectIconViews(grid, cands)
        var folderHit: View? = null
        var appHit: View? = null
        val loc = IntArray(2)
        for (c in cands) {
            if (c === icon) continue
            c.getLocationOnScreen(loc)
            val w = c.width.coerceAtLeast(1)
            val inside = rx >= loc[0] && rx <= loc[0] + w && ry >= loc[1] && ry <= loc[1] + c.height
            val frac = ((rx - loc[0]).toFloat() / w).coerceIn(0f, 1f)
            val slot = slotKeyOf(runCatching { c.tag }.getOrNull())
            val isFolder = slot?.startsWith("${DrawerFolderRender.SENTINEL_PKG}/") == true
            if (inside) {
                Logx.i("  cand '${textOf(c)}' folder=$isFolder frac=${"%.2f".format(frac)} " +
                    "bounds=[${loc[0]},${loc[1]}-${loc[0] + w},${loc[1] + c.height}] INSIDE")
                if (isFolder) { if (folderHit == null) folderHit = c }
                else if (frac in (0.5f - groupZoneHalf)..(0.5f + groupZoneHalf)) { if (appHit == null) appHit = c }
            }
        }
        val target = folderHit ?: appHit
        // If nothing was under the release point, report the NEAREST folder + its gap — a small gap
        // means a coordinate offset (fixable), a large one means the release point is stale/elsewhere.
        if (target == null) {
            var near: View? = null; var best = Long.MAX_VALUE
            for (c in cands) {
                if (c === icon) continue
                val slot = slotKeyOf(runCatching { c.tag }.getOrNull())
                if (slot?.startsWith("${DrawerFolderRender.SENTINEL_PKG}/") != true) continue
                c.getLocationOnScreen(loc)
                val cx = loc[0] + c.width / 2; val cy = loc[1] + c.height / 2
                val d = (rx - cx).toLong() * (rx - cx) + (ry - cy).toLong() * (ry - cy)
                if (d < best) { best = d; near = c }
            }
            near?.getLocationOnScreen(loc)
            Logx.i("secondary drop: nearest folder='${near?.let { textOf(it) } ?: "none"}' " +
                "centre=(${loc[0] + (near?.width ?: 0) / 2},${loc[1] + (near?.height ?: 0) / 2}) " +
                "dist=${if (best == Long.MAX_VALUE) -1 else Math.sqrt(best.toDouble()).toInt()}px from release=($rx,$ry)")
        }
        Logx.i("secondary drop decision: ${
            when {
                folderHit != null -> "JOIN folder '${textOf(folderHit)}'"
                appHit != null -> "GROUP app '${textOf(appHit)}'"
                else -> "REORDER (no folder/app-centre under release)"
            }
        } (icons=${cands.size})")
        if (target != null) {
            draggedIcon = icon   // maybeOpenFolder reads draggedIcon.context; drawer path is inactive
            draggedTag = runCatching { icon.tag }.getOrNull()   // stable source tag for maybeOpenFolder
            maybeOpenFolder(target)
            draggedIcon = null
            draggedTag = null
        }
    }

    /** Recursively collect every icon view (one whose tag carries a componentName — app OR folder
     *  sentinel), regardless of how the grid nests them. */
    private fun collectIconViews(v: View, out: ArrayList<View>) {
        if (slotKeyOf(runCatching { v.tag }.getOrNull()) != null) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { collectIconViews(it, out) }
    }

    /**
     * A drop of one drawer icon onto another. Cases:
     *   app  + app    -> create a new group of the two
     *   app  + folder -> add the app into that folder (NOT a new merged folder)
     *   folder + folder -> nothing (folders don't nest)
     */
    private fun maybeOpenFolder(target: View) {
        if (!folderUi) return
        val cl = classLoader ?: return
        // Source is the tag captured at drag START (stable), NOT the live draggedIcon.tag (recycled).
        val src = draggedTag ?: runCatching { draggedIcon?.tag }.getOrNull()
        val tgt = runCatching { target.tag }.getOrNull()
        val srcKeyDbg = src?.let { componentKey(it) }
        val tgtKeyDbg = tgt?.let { componentKey(it) }
        Logx.i("maybeOpenFolder: src(icon=${draggedIcon != null} tag=${src?.javaClass?.simpleName} " +
            "key=$srcKeyDbg label='$sourceLabel') tgt(label='${textOf(target)}' tag=${tgt?.javaClass?.simpleName} key=$tgtKeyDbg)")
        if (src == null) { Logx.e("maybeOpenFolder ABORT: draggedIcon tag null (stale/recycled)"); return }
        if (tgt == null) { Logx.e("maybeOpenFolder ABORT: target tag null"); return }
        val ctx = draggedIcon?.context ?: target.context
        val srcKey = componentKey(src) ?: run { Logx.e("maybeOpenFolder ABORT: srcKey null"); return }
        val tgtKey = componentKey(tgt) ?: run { Logx.e("maybeOpenFolder ABORT: tgtKey null"); return }
        if (srcKey == tgtKey) { Logx.e("maybeOpenFolder ABORT: srcKey==tgtKey ($srcKey)"); return }
        runCatching { FolderStore.init(ctx) }
        val srcFolder = DrawerFolderRender.folderGroupIdOf(srcKey)
        val tgtFolder = DrawerFolderRender.folderGroupIdOf(tgtKey)
        when {
            srcFolder != null && tgtFolder != null -> Logx.i("drop folder-onto-folder: ignored")
            tgtFolder != null -> addAppToFolder(ctx, tgtFolder, srcKey, src)
            srcFolder != null -> addAppToFolder(ctx, srcFolder, tgtKey, tgt)
            else -> {
                val gid = runCatching { FolderStore.createGroup("폴더", listOf(tgtKey, srcKey)) }.getOrDefault(-1L)
                DrawerFolderRender.refresh()
                mainHandler.post { DrawerFolderOverlay.open(cl, ctx, listOf(tgt, src), "폴더", gid) }
            }
        }
    }

    /** Add [appKey] (its AppInfo [appTag]) into an existing folder group and open it. */
    private fun addAppToFolder(ctx: android.content.Context, groupId: Long, appKey: String, appTag: Any) {
        val before = FolderStore.listGroups().firstOrNull { it.id == groupId }?.members?.size ?: -1
        runCatching {
            FolderStore.addMember(groupId, appKey)
            val after = FolderStore.listGroups().firstOrNull { it.id == groupId }?.members ?: emptyList()
            Logx.i("addAppToFolder: group=$groupId +'$appKey' members $before->${after.size} contains=${after.contains(appKey)}")
            DrawerFolderRender.refresh()
        }.onFailure { Logx.e("addAppToFolder failed", it) }
        DrawerFolderRender.openGroupPlus(groupId, ctx, appTag)
    }

    private fun componentKey(appInfo: Any): String? = runCatching {
        (XposedHelpers.callMethod(appInfo, "getTargetComponent") as? ComponentName)?.flattenToShortString()
    }.getOrNull()

    /** The dragged folder item left the folder panel: pull it out of the group, back to the drawer. */
    private fun onFolderDragLeft() {
        Logx.i("folder drag-out: left folder at ($lastPointerX,$lastPointerY)")
        val gid = DrawerFolderOverlay.openGroupId()
        if (gid < 0) return
        val tag = runCatching { folderDragIcon?.tag }.getOrNull() ?: return
        val comp = componentKey(tag) ?: return
        runCatching {
            FolderStore.removeMember(gid, comp)
            Logx.i("folder drag-out: removed $comp from group $gid")
        }
        // Hand the still-alive drag to the DRAWER, don't jump straight to home. The app now lives in
        // the drawer context; the existing grid-boundary handoff reveals the home screen only once
        // the pointer ALSO leaves the drawer grid — the overlay -> drawer -> home flow the user
        // expects (revealing home the instant the item left the overlay was too eager).
        // NB: the folder item's drag view is a DETACHED copy, so its rootView is itself (diag showed
        // root=BubbleTextView, recyclers=0). Search the launcher's real drag layer instead.
        val grid = findAllAppsGrid(launcherRootFrom(folderDragIcon) ?: folderDragIcon?.rootView)
        if (grid == null) {
            Logx.e("folder drag-out: all-apps grid not found; falling back to immediate reveal")
            revealWorkspaceForDragOut()
            return
        }
        // Engage the drawer's live REORDER/placement for the dragged-out app, exactly like a native
        // drawer long-press-drag. Just flipping drawerDragActive (the old behaviour) held the floating
        // icon with NO reflow — you couldn't position it until you released and re-grabbed. beginReorder
        // makes the grid open a gap under the pointer (handleReorderMove -> updateReorder) and lets
        // callOnDragEnd commit the drop position. The app is a folder member becoming a drawer item
        // mid-drag: `comp` (getTargetComponent) is its drawer slot key; beginDragOutReorder adds it to
        // the drawer order so the reorder accepts it. Its own grid view is pinned INVISIBLE as the
        // draggedSlot (installReorderHideHook), so refreshing to render it top-level shows no duplicate.
        drawerView = grid
        draggedIcon = null
        draggedTag = tag                 // stable source tag for a release-over-folder JOIN
        sourceLabel = textOf(folderDragIcon ?: grid)
        drawerDragActive = true
        handedOff = false
        reorderBegan = false
        groupCandidate = null
        draggedSlotKey = null
        if (DrawerFolderRender.beginDragOutReorder(comp)) {
            reorderBegan = true
            draggedSlotKey = comp
            // It has already moved (it left the folder), so arm the reflow now: skip the slop baseline
            // and let the very next move event reflow the grid instead of waiting for a fresh gesture.
            drawerDragMoved = true
            drawerBaselineSet = true
            ensureItemAnimator(grid)
            DrawerFolderRender.refresh()
            Logx.i("folder drag-out: engaged drawer reorder for '$sourceLabel' (reflow armed)")
        } else {
            Logx.e("folder drag-out: beginDragOutReorder('$comp') failed; drawer holds icon without reflow")
        }
        Logx.i("folder drag-out: handed to drawer drag; grid=${boundsOf(grid)}")
    }

    /** The launcher's drag layer (root of its live view tree), reached from a view's context. */
    private fun launcherRootFrom(icon: View?): View? {
        val cl = classLoader ?: return null
        icon ?: return null
        return runCatching {
            val actx = XposedHelpers.findClass("com.android.launcher3.views.ActivityContext", cl)
            val launcher = XposedHelpers.callStaticMethod(actx, "lookupContext", icon.context)
            XposedHelpers.callMethod(launcher, "getDragLayer") as? View
        }.getOrNull()
    }

    /**
     * The visible all-apps grid, searched from a root. The class name is R8-obfuscated (not literally
     * "AllAppsRecyclerView"), so match any RecyclerView that lives under an AllApps container — the
     * same signal `captureDrawerView` uses for a normal drawer drag — and pick the largest visible.
     */
    private fun findAllAppsGrid(root: View?): View? {
        val allRecyclers = ArrayList<View>()
        val matches = ArrayList<View>()
        fun rec(v: View?) {
            v ?: return
            val sn = v.javaClass.simpleName
            if (sn.contains("RecyclerView", ignoreCase = true)) {
                allRecyclers.add(v)
                if (sn.contains("AllApps", ignoreCase = true) || surfaceIsAllApps(v)) matches.add(v)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) rec(v.getChildAt(i))
        }
        rec(root)
        val sized = matches.filter { it.width > 0 && it.height > 0 }
        return sized.filter { it.isShown }.maxByOrNull { it.width.toLong() * it.height }
            ?: sized.maxByOrNull { it.width.toLong() * it.height }
            ?: matches.firstOrNull()
    }

    /**
     * Drive the launcher into SpringLoaded (workspace-drag) state so an app dragged out of our
     * folder overlay can be dropped straight onto the home screen — a continuous gesture instead of
     * release + re-long-press. Best-effort: if we cannot reach the state manager or a SpringLoaded
     * state object, it logs and the old two-step behaviour stands.
     */
    private fun revealWorkspaceForDragOut() {
        val cl = classLoader ?: return
        val icon = folderDragIcon ?: return
        runCatching {
            val activityCtxCls = XposedHelpers.findClass("com.android.launcher3.views.ActivityContext", cl)
            val launcher = XposedHelpers.callStaticMethod(activityCtxCls, "lookupContext", icon.context)
            val sm = XposedHelpers.callMethod(launcher, "getStateManager")
            val spring = springLoadedState ?: runCatching {
                XposedHelpers.findClass("com.android.launcher3.LauncherState", cl)
                    .getDeclaredField("SPRING_LOADED").apply { isAccessible = true }.get(null)
            }.getOrNull()
            if (spring == null) {
                Logx.e("folder drag-out: no SpringLoaded state object; workspace not revealed")
                return
            }
            // Suppression is already released (folderDragLeftBounds == true), so this call proceeds.
            XposedHelpers.callMethod(sm, "goToState", spring)
            Logx.i("folder drag-out: goToState(SpringLoaded) -> workspace revealed for home drop")
        }.onFailure { Logx.e("folder drag-out: reveal workspace failed", it) }
    }

    /** The dragged icon: the View arg carrying an AppInfo tag. */
    private fun captureDraggedIcon(args: Array<Any?>?): View? {
        if (args == null) return null
        for (a in args) {
            if (a is View && runCatching { a.tag }.getOrNull()?.javaClass?.simpleName == "AppInfo") return a
        }
        return null
    }

    /**
     * Keep the drawer RecyclerView's ItemAnimator NULL during a reorder (its launcher default). With an
     * animator, our onAppsUpdated full re-diff fades every item — a flicker — because the launcher's
     * diff turns a move into remove+insert. Null gives an instant snap instead of a flicker; smooth
     * per-item slides would need direct notifyItemMoved, not a full rebind.
     */
    private fun ensureItemAnimator(rv: View?) {
        rv ?: return
        runCatching {
            rv.javaClass.methods.firstOrNull { it.name == "setItemAnimator" && it.parameterCount == 1 }?.invoke(rv, null)
        }
    }

    /** Slot key (componentName.flattenToShortString) of an AppInfo tag — matches DrawerFolderRender. */
    private fun slotKeyOf(tag: Any?): String? = runCatching {
        (XposedHelpers.getObjectField(tag, "componentName") as? ComponentName)?.flattenToShortString()
    }.getOrNull()

    /**
     * Live reorder: reflow so the dragged app inserts before/after the app under the pointer, or —
     * when hovering an app's centre zone — mark it a group candidate (drop-to-group, no reflow).
     */
    private fun handleReorderMove(rv: View, x: Int, y: Int) {
        if (!DrawerFolderRender.isReordering()) return
        val loc = IntArray(2)
        rv.getLocationOnScreen(loc)
        val rvx = (x - loc[0]).toFloat()
        val rvy = (y - loc[1]).toFloat()
        val child = runCatching { XposedHelpers.callMethod(rv, "findChildViewUnder", rvx, rvy) as? View }.getOrNull()
            ?: run { groupCandidate = null; return }
        val slot = slotKeyOf(runCatching { child.tag }.getOrNull())
        if (slot == null || slot == draggedSlotKey) { groupCandidate = null; return }
        // Reflow (reorder) unless an APP is hovering an icon's CENTRE band — that stays put as a live
        // group candidate. Crucially, an app in a folder's EDGE zone STILL reflows, so the app can
        // travel PAST a folder to reorder to its far side (moving a folder is not an inescapable group
        // target). A FOLDER drag never groups (folder-onto-folder is a no-op) so it always reflows.
        // Whether a drop actually JOINS a folder is decided at RELEASE by the folder under the release
        // point (see callOnDragEnd) — which also makes a drop onto a NON-ADJACENT folder work regardless
        // of how the reflow moved things.
        val draggedIsApp = draggedSlotKey?.startsWith("${DrawerFolderRender.SENTINEL_PKG}/") != true
        val w = child.width.coerceAtLeast(1)
        val frac = ((rvx - child.left) / w).coerceIn(0f, 1f)
        val groupHover = draggedIsApp && frac in (0.5f - groupZoneHalf)..(0.5f + groupZoneHalf)
        if (reorderMoveLog) {
            val isFolderTgt = slot.startsWith("${DrawerFolderRender.SENTINEL_PKG}/")
            val act = if (groupHover) "GROUP-hover" else if (frac >= 0.5f) "reorder-after" else "reorder-before"
            val key = "${textOf(child)}|$act"
            if (key != lastReorderMoveLog) {
                lastReorderMoveLog = key
                Logx.i("reorderMove: child='${textOf(child)}' folderTgt=$isFolderTgt frac=${"%.2f".format(frac)} -> $act")
            }
        }
        if (groupHover) {
            groupCandidate = child
        } else {
            groupCandidate = null
            DrawerFolderRender.updateReorder(slot, frac >= 0.5f)
        }
    }

    /** The FOLDER icon (sentinel) under a screen point, excluding [exclude], else null. */
    private fun folderUnderPoint(container: View, x: Int, y: Int, exclude: View?): View? {
        val hit = hitTestAppIcon(container, x, y, exclude) ?: return null
        val slot = slotKeyOf(runCatching { hit.tag }.getOrNull()) ?: return null
        return if (slot.startsWith("${DrawerFolderRender.SENTINEL_PKG}/")) hit else null
    }

    @Volatile private var lastReorderMoveLog = ""
    private val reorderMoveLog = true   // diagnostic: trace child-under-pointer + frac during reorder

    /** The app icon (BubbleTextView with AppInfo tag) under a screen point, excluding one view. */
    private fun hitTestAppIcon(container: View, x: Int, y: Int, exclude: View?): View? {
        val vg = container as? ViewGroup ?: return null
        val loc = IntArray(2)
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i) ?: continue
            if (c === exclude) continue
            if (runCatching { c.tag }.getOrNull()?.javaClass?.simpleName != "AppInfo") continue
            c.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + c.width && y >= loc[1] && y <= loc[1] + c.height) return c
        }
        return null
    }

    private fun ancestorMatching(v: View, pred: (String) -> Boolean): View? {
        var p: ViewParent? = v.parent
        var hops = 0
        while (p != null && hops < 16) {
            if (p is View && pred(p.javaClass.simpleName)) return p
            p = p.parent
            hops++
        }
        return null
    }

    private fun surfaceIsAllApps(v: View): Boolean {
        var p: ViewParent? = v.parent
        var hops = 0
        while (p != null && hops < 16) {
            if (p.javaClass.simpleName.contains("AllApps", ignoreCase = true)) return true
            p = p.parent
            hops++
        }
        return false
    }

    /** [left, top, right, bottom] of a view on screen, or null. */
    private fun screenRect(v: View): IntArray? = try {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    } catch (t: Throwable) {
        null
    }

    private fun boundsOf(v: View?): String {
        val r = v?.let { screenRect(it) } ?: return "null"
        return "[${r[0]},${r[1]}-${r[2]},${r[3]}]"
    }

    private fun textOf(v: View): String = runCatching {
        (v.javaClass.getMethod("getText").invoke(v) as? CharSequence)?.toString()
    }.getOrNull() ?: "-"
}
