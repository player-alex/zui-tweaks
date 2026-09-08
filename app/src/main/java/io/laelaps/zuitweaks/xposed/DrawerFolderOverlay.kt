package io.laelaps.zuitweaks.xposed

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.reflect.Modifier

/**
 * Drawer folder overlay that REUSES the launcher's real Folder view (so it looks exactly like a
 * workspace folder — glossy background, title, "앱 추가", native open/close), while keeping the
 * LIFECYCLE ours so none of the workspace coupling leaks:
 *
 *   - built via FolderIcon.inflateFolderAndIcon (the supported creation path) from an in-memory
 *     FolderInfo of the dropped apps;
 *   - positioned at screen centre via the folder's own BaseDragLayer.LayoutParams (x/y);
 *   - the synthetic FolderIcon is removed right after open, so nothing is left behind on the home
 *     screen (the earlier "ghost folder" was that icon);
 *   - a DB guard skips Folder's favorites-DB persistence while we build, so the real launcher
 *     layout is never touched.
 *
 * Runs in com.zui.launcher: pure android.* + XposedBridge (no AndroidX). Everything is caught.
 */
object DrawerFolderOverlay {

    private const val FOLDER = "com.android.launcher3.folder.Folder"
    private const val FOLDER_ICON = "com.android.launcher3.folder.FolderIcon"
    private const val WORKSPACE = "com.android.launcher3.Workspace"
    private const val FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo"
    private const val WORKSPACE_ITEM_INFO = "com.android.launcher3.model.data.WorkspaceItemInfo"
    private const val ACTIVITY_CONTEXT = "com.android.launcher3.views.ActivityContext"

    /** While true the DB guard skips Folder persistence (our transient folder). */
    @Volatile private var guardActive = false
    @Volatile private var guardsInstalled = false

    // The group currently shown in the overlay, so a rename persists to the right group.
    @Volatile private var currentGroupId: Long = -1
    @Volatile private var currentFolderInfo: Any? = null
    @Volatile private var currentFolder: View? = null
    // The synthetic anchor FolderIcon we add to the drag layer to open the folder. Tracked so it is
    // removed even if the open animator throws (it does on a non-Launcher context), otherwise the
    // native close re-shows it as a nameless "ghost" folder in the screen centre.
    @Volatile private var currentFolderIcon: View? = null

    // Set during our open(), read by the j0() positioner hook to re-centre OUR folder (its x/y + pivot)
    // INSIDE animateOpen — before the native reveal animator captures them — so it grows from centre
    // like a real workspace folder instead of the anchor's top-left corner.
    @Volatile private var pendingCenterFolder: View? = null
    @Volatile private var pendingDragLayer: ViewGroup? = null

    // Folder.N as it was before the capture hook widened it, so mPadShowRect is handed back
    // untouched to the pad-mode paths that also read it. Single-valued because the hook is
    // identity-gated to one folder and opens happen on the main thread.
    @Volatile private var savedCaptureRect: RectF? = null

    // While set, the native folder-open reveal (Folder implements ClipPathView; a ShapeDelegate
    // animates setClipPath each frame) is suppressed for THIS folder. Currently unused (we let the
    // native reveal run); kept as a safety valve.
    @Volatile private var suppressRevealFolder: View? = null

    // While set (only around OUR animateOpen), force Workspace.getNewScaleFolder() to 1.0. Over the
    // all-apps drawer it returns the zoomed-out workspace scale (~0.75), which the native animator
    // uses as the folder's open TARGET — so the folder grows to ~75% then snaps to full at animation
    // end (the "2-stage"). Forcing 1.0 makes it target full size and open in one smooth motion.
    @Volatile private var forceFolderScaleOne = false

    /** Number of items the open folder holds (set at open) — the real item count for reorder clamp. */
    @Volatile private var openItemCount = 0
    fun itemCount(): Int = openItemCount

    /** The group id of the currently open overlay (or -1). */
    fun openGroupId(): Long = currentGroupId

    /**
     * The open folder's members as component keys, in current on-screen order — for persisting a
     * reorder. The FolderInfo `rank`s do NOT commit for our folder (the rank-write goes through
     * `updateItemLocationsInDatabaseBatch`, which we guard), so both `rank` and
     * `getItemsInReadingOrder` (which sorts by rank) read STALE — that was the one-reorder lag.
     * Instead sort the item VIEWS by their actual screen position (row then column), which reflects
     * the live reflow. Falls back to contents list order.
     */
    fun currentOrderComponents(): List<String>? {
        fun keyOf(itemInfo: Any?): String? =
            (runCatching { XposedHelpers.callMethod(itemInfo, "getTargetComponent") }.getOrNull() as? ComponentName)
                ?.flattenToShortString()

        val folder = currentFolder
        if (folder != null) {
            val visual = runCatching {
                @Suppress("UNCHECKED_CAST")
                val views = XposedHelpers.callMethod(folder, "getItemsInReadingOrder") as? List<View> ?: return@runCatching null
                views
                    .map { v -> val l = IntArray(2); v.getLocationOnScreen(l); Triple(l[1], l[0], v) } // (y, x, view)
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .mapNotNull { keyOf(it.third.tag) }
            }.getOrNull()
            if (!visual.isNullOrEmpty()) return visual
        }
        val fi = currentFolderInfo ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val contents = (runCatching { XposedHelpers.callMethod(fi, "getContents") }.getOrNull()
                ?: XposedHelpers.getObjectField(fi, "contents")) as? List<Any> ?: return null
            contents.mapNotNull { keyOf(it) }
        }.getOrNull()
    }

    /** Whether a screen point is inside the open folder panel (for drag-out detection). */
    fun folderContains(sx: Int, sy: Int): Boolean {
        val f = currentFolder ?: return false
        return try {
            val loc = IntArray(2)
            f.getLocationOnScreen(loc)
            sx >= loc[0] && sx <= loc[0] + f.width && sy >= loc[1] && sy <= loc[1] + f.height
        } catch (t: Throwable) {
            false
        }
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun installGuards(cl: ClassLoader) {
        if (guardsInstalled) return
        try {
            val folderCls = XposedHelpers.findClass(FOLDER, cl)
            XposedBridge.hookAllMethods(folderCls, "updateItemLocationsInDatabaseBatch", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("updateItemLocationsInDatabaseBatch") {
                        if (guardActive) param.setResult(null)
                    }
                }
            })
            // Persist a user rename: when the OPEN overlay's FolderInfo title changes, write it to
            // our store and repaint the drawer. Only our folder (identity match) is affected.
            val fiCls = XposedHelpers.findClass(FOLDER_INFO, cl)
            XposedBridge.hookAllMethods(fiCls, "setTitle", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Logx.guard("FolderInfo.setTitle:persist") {
                        if (param.thisObject !== currentFolderInfo || currentGroupId < 0) return@guard
                        val newTitle = (param.args?.getOrNull(0) as? CharSequence)?.toString() ?: return@guard
                        FolderStore.renameGroup(currentGroupId, newTitle)
                        DrawerFolderRender.refresh()
                        Logx.i("folder rename persisted: group $currentGroupId -> '$newTitle'")
                    }
                }
            })
            // Centre OUR folder inside animateOpen. Per the dex RE, animateOpen -> d0() -> j0() (the
            // positioner; AOSP's centerAboutIcon, split into j0/h1) computes the folder's x/y + pivot
            // from the FolderIcon's VIEW rect, THEN d0 builds the reveal animator. Our anchor icon has
            // cell (-1,-1), so its rect resolves to the top-left and the folder opens top-anchored;
            // re-centring AFTER animateOpen is too late (the animator already captured the corner).
            // So hook j0()'s return — which runs before the animator is built — and overwrite x/y +
            // pivot to screen-centre. Identity-gated to our folder; native folders are untouched.
            // R8 shifted these single-letter names by one between 18.1.0 and 18.2.0: the positioner
            // is i0() on 18.1.0 and j0() on 18.2.0 (there, i0 is something else entirely). Hook both
            // names on every build - the body is identity-gated to our own folder and only writes
            // layout params, so landing on the wrong method is a no-op rather than a hazard.
            val n = listOf("i0", "j0").sumOf { name -> runCatching {
                XposedBridge.hookAllMethods(folderCls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("Folder positioner:centre") {
                            val folder = param.thisObject as? View ?: return@guard
                            if (folder !== pendingCenterFolder) return@guard
                            val dl = pendingDragLayer ?: return@guard
                            val lp = folder.layoutParams ?: return@guard
                            val lpW = lp.width
                            val lpH = lp.height
                            if (lpW <= 0 || lpH <= 0 || dl.width <= 0 || dl.height <= 0) return@guard
                            XposedHelpers.setObjectField(lp, "x", ((dl.width - lpW) / 2).coerceAtLeast(0))
                            XposedHelpers.setObjectField(lp, "y", ((dl.height - lpH) / 2).coerceAtLeast(0))
                            runCatching { XposedHelpers.setObjectField(lp, "customPosition", true) }
                            folder.layoutParams = lp
                            folder.pivotX = lpW / 2f
                            folder.pivotY = lpH / 2f
                            pendingCenterFolder = null
                            Logx.i("Folder positioner: re-centred to ${(dl.width - lpW) / 2},${(dl.height - lpH) / 2} (lp ${lpW}x$lpH dl ${dl.width}x${dl.height})")
                        }
                    }
                }).size
            }.getOrDefault(0) }
            Logx.i("drawer folder overlay: positioner hook installed on i0+j0 ($n method(s))")

            // Suppress the native open reveal for OUR folder only: Folder implements ClipPathView and
            // a ShapeDelegate animates setClipPath(Path) each frame to grow the body. No-op it while
            // our folder opens so the body is fully drawn and our own scale+fade is the only motion.
            val m = runCatching {
                XposedBridge.hookAllMethods(folderCls, "setClipPath", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("Folder.setClipPath:suppress") {
                            if (param.thisObject === suppressRevealFolder) param.setResult(null)
                        }
                    }
                })
            }.map { it.size }.getOrDefault(0)
            Logx.i("drawer folder overlay: setClipPath suppress hook installed ($m method(s))")

            // The 2-stage open's real cause: over the all-apps drawer, Workspace.getNewScaleFolder()
            // returns the zoomed-out workspace scale (~0.75), which the native folder animator uses as
            // the open target — folder grows to ~75% then snaps to full at animation end. Force 1.0
            // only during OUR animateOpen so the folder targets full size and opens in one motion.
            val ws = runCatching {
                XposedBridge.hookAllMethods(XposedHelpers.findClass(WORKSPACE, cl), "getNewScaleFolder", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("getNewScaleFolder:force1") {
                            if (!forceFolderScaleOne) return@guard
                            val orig = param.result
                            param.result = 1.0f
                            Logx.i("getNewScaleFolder: forced 1.0 (was $orig)")
                        }
                    }
                })
            }.map { it.size }.getOrDefault(0)
            Logx.i("drawer folder overlay: getNewScaleFolder hook installed ($ws method(s))")

            // Safe close on a non-Launcher context (external taskbar all-apps / secondary display).
            // The native close animator FolderAnimationManager.getZuiFolderAnimator dereferences
            // Folder.mLauncherDelegate.e().getWorkspace() — and on the taskbar overlay / secondary
            // display that Launcher is null, so closing OUR reused folder crashed the launcher with an
            // NPE (Launcher.getWorkspace() on null). Folder.handleClose(animate) only builds that
            // animator when animate==true (it branches straight to the non-animated close otherwise),
            // so for our folder on a context that has no real Launcher we force animate=false: an
            // instant, crash-free close. Native folders and the internal drawer (a real Launcher) keep
            // their animated close untouched.
            XposedBridge.hookAllMethods(folderCls, "handleClose", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Logx.guard("Folder.handleClose:safeClose") {
                        val folder = param.thisObject ?: return@guard
                        // Force a non-animated close for ANY folder whose host is not a real Launcher —
                        // i.e. our reused folder on the external taskbar overlay / secondary display.
                        // NOT gated on `currentFolder`: a second overlay open (e.g. opening/editing the
                        // same folder on the INTERNAL display while one is open on the external) reassigns
                        // currentFolder, which used to leave the FIRST (external) folder's close animated,
                        // and FolderAnimationManager.getZuiFolderAnimator then dereferenced a null
                        // Launcher.getWorkspace() → launcher crash (verified: NPE at getZuiFolderAnimator
                        // from a tap-outside close on the TaskbarOverlayDragLayer). Native workspace folders
                        // always have a real Launcher, so this is a no-op for them.
                        // The close animator reads Workspace.getNewScaleFolder() again while it
                        // builds its target rect, and over the drawer that is ~0.75 rather than 1.0.
                        // Forcing it only around animateOpen left the close aiming at a rect scaled
                        // differently from the one the open grew out of, so the folder converged
                        // near - but not on - its icon. Armed here and disarmed in afterHooked below.
                        if (folder === currentFolder) forceFolderScaleOne = true
                        if (hasRealLauncher(folder)) return@guard
                        val args = param.args ?: return@guard
                        if (args.isNotEmpty() && args[0] == true) {
                            args[0] = false
                            Logx.i("folder close: forced non-animated (no real Launcher on this context)")
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // The animator is fully built by now, so the forced 1.0 has done its job and must
                    // not leak into a native workspace folder's own close.
                    forceFolderScaleOne = false
                    Logx.guard("Folder.handleClose:cleanup") {
                        if (param.thisObject !== currentFolder) return@guard
                        // Safety net for the ghost folder: make sure our anchor icon is detached after
                        // a close (it is normally removed on open; this covers any surviving instance).
                        val icon = currentFolderIcon
                        currentFolderIcon = null
                        handler.post { runCatching { (icon?.parent as? ViewGroup)?.removeView(icon) } }
                    }
                }
            })
            // Make the backdrop screenshot cover the whole screen, on the builds that do not.
            //
            // The capture method - g0() on 18.1.0, h0() on 18.2.0 - crops to the union of the folder
            // icon's rect and Folder.N (mPadShowRect), then setPadding()s the backdrop view down to
            // exactly that crop. 18.2.0 additionally clamps the crop to the DragLayer (verified: four
            // Math.min/max pairs against getWidth/getHeight, against 18.1.0's two, which only do the
            // union). So on 18.1.0 the capture is a small box around the folder and its icon, and the
            // padding pins it there - the offset patch reported on that build.
            //
            // Widening Folder.N to the whole DragLayer before the capture makes the union full-screen
            // on BOTH builds, so the crop is full-screen and the padding comes out zero by itself.
            // That is the same end state 18.2.0 reaches on its own, reached the same way - rather than
            // stretching a partial capture to fit, which is what the first attempt did and why the
            // launcher behind the folder looked magnified.
            //
            // N is restored afterwards: it is mPadShowRect, which the pad-mode paths also read.
            val cap = listOf("g0", "h0").sumOf { name -> runCatching {
                XposedBridge.hookAllMethods(folderCls, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logx.guard("Folder.$name:fullScreenCapture") {
                            val folder = param.thisObject as? View ?: return@guard
                            if (folder !== currentFolder) return@guard
                            val dl = folder.parent as? ViewGroup ?: return@guard
                            if (dl.width <= 0 || dl.height <= 0) return@guard
                            val n = XposedHelpers.getObjectField(folder, "N") as? RectF ?: return@guard
                            savedCaptureRect = RectF(n)
                            n.set(0f, 0f, dl.width.toFloat(), dl.height.toFloat())
                            Logx.i("backdrop: widened capture rect to ${dl.width}x${dl.height} for $name")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        Logx.guard("Folder.$name:restoreN") {
                            val saved = savedCaptureRect ?: return@guard
                            savedCaptureRect = null
                            val folder = param.thisObject as? View ?: return@guard
                            (XposedHelpers.getObjectField(folder, "N") as? RectF)?.set(saved)
                        }
                    }
                }).size
            }.getOrDefault(0) }
            Logx.i("drawer folder overlay: full-screen capture hook installed on g0+h0 ($cap method(s))")
            Logx.i("drawer folder overlay: handleClose safe-close hook installed")

            guardsInstalled = true
            Logx.i("drawer folder overlay: DB guard + rename hook installed")
        } catch (t: Throwable) {
            Logx.e("drawer folder overlay: guard install failed", t)
        }
    }

    /** Open the real folder over the drawer for [apps] (each a launcher AppInfo). */
    fun open(
        cl: ClassLoader,
        viewCtx: Context,
        apps: List<Any>,
        title: CharSequence,
        groupId: Long,
        sourceIcon: View? = null,
    ) {
        guardActive = true
        openItemCount = apps.size
        // bug3 diag: a folder tap that "does nothing" — confirm open() is even reached, and whether a
        // prior overlay was left attached (a leftover modal folder could swallow the tap).
        Logx.i("drawer folder overlay: open() group=$groupId apps=${apps.size} leftover=${currentFolder?.isAttachedToWindow == true}")
        // Close any overlay still attached (e.g. one open on the external while we open again from the
        // internal display). Otherwise it is ORPHANED — the single `currentFolder` gets reassigned
        // below, so the stale folder's later close is no longer our guarded folder and would crash. The
        // close is non-animated (the handleClose guard above forces it), so it is safe on a non-Launcher
        // context. No-op in the normal single-overlay flow (leftover == false).
        currentFolder?.let { prev ->
            if (runCatching { prev.isAttachedToWindow }.getOrDefault(false)) {
                runCatching { XposedHelpers.callMethod(prev, "close", false) }
                Logx.i("drawer folder overlay: closed leftover overlay before opening")
            }
        }
        try {
            val activityCtxCls = XposedHelpers.findClass(ACTIVITY_CONTEXT, cl)
            val folderInfoCls = XposedHelpers.findClass(FOLDER_INFO, cl)
            val folderIconCls = XposedHelpers.findClass(FOLDER_ICON, cl)

            val launcher = XposedHelpers.callStaticMethod(activityCtxCls, "lookupContext", viewCtx)
            val launcherCtx = launcher as Context
            val dragLayer = XposedHelpers.callMethod(launcher, "getDragLayer") as ViewGroup

            // FolderInfo with both apps (default title "폴더" is what the launcher uses). Each item
            // is added with an explicit, distinct rank so the folder places them in separate cells
            // and reorders cleanly — the missing ranks were why items collided/swapped/vanished.
            val fi = folderInfoCls.getDeclaredConstructor().newInstance()
            val wsiCls = runCatching { XposedHelpers.findClass(WORKSPACE_ITEM_INFO, cl) }.getOrNull()
            apps.forEachIndexed { i, app ->
                // Most members are AppInfos -> makeWorkspaceItem() gives the folder-item copy. But a
                // member handed in from a folder-drag-out is ALREADY a WorkspaceItemInfo (it has no
                // makeWorkspaceItem, which used to throw and abort the whole open). Use it directly —
                // copy-constructed when possible so we don't mutate the caller's object as we set rank.
                val wsi = if (wsiCls != null && wsiCls.isInstance(app)) {
                    runCatching { wsiCls.getConstructor(wsiCls).newInstance(app) }.getOrNull() ?: app
                } else {
                    XposedHelpers.callMethod(app, "makeWorkspaceItem", launcherCtx)
                }
                runCatching { XposedHelpers.setObjectField(wsi, "rank", i) }
                addToFolder(fi, wsi, i)
            }
            // The R8 add(item, rank, animate) overload did NOT stick a distinct rank — every item ended
            // up rank 0, so on a no-move release the folder re-added the picked item at rank 0 (it
            // jumped to first). Force distinct ranks (0,1,2,…) on the final contents so removal/re-add
            // restores the original position.
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val contents = (runCatching { XposedHelpers.callMethod(fi, "getContents") }.getOrNull()
                    ?: XposedHelpers.getObjectField(fi, "contents")) as? List<Any>
                contents?.forEachIndexed { i, item -> runCatching { XposedHelpers.setObjectField(item, "rank", i) } }
            }
            // Show the group's name, and remember which group this overlay is editing so a rename
            // (FolderInfo.setTitle) persists to the right group. Set the field directly so seeding
            // the title here does not re-trigger our own rename-persist hook.
            runCatching { XposedHelpers.setObjectField(fi, "title", title) }
            currentFolderInfo = fi
            currentGroupId = groupId

            // Build the real FolderIcon (+ its Folder) via the supported factory.
            var resId = launcherCtx.resources.getIdentifier("folder_icon", "layout", launcherCtx.packageName)
            if (resId == 0) resId = launcherCtx.resources.getIdentifier("user_folder_icon_normalized", "layout", launcherCtx.packageName)
            val factory = folderIconCls.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.returnType == folderIconCls &&
                    it.parameterTypes.any { p -> p == Integer.TYPE } &&
                    it.parameterTypes.any { p -> p.isAssignableFrom(fi.javaClass) }
            } ?: run { Logx.e("drawer folder overlay: no FolderIcon factory"); return }
            factory.isAccessible = true
            val args = factory.parameterTypes.map { p ->
                when {
                    p == Integer.TYPE -> resId
                    p.isAssignableFrom(launcher.javaClass) -> launcher
                    p.isAssignableFrom(dragLayer.javaClass) -> dragLayer
                    p.isAssignableFrom(fi.javaClass) -> fi
                    else -> null
                }
            }.toTypedArray()
            val folderIcon = factory.invoke(null, *args) as View

            // Give the anchor icon a SMALL, CENTRED footprint. Added with default LayoutParams it fills
            // the drag layer (measured 3040x1797), which corrupts every icon-relative computation the
            // native open does — folder position, pivot, AND the clip-reveal origin (getZuiPreviewBounds
            // (mFolderIcon)). That corrupted reveal origin is the real cause of the "2-stage" open. A
            // small centred anchor makes the native reveal grow smoothly from the centre, like a real
            // workspace folder.
            folderIcon.visibility = View.INVISIBLE
            val iconSz = runCatching {
                val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                folderIcon.measure(spec, spec)
                folderIcon.measuredWidth
            }.getOrNull()?.takeIf { it in 1..600 } ?: 200
            dragLayer.addView(folderIcon)
            // Where this anchor sits IS where the folder animates from and back to. The close
            // animator builds its target rect live, at close time, from mFolderIcon's left/top
            // (dragLayer.getDescendantRectRelativeToSelf) - nothing is captured at open - and only
            // the top-left is used; the size comes from the PreviewBackground. Removing the anchor
            // after the open does not erase that: removeView leaves mLeft/mTop alone, so a detached
            // icon still reports its last laid-out position. Parking it at the screen centre is
            // therefore exactly why the folder used to shrink to the centre instead of to its icon.
            //
            // So put it on the real drawer icon. Open and close share one rect - the from/to pair is
            // just swapped - so this makes the folder grow out of its icon and collapse back into
            // it, while the positioner hook still lands the open folder itself in the centre.
            runCatching {
                val lp = folderIcon.layoutParams
                lp.width = iconSz
                lp.height = iconSz
                val at = sourceIcon?.let { rectInDragLayer(it, dragLayer) }
                val x = at?.let { it.centerX() - iconSz / 2 } ?: ((dragLayer.width - iconSz) / 2)
                val y = at?.let { it.centerY() - iconSz / 2 } ?: ((dragLayer.height - iconSz) / 2)
                XposedHelpers.setObjectField(lp, "x", x.coerceIn(0, (dragLayer.width - iconSz).coerceAtLeast(0)))
                XposedHelpers.setObjectField(lp, "y", y.coerceIn(0, (dragLayer.height - iconSz).coerceAtLeast(0)))
                setCustomPosition(lp, true)
                folderIcon.layoutParams = lp
                // Lay it out explicitly as well. The frame is what getDescendantRectRelativeToSelf
                // reads, and the anchor is removed before the drag layer's next layout pass would
                // otherwise have applied these params.
                val spec = View.MeasureSpec.makeMeasureSpec(iconSz, View.MeasureSpec.EXACTLY)
                folderIcon.measure(spec, spec)
                folderIcon.layout(x, y, x + iconSz, y + iconSz)
                Logx.i("drawer folder overlay: anchor ${iconSz}x$iconSz at $x,$y " +
                    (if (at != null) "(on the drawer icon $at)" else "(centred - no source icon)"))
            }
            val folder = XposedHelpers.callMethod(folderIcon, "getFolder") as View
            currentFolder = folder
            currentFolderIcon = folderIcon
            // Arm the j0() hook to re-centre THIS folder (identity-gated) as it opens. We do NOT arm
            // the reveal suppression: the native clip-path reveal IS the smooth open, so we let it run.
            pendingCenterFolder = folder
            pendingDragLayer = dragLayer

            folderIcon.post {
                Logx.guard("folder-open") {
                    // Force the folder's open TARGET scale to 1.0 for the duration of animateOpen (which
                    // reads Workspace.getNewScaleFolder() synchronously while building the animator).
                    forceFolderScaleOne = true
                    try {
                        XposedHelpers.callMethod(folder, "animateOpen")
                    } catch (t: Throwable) {
                        // On a non-Launcher context (external taskbar all-apps), the native open
                        // animator dereferences Launcher.getWorkspace() — null there — and throws. The
                        // folder view is already shown by then, so SWALLOW it: letting it propagate to
                        // the guard skipped the anchor-removal below, and the surviving anchor icon was
                        // re-shown by the native close as a nameless ghost folder in the screen centre.
                        Logx.e("folder-open: animateOpen threw (no Launcher on this context?); continuing cleanup", t)
                    } finally {
                        forceFolderScaleOne = false
                    }
                    // Let the NATIVE clip-path reveal run start-to-finish as the ONLY open motion — it
                    // is already a single smooth expansion (like a real workspace folder). Our earlier
                    // scale+fade + reveal-suppression FOUGHT it: our 200 ms animation ended and lifted
                    // the setClipPath suppression while the ~300 ms native reveal still had ~⅓ left,
                    // snapping the clip back to a partial rounded rect — that straddle was the "2-stage".
                    // With the folder j0-centred from the first frame and a small centred anchor, the
                    // native reveal alone opens cleanly.
                    logGridGeometry(viewCtx, folder)
                    // Remove the anchor icon now, whether or not animateOpen succeeded (the folder.post
                    // below can be unreliable if the open animator failed). removeView is idempotent.
                    runCatching { (folderIcon.parent as? ViewGroup)?.removeView(folderIcon) }
                    folder.post {
                        Logx.guard("folder-position") {
                            centre(folder, dragLayer)
                            runCatching { (folderIcon.parent as? ViewGroup)?.removeView(folderIcon) }
                            applyBackdropBlur(dragLayer)
                            hideDropTargetBar(dragLayer)
                            Logx.i("drawer folder overlay: opened (${apps.size} apps)")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Logx.e("drawer folder overlay: open failed", t)
        } finally {
            handler.postDelayed({ guardActive = false }, 2500)
            // Safety: never leave the reveal suppressed (the animation's withEndAction normally clears
            // it; this covers the case where the open path threw before the animation started).
            handler.postDelayed({ suppressRevealFolder = null }, 1500)
        }
    }

    /**
     * The launcher's real folder-open blur (per bytecode RE): ZuiFolderBgImageView (Folder.v0) is
     * a full-screen ImageView already holding a screenshot of the launcher (captured by the native
     * open path); the launcher only blurs it when a big-folder blur flag is on. We apply the blur
     * ourselves — View.setRenderEffect(createBlurEffect(70,70,DECAL)) — which is the exact effect
     * the native listener uses, and disable the folder-icon-shaped clip so it blurs full-screen
     * behind the folder. State-independent, so it works over the drawer.
     */
    private fun applyBackdropBlur(dl: ViewGroup) {
        if (Build.VERSION.SDK_INT < 31) return
        val v0 = findChild(dl) { it.contains("ZuiFolderBgImageView", ignoreCase = true) } ?: run {
            Logx.i("blur: ZuiFolderBgImageView not found"); return
        }
        val drawable = (v0 as? ImageView)?.drawable
        Logx.i("blur: v0=${v0.javaClass.simpleName} size=${v0.width}x${v0.height} drawable=${drawable?.javaClass?.simpleName}")
        // Remove the rounded folder-icon-shaped clips so the blur covers the whole backdrop.
        // Still needed: 18.1.0's clip consumer additionally clipRect()s to the animated folder
        // rect, which 18.2.0 dropped.
        runCatching { v0.clipToOutline = false }
        runCatching {
            v0.javaClass.methods.firstOrNull { it.name == "setClipConsumer" && it.parameterCount == 1 }?.invoke(v0, null)
        }
        // A safety net for the padding, which the capture hook above should already have made zero.
        // The launcher screenshots itself into this view and then setPadding()s the view down to
        // exactly the captured rect; widening Folder.N makes that rect full-screen, so nothing is
        // left to clear. If something IS cleared here, the widening did not take on this build and
        // the backdrop is a partial capture pinned near the folder icon - the 18.1.0 symptom. The
        // log line is the signal; it should never appear.
        fun unpad(where: String) = runCatching {
            if (v0.paddingLeft != 0 || v0.paddingTop != 0 || v0.paddingRight != 0 || v0.paddingBottom != 0) {
                Logx.i("blur: backdrop padding $where was " +
                    "(${v0.paddingLeft},${v0.paddingTop},${v0.paddingRight},${v0.paddingBottom}) -> 0")
                v0.setPadding(0, 0, 0, 0)
            }
        }
        // Deliberately no scaleType override: stretching a partial capture to fill the view is what
        // made the launcher behind an open folder look magnified.
        unpad("at open")
        // The big-folder/taskbar path calls setPadding on this view again after this frame.
        runCatching { v0.post { Logx.guard("blur: repad") { unpad("next frame") } } }
        runCatching {
            v0.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.DECAL))
            Logx.i("blur: setRenderEffect(70) applied")
        }.onFailure { Logx.e("blur: setRenderEffect failed", it) }
    }

    /**
     * Log what the opened folder's grid actually resolved to.
     *
     * A report said the drawer folder shows a 2x2 grid where a workspace folder shows 4x4. A dex
     * survey of this build found 4x4 to be the only value configured for a device this size - the
     * smallest grid declared anywhere is 3x3, and nothing declares 2 columns - and only one
     * InvariantDeviceProfile exists per process, so no context can legitimately produce 2x2. That
     * leaves two candidates, which look alike from a photograph and cannot be told apart by
     * reasoning: the OPENED folder's FolderPagedView grid, or the folder ICON's preview, whose
     * ClippedFolderIconLayoutRule shows 4 items in a 2x2 arrangement until init() raises it to 16.
     * So measure both rather than guess which one the report meant.
     */
    private fun logGridGeometry(viewCtx: Context, folder: Any) {
        Logx.guard("folder grid probe") {
            val dp = runCatching { XposedHelpers.callMethod(viewCtx, "getDeviceProfile") }.getOrNull()
            val cols = dp?.let { runCatching { XposedHelpers.getIntField(it, "numFolderColumns") }.getOrNull() }
            val rows = dp?.let { runCatching { XposedHelpers.getIntField(it, "numFolderRows") }.getOrNull() }
            Logx.i("folder grid: ctx=${viewCtx.javaClass.simpleName} deviceProfile=${dp?.javaClass?.simpleName} " +
                "numFolderColumns=$cols numFolderRows=$rows")
            // The paged content's own counts, i.e. what is actually laid out.
            val content = runCatching { XposedHelpers.getObjectField(folder, "mContent") }.getOrNull()
            if (content != null) {
                val counts = content.javaClass.declaredFields
                    .filter { it.type == Integer.TYPE }
                    .mapNotNull { f ->
                        runCatching { f.isAccessible = true; "${f.name}=${f.getInt(content)}" }.getOrNull()
                    }
                Logx.i("folder grid: ${content.javaClass.simpleName} ints[${counts.joinToString()}]")
            } else {
                Logx.i("folder grid: mContent not reachable on ${folder.javaClass.simpleName}")
            }
        }
    }

    /** [view]'s bounds expressed in [dragLayer]'s coordinates. */
    private fun rectInDragLayer(view: View, dragLayer: ViewGroup): Rect? = runCatching {
        val v = IntArray(2)
        val d = IntArray(2)
        view.getLocationOnScreen(v)
        dragLayer.getLocationOnScreen(d)
        if (view.width <= 0 || view.height <= 0) return null
        Rect(v[0] - d[0], v[1] - d[1], v[0] - d[0] + view.width, v[1] - d[1] + view.height)
    }.getOrNull()

    /** Hide the drop-target bar left visible at the top by our suppressed drag-end. */
    private fun hideDropTargetBar(dl: ViewGroup) {
        val bar = findChild(dl) { it.contains("DropTargetBar", ignoreCase = true) } ?: return
        runCatching { XposedHelpers.callMethod(bar, "animateToVisibility", false) }
            .onSuccess { Logx.i("blur: hid ${bar.javaClass.simpleName}") }
    }

    /**
     * True only when [folder]'s host is a real Launcher (the internal home drawer), where the native
     * close animator's Launcher.getWorkspace() call is safe. On the external taskbar all-apps
     * (TaskbarOverlayContext) and the secondary-display drawer the host is not a Launcher, so
     * Folder.mLauncherDelegate.e() is null and the animator would NPE — those must close un-animated.
     */
    private fun hasRealLauncher(folder: Any): Boolean {
        val actx = runCatching { XposedHelpers.getObjectField(folder, "mActivityContext") }.getOrNull() ?: return false
        val launcherCls = runCatching {
            XposedHelpers.findClass("com.android.launcher3.Launcher", folder.javaClass.classLoader)
        }.getOrNull() ?: return false
        return launcherCls.isInstance(actx)
    }

    private fun findChild(dl: ViewGroup, pred: (String) -> Boolean): View? {
        for (i in 0 until dl.childCount) {
            val c = dl.getChildAt(i) ?: continue
            if (pred(c.javaClass.simpleName)) return c
        }
        return null
    }

    /** Centre the open folder via its BaseDragLayer.LayoutParams x/y (public int fields). */
    private fun centre(folder: View, dl: View) {
        val lp = folder.layoutParams ?: return
        // Before the first layout pass folder.width/height are 0; fall back to the measured size so
        // synchronous centring (same frame as animateOpen) still lands correctly.
        val fw = folder.width.takeIf { it > 0 } ?: folder.measuredWidth
        val fh = folder.height.takeIf { it > 0 } ?: folder.measuredHeight
        val tx = ((dl.width - fw) / 2).coerceAtLeast(0)
        val ty = ((dl.height - fh) / 2).coerceAtLeast(0)
        val cx = runCatching { XposedHelpers.getObjectField(lp, "x") as? Int }.getOrNull() ?: Int.MIN_VALUE
        val cy = runCatching { XposedHelpers.getObjectField(lp, "y") as? Int }.getOrNull() ?: Int.MIN_VALUE
        // Already centred by the pre-centred anchor (the common path now)? Then do NOT relayout mid-
        // animation — that requestLayout is what yanked the folder across and made the open stutter.
        // Only correct a genuine miss.
        if (kotlin.math.abs(cx - tx) <= 8 && kotlin.math.abs(cy - ty) <= 8) {
            Logx.i("overlay centre: already centred (cur=$cx,$cy target=$tx,$ty) — skip relayout")
            return
        }
        Logx.i("overlay centre: REPOSITION cur=$cx,$cy -> $tx,$ty (folder ${folder.width}x${folder.height})")
        runCatching { XposedHelpers.setObjectField(lp, "x", tx) }
        runCatching { XposedHelpers.setObjectField(lp, "y", ty) }
        setCustomPosition(lp, true)
        folder.layoutParams = lp
        folder.requestLayout()
    }

    private fun setCustomPosition(lp: Any, value: Boolean) {
        var cls: Class<*>? = lp.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type != java.lang.Boolean.TYPE || !f.name.contains("ustom", ignoreCase = true)) continue
                f.isAccessible = true
                try { f.setBoolean(lp, value); return } catch (t: Throwable) {}
            }
            cls = cls.superclass
        }
    }

    private fun addToFolder(fi: Any, wsi: Any, rank: Int) {
        val adds = fi.javaClass.methods.filter { it.name == "add" }
        // Prefer add(item, rank, animate): it sets item.rank AND item.container = this folder's id,
        // which is exactly the model consistency the reorder needs. Fall back to 2-/1-arg.
        for (m in adds.sortedBy { arrayOf(3, 2, 1).indexOf(it.parameterCount).let { i -> if (i < 0) 9 else i } }) {
            try {
                when (m.parameterCount) {
                    3 -> m.invoke(fi, wsi, rank, false)
                    2 -> m.invoke(fi, wsi, false)
                    1 -> m.invoke(fi, wsi)
                    else -> continue
                }
                return
            } catch (t: Throwable) {
            }
        }
        Logx.e("drawer folder overlay: no add() overload accepted the item")
    }
}
